package com.codex.applockguard.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.ServiceCompat;

import com.codex.applockguard.R;
import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.DeviceIdentityStore;
import com.codex.applockguard.data.DiagnosticStore;
import com.codex.applockguard.data.ServiceStateStore;
import com.codex.applockguard.data.UsageStore;
import com.codex.applockguard.net.ApprovalApi;
import com.codex.applockguard.sync.StudentSyncManager;
import com.codex.applockguard.ui.GuardModeActivity;
import com.codex.applockguard.ui.LockActivity;
import com.codex.applockguard.util.AppCatalog;
import com.codex.applockguard.util.ForegroundAppReader;
import com.codex.applockguard.util.LauncherVisibilityManager;
import com.codex.applockguard.util.LockForegroundVerifier;
import com.codex.applockguard.util.PermissionUtils;
import com.codex.applockguard.util.ProtectionStatusResolver;
import com.codex.applockguard.util.ProtectionStatusSnapshot;
import com.codex.applockguard.util.SystemUsageReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class AppMonitorService extends Service {
    private static final String TAG = "AppMonitorService";
    private static final String CHANNEL_ID = "app_lock_monitor";
    private static final int NOTIFICATION_ID = 1001;
    private static final long LOOP_MS = 500L;
    private static final long COMMAND_POLL_MS = 4_000L;
    private static final long FALLBACK_LOCK_COOLDOWN_MS = 2_500L;
    private static final long OVERLAY_CONFIRM_MS = 350L;
    private static final long GUARD_EXIT_CONFIRM_MS = 700L;
    private static final long WATCHDOG_SCHEDULE_MS = 3_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ApprovalApi approvalApi = new ApprovalApi();
    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            try {
                checkForegroundApp();
            } catch (Throwable throwable) {
                String message = formatThrowable("后台保护循环异常", throwable);
                recordMonitorEvent(message);
                if (serviceStateStore != null) {
                    serviceStateStore.recordMonitorLoopError(message);
                }
                Log.e(TAG, "Monitor loop failed", throwable);
            } finally {
                if (monitorHandler != null) {
                    monitorHandler.postDelayed(this, LOOP_MS);
                }
            }
        }
    };

    private AppLockStore store;
    private DeviceIdentityStore identityStore;
    private DiagnosticStore diagnosticStore;
    private ServiceStateStore serviceStateStore;
    private UsageStore usageStore;
    private HandlerThread monitorThread;
    private Handler monitorHandler;
    private String lastNotificationText;
    private long lastCommandPollAt;
    private String trackedPackage;
    private long trackedAt;
    private String lastFallbackLockPackage;
    private long lastFallbackLockAt;
    private String lastGuardExitConfirmPackage;
    private long lastGuardExitConfirmAt;
    private PowerManager.WakeLock monitorWakeLock;
    private volatile boolean commandPollInFlight;
    private long lastWakeLockStateRecordAt;
    private long lastWatchdogScheduleAt;

    public static boolean start(Context context) {
        Intent intent = new Intent(context, AppMonitorService.class);
        ServiceStateStore stateStore = new ServiceStateStore(context);
        stateStore.recordRestartAttempt("后台保护启动请求已发出");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            stateStore.recordRestartAttempt("后台保护启动请求已交给系统");
            return true;
        } catch (RuntimeException exception) {
            stateStore.recordRestartAttempt(
                    "后台保护启动失败：" + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            Log.e(TAG, "Failed to start monitor service", exception);
            return false;
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, AppMonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new AppLockStore(this);
        identityStore = new DeviceIdentityStore(this);
        diagnosticStore = new DiagnosticStore(this);
        serviceStateStore = new ServiceStateStore(this);
        usageStore = new UsageStore(this);
        if (!promoteToForeground(getString(R.string.monitor_notification_text))) {
            serviceStateStore.recordMonitorEvent("前台服务通知启动失败，后台保护已停止");
            stopSelf();
            return;
        }
        acquireMonitorWakeLock("服务创建");
        serviceStateStore.touchMonitorHeartbeat();
        serviceStateStore.recordMonitorEvent("后台保护服务已创建，心跳已写入");
        startMonitorThread();
        monitorHandler.post(loop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!promoteToForeground(lastNotificationText == null
                ? getString(R.string.monitor_notification_text)
                : lastNotificationText)) {
            if (serviceStateStore != null) {
                serviceStateStore.recordMonitorEvent("前台服务通知刷新失败，后台保护已停止");
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireMonitorWakeLock("收到启动命令");
        if (serviceStateStore != null) {
            serviceStateStore.recordMonitorEvent("后台保护服务收到启动命令，前台通知正常");
        }
        startMonitorThread();
        if (monitorHandler != null) {
            monitorHandler.removeCallbacks(loop);
            monitorHandler.post(loop);
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        recordMonitorEvent("最近任务划掉，准备调度自恢复");
        scheduleRestartIfNeeded();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (monitorHandler != null) {
            monitorHandler.removeCallbacksAndMessages(null);
        }
        if (monitorThread != null) {
            monitorThread.quitSafely();
            monitorThread = null;
            monitorHandler = null;
        }
        releaseMonitorWakeLock();
        mainHandler.removeCallbacksAndMessages(null);
        recordMonitorEvent("监控服务销毁，准备调度自恢复");
        scheduleRestartIfNeeded();
        if (serviceStateStore != null) {
            serviceStateStore.clearMonitorHeartbeat();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startMonitorThread() {
        if (monitorThread != null && monitorThread.isAlive() && monitorHandler != null) {
            return;
        }
        monitorThread = new HandlerThread("AppLockMonitorLoop");
        monitorThread.start();
        monitorHandler = new Handler(monitorThread.getLooper());
    }

    private void acquireMonitorWakeLock(String reason) {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                recordMonitorEvent("后台保护保活失败：PowerManager 不可用");
                if (serviceStateStore != null) {
                    serviceStateStore.recordWakeLockState(false, "PowerManager 不可用");
                }
                return;
            }
            if (monitorWakeLock == null) {
                monitorWakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "AppLockGuard:Monitor"
                );
                monitorWakeLock.setReferenceCounted(false);
            }
            if (!monitorWakeLock.isHeld()) {
                monitorWakeLock.acquire();
                String event = "后台保护保活已开启：" + reason;
                recordMonitorEvent(event);
                if (serviceStateStore != null) {
                    lastWakeLockStateRecordAt = System.currentTimeMillis();
                    serviceStateStore.recordWakeLockState(true, event);
                }
            } else if (serviceStateStore != null) {
                long now = System.currentTimeMillis();
                if (now - lastWakeLockStateRecordAt > 5_000L) {
                    lastWakeLockStateRecordAt = now;
                    serviceStateStore.recordWakeLockState(true, "后台保护保活保持中：" + reason);
                }
            }
        } catch (RuntimeException exception) {
            String event = "后台保护保活失败：" + exception.getClass().getSimpleName() + ": " + exception.getMessage();
            recordMonitorEvent(event);
            if (serviceStateStore != null) {
                serviceStateStore.recordWakeLockState(false, event);
            }
            Log.e(TAG, "Failed to acquire monitor wake lock", exception);
        }
    }

    private void releaseMonitorWakeLock() {
        try {
            if (monitorWakeLock != null && monitorWakeLock.isHeld()) {
                monitorWakeLock.release();
                if (serviceStateStore != null) {
                    serviceStateStore.recordWakeLockState(false, "后台保护服务销毁，释放 WakeLock");
                }
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Failed to release monitor wake lock", exception);
        } finally {
            monitorWakeLock = null;
        }
    }

    private boolean promoteToForeground(String text) {
        String safeText = text == null ? getString(R.string.monitor_notification_text) : text;
        lastNotificationText = safeText;
        Notification notification = buildNotification(safeText);
        int serviceType = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType);
            return true;
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to promote foreground service", throwable);
            if (serviceStateStore != null) {
                serviceStateStore.recordRestartAttempt(
                        "启动前台通知失败：" + throwable.getClass().getSimpleName() + ": " + throwable.getMessage()
                );
            }
            return false;
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.monitor_channel),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.monitor_notification_text));
            manager.createNotificationChannel(channel);
        }

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.monitor_notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_lock)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        String safeText = text == null ? getString(R.string.monitor_notification_text) : text;
        if (safeText.equals(lastNotificationText)) {
            return;
        }
        lastNotificationText = safeText;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(safeText));
        }
    }

    private void checkForegroundApp() {
        long now = System.currentTimeMillis();
        serviceStateStore.touchMonitorHeartbeat();
        acquireMonitorWakeLock("后台循环心跳");
        maybeScheduleWatchdog(now);
        maybePullRemoteCommands(now);
        maybeApplyDailyTimeRanges(now);

        if (!store.isMonitorEnabled()) {
            serviceStateStore.recordMonitorEvent("普通保护未开启，后台保持远程控制待命");
            updateNotification("远程控制待命，普通保护未开启。");
            recordLocalUsage(null, now);
            return;
        }

        ProtectionStatusSnapshot snapshot = ProtectionStatusResolver.resolve(this);
        if (store.isGuardModeActive()) {
            updateNotification("守门模式运行中，剩余 " + formatRemaining(store.getGuardModeRemainingMs()));
        } else {
            updateNotification(snapshot.blockReady
                    ? getString(R.string.monitor_notification_text)
                    : snapshot.title + "：" + snapshot.detail);
        }

        if (!PermissionUtils.hasUsageAccess(this)) {
            recordLocalUsage(null, now);
            return;
        }

        String packageName = ForegroundAppReader.getForegroundPackage(this);
        if (packageName == null || getPackageName().equals(packageName)) {
            recordLocalUsage(null, now);
            return;
        }

        diagnosticStore.recordForeground(packageName);
        recordLocalUsage(packageName, now);
        if (store.isGuardModeActive() && AppCatalog.isGuardExitSurface(packageName)) {
            recordGuardDecision(packageName, false);
            diagnosticStore.recordMonitorBlockCandidate(packageName);
            scheduleGuardExitSurfaceCheck(packageName, now);
            return;
        }
        maybeTriggerFallbackLock(packageName, now);
    }

    private void scheduleGuardExitSurfaceCheck(String surfacePackage, long now) {
        if (surfacePackage != null
                && surfacePackage.equals(lastGuardExitConfirmPackage)
                && now - lastGuardExitConfirmAt < GUARD_EXIT_CONFIRM_MS) {
            return;
        }
        lastGuardExitConfirmPackage = surfacePackage;
        lastGuardExitConfirmAt = now;
        if (monitorHandler != null) {
            monitorHandler.postDelayed(() -> confirmGuardExitSurface(surfacePackage), GUARD_EXIT_CONFIRM_MS);
        }
    }

    private void confirmGuardExitSurface(String surfacePackage) {
        if (store == null || diagnosticStore == null || !store.isGuardModeActive()) {
            return;
        }
        String currentPackage = ForegroundAppReader.getForegroundPackage(this);
        if (currentPackage == null || getPackageName().equals(currentPackage)) {
            currentPackage = surfacePackage;
        }
        diagnosticStore.recordForeground(currentPackage);
        recordLocalUsage(currentPackage, System.currentTimeMillis());
        if (AppCatalog.isGuardExitSurface(currentPackage)) {
            recordGuardDecision(currentPackage, false);
            diagnosticStore.recordMonitorBlockCandidate(currentPackage);
            LockAccessibilityService.requestGuardModeBlock(currentPackage, "monitor_guard_exit_surface_confirmed", currentPackage);
            return;
        }
        if (store.isAllowedInGuardMode(currentPackage)) {
            recordGuardDecision(currentPackage, true);
            return;
        }
        maybeTriggerFallbackLock(currentPackage, System.currentTimeMillis());
    }

    private void maybeTriggerFallbackLock(String packageName, long now) {
        long todayUsage = SystemUsageReader.getTodayUsage(this, packageName);
        if (!store.shouldBlockPackage(packageName, todayUsage, now)) {
            recordGuardDecision(packageName, true);
            return;
        }
        recordGuardDecision(packageName, false);
        diagnosticStore.recordMonitorBlockCandidate(packageName);
        if (store.isGuardModeActive()) {
            openGuardModeActivity(packageName, "monitor_guard_mode", now);
            return;
        }

        diagnosticStore.recordLockAttempt(packageName, "monitor_fallback");

        if (LockActivity.isShowingForPackage(packageName)) {
            diagnosticStore.recordFallbackLockResult(packageName, "跳过兜底", "LockActivity 已经显示");
            return;
        }
        if (packageName.equals(lastFallbackLockPackage) && now - lastFallbackLockAt < FALLBACK_LOCK_COOLDOWN_MS) {
            diagnosticStore.recordFallbackLockResult(packageName, "跳过兜底", "兜底锁层冷却中");
            return;
        }
        if (LockAccessibilityService.isOverlayShowingForPackage(packageName)) {
            diagnosticStore.recordFallbackLockResult(packageName, "跳过兜底", "无障碍锁层已经显示");
            return;
        }

        lastFallbackLockPackage = packageName;
        lastFallbackLockAt = now;

        boolean requested = LockAccessibilityService.requestLockOverlay(packageName, "monitor_fallback", packageName);
        if (requested) {
            diagnosticStore.recordFallbackLockResult(packageName, "请求无障碍锁层", "已发送");
            if (monitorHandler != null) {
                monitorHandler.postDelayed(() -> confirmOverlayOrOpenActivity(packageName, now), OVERLAY_CONFIRM_MS);
            }
            return;
        }

        openFallbackActivity(packageName, "无障碍实例不可用", now);
    }

    private void confirmOverlayOrOpenActivity(String packageName, long requestAt) {
        if (diagnosticStore == null || packageName == null) {
            return;
        }
        if (LockActivity.isShowingForPackage(packageName)) {
            diagnosticStore.recordFallbackLockResult(packageName, "Activity 兜底检查", "LockActivity 已经显示");
            return;
        }
        if (LockAccessibilityService.isOverlayShowingForPackage(packageName)) {
            diagnosticStore.recordFallbackLockResult(packageName, "无障碍锁层确认", "服务实例显示中");
            return;
        }
        boolean overlayShown = packageName.equals(diagnosticStore.getLastLockTargetPackage())
                && diagnosticStore.getLastLockShowAt() >= requestAt;
        if (overlayShown) {
            diagnosticStore.recordFallbackLockResult(packageName, "无障碍锁层确认", "已显示");
            return;
        }
        if (!isTargetStillForeground(packageName, requestAt)) {
            return;
        }
        openFallbackActivity(packageName, "无障碍锁层未确认显示", requestAt);
    }

    private void openFallbackActivity(String packageName, String reason, long requestAt) {
        if (!isTargetStillForeground(packageName, requestAt)) {
            return;
        }
        try {
            LockActivity.open(this, packageName);
            diagnosticStore.recordFallbackLockResult(packageName, "Activity 兜底", reason + "，已启动");
        } catch (RuntimeException exception) {
            diagnosticStore.recordFallbackLockResult(
                    packageName,
                    "Activity 兜底失败",
                    reason + "，" + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            Log.e(TAG, "Failed to open fallback lock activity", exception);
        }
    }

    private void openGuardModeActivity(String packageName, String source, long requestAt) {
        if (!isTargetStillForeground(packageName, requestAt)) {
            return;
        }
        if (LockAccessibilityService.requestGuardModeBlock(packageName, source, packageName)) {
            diagnosticStore.recordFallbackLockResult(packageName, "守门模式无障碍兜底", "已请求");
            return;
        }
        if (GuardModeActivity.isShowing()) {
            diagnosticStore.recordFallbackLockResult(packageName, "守门页拉回", "守门页已经显示");
            return;
        }
        try {
            diagnosticStore.recordLockAttempt(packageName, source);
            GuardModeActivity.open(this, packageName, source);
            diagnosticStore.recordFallbackLockResult(packageName, "守门页拉回", "已启动");
        } catch (RuntimeException exception) {
            diagnosticStore.recordFallbackLockResult(
                    packageName,
                    "守门页拉回失败",
                    exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            Log.e(TAG, "Failed to open guard mode activity", exception);
        }
    }

    private boolean isTargetStillForeground(String packageName, long requestAt) {
        String foregroundPackage = LockForegroundVerifier.resolveCurrentForeground(this, diagnosticStore, requestAt);
        diagnosticStore.recordLockAttemptFinalForeground(packageName, foregroundPackage);
        if (packageName != null && packageName.equals(foregroundPackage)) {
            return true;
        }
        String reason = "目标已离开，取消兜底锁窗，当前前台：" + LockForegroundVerifier.describe(foregroundPackage);
        diagnosticStore.recordLockAttemptCancelled(packageName, foregroundPackage, reason);
        diagnosticStore.recordFallbackLockResult(packageName, "取消兜底", reason);
        return false;
    }

    private void recordGuardDecision(String packageName, boolean allowed) {
        if (diagnosticStore == null || store == null || !store.isGuardModeActive()) {
            return;
        }
        diagnosticStore.recordGuardDecision(
                packageName,
                store.getGuardModeLabel(),
                allowed,
                store.getGuardDecisionReason(packageName),
                store.getGuardModeRemainingMs()
        );
    }

    private String formatRemaining(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        if (hours > 0L) {
            return String.format(Locale.CHINA, "%d 小时 %d 分钟", hours, remainingMinutes);
        }
        return Math.max(1L, minutes) + " 分钟";
    }

    private void maybePullRemoteCommands(long now) {
        if (identityStore == null || commandPollInFlight || now - lastCommandPollAt < COMMAND_POLL_MS) {
            return;
        }
        lastCommandPollAt = now;
        commandPollInFlight = true;
        new Thread(() -> {
            try {
                ApprovalApi.CommandResult command = approvalApi.getNextCommand(
                        identityStore.getDeviceId(),
                        identityStore.getDeviceSecret()
                );
                if (!command.ok || !command.hasCommand || command.commandId == null || command.commandId.isEmpty()) {
                    diagnosticStore.recordCommandPoll("没有待执行命令");
                    return;
                }
                CommandApplyResult result = applyRemoteCommand(command);
                approvalApi.markCommandApplied(
                        command.commandId,
                        identityStore.getDeviceId(),
                        identityStore.getDeviceSecret(),
                        result.success,
                        result.message
                );
                diagnosticStore.recordCommandPoll(result.message);
            } catch (Exception e) {
                diagnosticStore.recordApiError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                commandPollInFlight = false;
            }
        }, "AppLockCommandPoll").start();
    }

    private CommandApplyResult applyRemoteCommand(ApprovalApi.CommandResult command) {
        if ("relock_app".equals(command.commandType)) {
            if (command.targetPackage != null && !command.targetPackage.trim().isEmpty()) {
                store.clearTemporaryPass(command.targetPackage);
            }
            store.setMonitorEnabled(true);
            start(this);
            return CommandApplyResult.success("已重新保护单个应用");
        }
        if ("relock_all".equals(command.commandType)) {
            store.clearAllTemporaryPasses();
            store.setMonitorEnabled(true);
            start(this);
            return CommandApplyResult.success("已重新保护所有已放行应用");
        }
        if ("enable_protection".equals(command.commandType)) {
            store.clearAllTemporaryPasses();
            store.setMonitorEnabled(true);
            boolean started = start(this);
            return new CommandApplyResult(started, started ? "已开启保护，图标未自动隐藏" : "已请求开启保护，但后台保护启动失败");
        }
        if ("panic_protect_all".equals(command.commandType)) {
            store.clearAllTemporaryPasses();
            store.setMonitorEnabled(true);
            boolean started = start(this);
            return new CommandApplyResult(started, started ? "已一键重新收紧，图标未自动隐藏" : "已请求重新收紧，但后台保护启动失败");
        }
        if ("enable_risk_shield".equals(command.commandType)) {
            store.setRiskShieldEnabled(true);
            return CommandApplyResult.success("已开启第二层加固：系统设置、应用管理和卸载入口会被守门应用锁挡住");
        }
        if ("disable_risk_shield".equals(command.commandType)) {
            store.setRiskShieldEnabled(false);
            return CommandApplyResult.success("已关闭第二层加固：系统设置和应用管理恢复普通状态");
        }
        if ("start_guard_mode_30".equals(command.commandType)) {
            return startGuardModeFromCommand(30);
        }
        if ("start_guard_mode_60".equals(command.commandType)) {
            return startGuardModeFromCommand(60);
        }
        if ("start_guard_mode_120".equals(command.commandType)) {
            return startGuardModeFromCommand(120);
        }
        if ("start_guard_mode_custom".equals(command.commandType)) {
            int minutes = parseGuardMinutes(command.payloadText);
            if (minutes <= 0) {
                return CommandApplyResult.failed("自定义守门时间无效，请输入 1 到 1440 分钟");
            }
            return startGuardModeFromCommand(minutes);
        }
        if ("stop_guard_mode".equals(command.commandType)) {
            store.stopGuardMode();
            StudentSyncManager.reportGuardStoppedAsync(this, "remote_stop_guard_mode");
            return CommandApplyResult.success("已结束守门模式，手机恢复普通保护规则");
        }
        if ("set_guard_whitelist".equals(command.commandType)) {
            return applyGuardWhitelistCommand(command.payloadText);
        }
        if ("set_daily_time_ranges".equals(command.commandType)) {
            return applyDailyTimeRangesCommand(command.payloadText);
        }
        if ("set_blocked_time_ranges".equals(command.commandType)) {
            return applyBlockedTimeRangesCommand(command.payloadText);
        }
        if ("app_block".equals(command.commandType) || "block_app".equals(command.commandType)) {
            return applyAppBlockCommand(command.targetPackage, true);
        }
        if ("app_unblock".equals(command.commandType) || "unblock_app".equals(command.commandType)) {
            return applyAppBlockCommand(command.targetPackage, false);
        }
        if ("disable_normal_protection".equals(command.commandType)) {
            store.stopGuardMode();
            store.setMonitorEnabled(false);
            store.clearAllTemporaryPasses();
            StudentSyncManager.reportGuardStoppedAsync(this, "remote_disable_normal_protection");
            serviceStateStore.recordMonitorEvent("普通保护已关闭，后台保持远程控制待命");
            updateNotification("远程控制待命，普通保护未开启。");
            return CommandApplyResult.success("已关闭普通保护。守门模式和白名单没有被清空；后台保持远程控制待命");
        }
        if ("clear_locked_apps".equals(command.commandType)) {
            int count = store.clearLockedPackages();
            store.setMonitorEnabled(false);
            store.clearAllTemporaryPasses();
            StudentSyncManager.reportGuardStoppedAsync(this, "remote_clear_locked_apps");
            serviceStateStore.recordMonitorEvent("限制名单已清空，后台保持远程控制待命");
            updateNotification("远程控制待命，普通保护未开启。");
            return CommandApplyResult.success("已清空限制名单 " + count + " 个，并关闭普通保护；守门白名单不受影响");
        }
        if ("hide_launcher".equals(command.commandType)) {
            boolean iconOk = LauncherVisibilityManager.setLauncherVisible(this, false);
            return new CommandApplyResult(iconOk, iconOk ? "已隐藏桌面图标" : "隐藏桌面图标命令已执行，但组件状态未切对");
        }
        if ("show_launcher".equals(command.commandType)) {
            boolean iconOk = LauncherVisibilityManager.setLauncherVisible(this, true);
            return new CommandApplyResult(iconOk, iconOk ? "已显示桌面图标" : "显示桌面图标命令已执行，但组件状态未切对");
        }
        return CommandApplyResult.failed("未知命令，未执行");
    }

    private CommandApplyResult startGuardModeFromCommand(int minutes) {
        CommandApplyResult requirement = validateGuardModeStartRequirements();
        if (!requirement.success) {
            store.stopGuardMode();
            store.setMonitorEnabled(false);
            StudentSyncManager.syncAllAsync(this, "remote_guard_start_permission_failed");
            serviceStateStore.recordMonitorEvent("远程守门启动失败：" + requirement.message);
            updateNotification("远程守门启动失败：" + requirement.message);
            return requirement;
        }
        store.clearAllTemporaryPasses();
        store.startGuardMode(AppLockStore.GUARD_MODE_WHITELIST, minutes);
        store.setMonitorEnabled(true);
        boolean started = start(this);
        if (started) {
            StudentSyncManager.reportGuardStartedAsync(this, minutes, AppLockStore.GUARD_MODE_WHITELIST, "remote_start_guard_mode");
        } else {
            store.stopGuardMode();
            store.setMonitorEnabled(false);
            StudentSyncManager.syncAllAsync(this, "remote_guard_start_failed");
        }
        String message = started
                ? "已开始守门模式 " + minutes + " 分钟，白名单外应用将被拦截"
                : "已设置守门模式 " + minutes + " 分钟，但后台保护启动失败";
        return new CommandApplyResult(started, message);
    }

    private CommandApplyResult validateGuardModeStartRequirements() {
        if (!PermissionUtils.hasUsageAccess(this)) {
            return CommandApplyResult.failed("使用情况访问权限未开启，远程守门模式未启动");
        }
        if (!PermissionUtils.isAccessibilityEnabled(this)) {
            return CommandApplyResult.failed("无障碍权限未开启，远程守门模式未启动");
        }
        return CommandApplyResult.success("守门模式启动条件已满足");
    }

    private int parseGuardMinutes(String payloadText) {
        if (payloadText == null) {
            return -1;
        }
        try {
            int minutes = Integer.parseInt(payloadText.trim());
            if (minutes < 1 || minutes > 24 * 60) {
                return -1;
            }
            return minutes;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private CommandApplyResult applyGuardWhitelistCommand(String payloadText) {
        Set<String> packageNames = new HashSet<>();
        if (payloadText != null && !payloadText.trim().isEmpty()) {
            String[] parts = payloadText.split("[,，\\n\\r\\t ]+");
            for (String raw : parts) {
                String packageName = raw == null ? "" : raw.trim();
                if (packageName.contains(".") && packageName.length() >= 3) {
                    packageNames.add(packageName);
                }
            }
        }
        int count = store.replaceGuardWhitelistPackages(packageNames);
        StudentSyncManager.reportWhitelistChangedAsync(this, "remote_set_guard_whitelist");
        if (count == 0) {
            return CommandApplyResult.success("已清空自定义守门白名单；电话、短信、守门应用锁和必要系统入口仍会自动保留");
        }
        return CommandApplyResult.success("已设置守门白名单 " + count + " 个；电话、短信、守门应用锁和必要系统入口仍会自动保留");
    }

    private CommandApplyResult applyDailyTimeRangesCommand(String payloadText) {
        try {
            JSONObject payload = new JSONObject(payloadText == null ? "{}" : payloadText);
            String date = payload.optString("date", "").trim();
            JSONArray ranges = payload.optJSONArray("timeRanges");
            if (date.isEmpty() || ranges == null || ranges.length() == 0) {
                store.clearDailyTimeRanges();
                StudentSyncManager.syncAllAsync(this, "remote_daily_time_ranges_cleared");
                return CommandApplyResult.success("已清空今日可用时间段");
            }
            JSONArray normalized = new JSONArray();
            for (int i = 0; i < ranges.length(); i++) {
                JSONObject range = ranges.optJSONObject(i);
                if (range == null) {
                    continue;
                }
                String start = normalizeClock(range.optString("startTime"));
                String end = normalizeClock(range.optString("endTime"));
                if (start == null || end == null) {
                    continue;
                }
                JSONObject next = new JSONObject();
                next.put("startTime", start);
                next.put("endTime", end);
                normalized.put(next);
            }
            if (normalized.length() == 0) {
                return CommandApplyResult.failed("今日可用时间段格式无效，未保存");
            }
            CommandApplyResult requirement = validateGuardModeStartRequirements();
            if (!requirement.success) {
                return CommandApplyResult.failed("今日可用时间段未保存：" + requirement.message);
            }
            store.setDailyTimeRanges(date, normalized.toString());
            store.setMonitorEnabled(true);
            start(this);
            maybeApplyDailyTimeRanges(System.currentTimeMillis());
            StudentSyncManager.syncAllAsync(this, "remote_daily_time_ranges_set");
            return CommandApplyResult.success("已保存今日可用时间段 " + normalized.length() + " 段");
        } catch (Exception exception) {
            return CommandApplyResult.failed("今日可用时间段解析失败：" + exception.getMessage());
        }
    }

    private CommandApplyResult applyBlockedTimeRangesCommand(String payloadText) {
        try {
            JSONObject payload = new JSONObject(payloadText == null ? "{}" : payloadText);
            String date = payload.optString("date", "").trim();
            JSONArray ranges = payload.optJSONArray("blockedTimeRanges");
            if (ranges == null) {
                ranges = payload.optJSONArray("timeRanges");
            }
            if (date.isEmpty() || ranges == null || ranges.length() == 0) {
                store.clearDailyTimeRanges();
                StudentSyncManager.syncAllAsync(this, "remote_blocked_time_ranges_cleared");
                return CommandApplyResult.success("已清空今日禁止时段");
            }
            JSONArray normalized = new JSONArray();
            for (int i = 0; i < ranges.length(); i++) {
                JSONObject range = ranges.optJSONObject(i);
                if (range == null) {
                    continue;
                }
                String start = normalizeClock(range.optString("startTime"));
                String end = normalizeClock(range.optString("endTime"));
                if (start == null || end == null) {
                    continue;
                }
                JSONObject next = new JSONObject();
                next.put("startTime", start);
                next.put("endTime", end);
                normalized.put(next);
            }
            if (normalized.length() == 0) {
                return CommandApplyResult.failed("今日禁止时段格式无效，未保存");
            }
            CommandApplyResult requirement = validateGuardModeStartRequirements();
            if (!requirement.success) {
                return CommandApplyResult.failed("今日禁止时段未保存：" + requirement.message);
            }
            store.setBlockedTimeRanges(date, normalized.toString());
            store.setMonitorEnabled(true);
            start(this);
            maybeApplyDailyTimeRanges(System.currentTimeMillis());
            StudentSyncManager.syncAllAsync(this, "remote_blocked_time_ranges_set");
            return CommandApplyResult.success("已保存今日禁止时段 " + normalized.length() + " 段");
        } catch (Exception exception) {
            return CommandApplyResult.failed("今日禁止时段解析失败：" + exception.getMessage());
        }
    }

    private void maybeApplyDailyTimeRanges(long now) {
        if (!store.hasDailyTimeRangesForToday(now)) {
            if (store.isAutoDailyGuardMode()) {
                store.stopGuardMode();
                StudentSyncManager.reportGuardStoppedAsync(this, "daily_time_ranges_expired");
            }
            return;
        }
        if (store.isDailyTimeRangesBlockedMode()) {
            boolean insideBlockedTime = store.isWithinDailyBlockedRanges(now);
            if (!insideBlockedTime) {
                if (store.isAutoDailyGuardMode()) {
                    store.stopGuardMode();
                    StudentSyncManager.reportGuardStoppedAsync(this, "blocked_time_ranges_unlocked_now");
                    serviceStateStore.recordMonitorEvent("当前不在今日禁止时段内，自动守门已结束");
                    updateNotification("当前不在禁止时段内，守门未开启");
                }
                return;
            }
            if (store.isGuardModeActive()) {
                return;
            }
            if (!PermissionUtils.hasUsageAccess(this) || !PermissionUtils.isAccessibilityEnabled(this)) {
                serviceStateStore.recordMonitorEvent("当前处于今日禁止时段，但关键权限未开启");
                return;
            }
            int minutes = store.minutesUntilCurrentDailyBlockedRangeEnds(now);
            if (minutes <= 0) {
                minutes = 1;
            }
            store.clearAllTemporaryPasses();
            store.startDailyTimeRangeGuardMode(minutes);
            store.setMonitorEnabled(true);
            boolean started = start(this);
            if (started) {
                StudentSyncManager.reportGuardStartedAsync(this, minutes, AppLockStore.GUARD_MODE_WHITELIST, "blocked_time_ranges_auto_guard");
                serviceStateStore.recordMonitorEvent("当前处于今日禁止时段，已自动进入守门 " + minutes + " 分钟");
                updateNotification("当前处于禁止时段内，守门模式运行中");
            } else {
                store.stopGuardMode();
                StudentSyncManager.syncAllAsync(this, "blocked_time_ranges_auto_guard_failed");
                serviceStateStore.recordMonitorEvent("今日禁止时段自动守门启动失败");
            }
            return;
        }
        boolean insideAllowedTime = store.isWithinDailyTimeRanges(now);
        if (insideAllowedTime) {
            if (store.isAutoDailyGuardMode()) {
                store.stopGuardMode();
                StudentSyncManager.reportGuardStoppedAsync(this, "daily_time_ranges_allowed_now");
                serviceStateStore.recordMonitorEvent("今日可用时间段已开始，自动守门已结束");
                updateNotification("当前处于可用时间段，守门未开启");
            }
            return;
        }
        if (store.isGuardModeActive()) {
            return;
        }
        if (!PermissionUtils.hasUsageAccess(this) || !PermissionUtils.isAccessibilityEnabled(this)) {
            serviceStateStore.recordMonitorEvent("今日可用时间段要求锁定，但关键权限未开启");
            return;
        }
        int minutes = store.minutesUntilNextDailyAllowedRange(now);
        store.clearAllTemporaryPasses();
        store.startDailyTimeRangeGuardMode(minutes);
        store.setMonitorEnabled(true);
        boolean started = start(this);
        if (started) {
            StudentSyncManager.reportGuardStartedAsync(this, minutes, AppLockStore.GUARD_MODE_WHITELIST, "daily_time_ranges_auto_guard");
            serviceStateStore.recordMonitorEvent("不在今日可用时间段内，已自动进入守门 " + minutes + " 分钟");
            updateNotification("不在可用时间段内，守门模式运行中");
        } else {
            store.stopGuardMode();
            StudentSyncManager.syncAllAsync(this, "daily_time_ranges_auto_guard_failed");
            serviceStateStore.recordMonitorEvent("今日可用时间段自动守门启动失败");
        }
    }

    private static String normalizeClock(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int hour = Math.max(0, Math.min(Integer.parseInt(parts[0]), 23));
            int minute = Math.max(0, Math.min(Integer.parseInt(parts[1]), 59));
            return String.format(Locale.CHINA, "%02d:%02d", hour, minute);
        } catch (Exception ignored) {
            return null;
        }
    }

    private CommandApplyResult applyAppBlockCommand(String packageName, boolean block) {
        String target = packageName == null ? "" : packageName.trim();
        if (target.isEmpty() || !target.contains(".")) {
            return CommandApplyResult.failed("缺少要控制的具体应用，单 App 封锁未执行");
        }
        if (getPackageName().equals(target)) {
            return CommandApplyResult.failed("不能限制守门应用锁本身");
        }
        if (!PermissionUtils.hasUsageAccess(this)) {
            return CommandApplyResult.failed("使用情况访问权限未开启，单 App 封锁未启动");
        }
        if (!PermissionUtils.isAccessibilityEnabled(this)) {
            return CommandApplyResult.failed("无障碍权限未开启，单 App 封锁未启动");
        }
        if (block) {
            store.setLocked(target, true);
            store.clearTemporaryPass(target);
            store.setMonitorEnabled(true);
            boolean started = start(this);
            StudentSyncManager.syncAllAsync(this, "remote_app_block");
            if (!started) {
                return CommandApplyResult.failed("已写入单 App 限制，但后台保护服务启动失败");
            }
            updateNotification("已远程限制：" + AppCatalog.getLabel(this, target));
            return CommandApplyResult.success("已限制 " + AppCatalog.getLabel(this, target));
        }
        store.setLocked(target, false);
        store.clearTemporaryPass(target);
        StudentSyncManager.syncAllAsync(this, "remote_app_unblock");
        updateNotification("已解除单 App 限制：" + AppCatalog.getLabel(this, target));
        return CommandApplyResult.success("已解除 " + AppCatalog.getLabel(this, target) + " 的限制");
    }

    private static final class CommandApplyResult {
        final boolean success;
        final String message;

        CommandApplyResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        static CommandApplyResult success(String message) {
            return new CommandApplyResult(true, message);
        }

        static CommandApplyResult failed(String message) {
            return new CommandApplyResult(false, message);
        }
    }

    private void recordLocalUsage(String packageName, long now) {
        if (trackedPackage != null && trackedAt > 0L && now > trackedAt && store.isProtectedPackage(trackedPackage)) {
            usageStore.addUsage(trackedPackage, now - trackedAt);
        }
        trackedPackage = packageName;
        trackedAt = now;
    }

    private void scheduleRestartIfNeeded() {
        if (store == null) {
            recordRestartAttempt("跳过自恢复：状态存储还没初始化");
            return;
        }
        if (!store.isMonitorEnabled()) {
            recordRestartAttempt("跳过自恢复：保护开关未开启");
            return;
        }
        if (!PermissionUtils.hasUsageAccess(this)) {
            recordRestartAttempt("跳过自恢复：使用情况访问未开启");
            return;
        }
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            recordRestartAttempt("调度自恢复失败：AlarmManager 不可用");
            return;
        }
        Intent restartIntent = new Intent(this, AppMonitorService.class);
        PendingIntent pendingIntent = PendingIntent.getForegroundService(
                this,
                2001,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        long triggerAt = SystemClock.elapsedRealtime() + 1200L;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                recordRestartAttempt("已调度监控服务自恢复：精确闹钟");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                recordRestartAttempt("已调度监控服务自恢复：降级闹钟");
                return;
            }
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            recordRestartAttempt("已调度监控服务自恢复：普通闹钟");
        } catch (SecurityException exception) {
            recordRestartAttempt("调度监控服务自恢复失败：缺少闹钟权限，" + exception.getMessage());
            Log.e(TAG, "Failed to schedule monitor restart", exception);
        } catch (RuntimeException exception) {
            recordRestartAttempt("调度监控服务自恢复失败：" + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            Log.e(TAG, "Failed to schedule monitor restart", exception);
        }
    }

    private void maybeScheduleWatchdog(long now) {
        if (now - lastWatchdogScheduleAt < WATCHDOG_SCHEDULE_MS) {
            return;
        }
        lastWatchdogScheduleAt = now;
        scheduleRestartIfNeeded();
    }

    private void recordMonitorEvent(String event) {
        if (serviceStateStore != null) {
            serviceStateStore.recordMonitorEvent(event);
        }
    }

    private void recordRestartAttempt(String event) {
        if (serviceStateStore != null) {
            serviceStateStore.recordRestartAttempt(event);
        }
    }

    private static String formatThrowable(String prefix, Throwable throwable) {
        if (throwable == null) {
            return prefix;
        }
        return prefix + "：" + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
    }
}
