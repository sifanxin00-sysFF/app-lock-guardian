package com.codex.applockguard.ui;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.DiagnosticStore;
import com.codex.applockguard.data.ServiceStateStore;
import com.codex.applockguard.receiver.AppDeviceAdminReceiver;
import com.codex.applockguard.service.AppMonitorService;
import com.codex.applockguard.sync.StudentSyncManager;
import com.codex.applockguard.util.LauncherVisibilityManager;
import com.codex.applockguard.util.PermissionSetupAccess;
import com.codex.applockguard.util.PermissionUtils;
import com.codex.applockguard.util.ProtectionStatusResolver;
import com.codex.applockguard.util.ProtectionStatusSnapshot;
import com.codex.applockguard.util.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends android.app.Activity {
    private static final String ACTION_DEVICE_ADMIN_SETTINGS = "android.settings.DEVICE_ADMIN_SETTINGS";
    private static final long HEARTBEAT_FREEZE_WARNING_MS = 5_000L;

    private AppLockStore lockStore;
    private ServiceStateStore serviceStateStore;
    private String transientStatusText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lockStore = new AppLockStore(this);
        serviceStateStore = new ServiceStateStore(this);
        LauncherVisibilityManager.repairMainActivity(this);
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (PermissionUtils.hasUsageAccess(this)) {
            AppMonitorService.start(this);
        } else {
            AppMonitorService.stop(this);
        }
        if (lockStore.isGuardModeActive()) {
            StudentSyncManager.syncAllAsync(this, "main_resume_guard_active");
            GuardModeActivity.open(this, null, "main_activity_guard_redirect");
            finish();
            return;
        }
        StudentSyncManager.reportPermissionsAsync(this, "main_resume");
        render();
    }

    private void render() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = Ui.root(this);
        root.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scrollView.addView(root);

        boolean hasUsage = PermissionUtils.hasUsageAccess(this);
        boolean hasAccessibility = PermissionUtils.isAccessibilityEnabled(this);
        boolean accessibilityAlive = PermissionUtils.isAccessibilityActive(this);
        boolean hasAdmin = PermissionUtils.isDeviceAdminActive(this);
        boolean monitorEnabled = lockStore.isMonitorEnabled();
        boolean monitorAlive = serviceStateStore.isMonitorAlive();
        boolean launcherVisible = LauncherVisibilityManager.isLauncherVisible(this);
        ProtectionStatusSnapshot status = ProtectionStatusResolver.resolve(this);
        String runtimeStatus = runtimeStatusLabel(monitorEnabled);
        String heartbeatLag = heartbeatLagSummary();
        String lastFailure = latestBackgroundFailure();

        root.addView(Ui.title(this, "守门应用锁"));
        root.addView(Ui.body(this, "权限齐全只是准备好。点击开启保护或启动守门模式后，后台保护才会开始拦截。"));
        root.addView(Ui.body(this,
                "当前状态：" + status.title + "\n"
                        + status.detail + "\n\n"
                        + line("使用情况权限", hasUsage)
                        + line("无障碍监测", hasAccessibility)
                        + line("无障碍心跳", accessibilityAlive)
                        + line("设备管理员", hasAdmin)
                        + line("保护开关", monitorEnabled)
                        + "后台保护：" + runtimeStatus + "\n"
                        + "心跳停滞：" + heartbeatLag + "\n"
                        + "守门模式：" + lockStore.getGuardModeLabel() + "\n"
                        + "守门剩余：" + formatRemaining(lockStore.getGuardModeRemainingMs()) + "\n"
                        + "守门白名单：" + lockStore.getGuardWhitelistPackages().size() + " 个\n"
                        + line("锁系统设置和卸载入口", lockStore.isRiskShieldEnabled())
                        + line("桌面图标", launcherVisible)
                        + "限制应用：" + lockStore.getLockedPackages().size() + " 个"
        ));
        root.addView(Ui.body(this, "守门模式类似番茄 ToDo 锁机：开启后只允许白名单应用。只想限制某些 App 时，可以在“限制应用 / 白名单”里配置限制名单，再用锁应用模式启动。"));
        root.addView(Ui.body(this, "第二层加固会锁住系统设置、应用管理、权限管理、小米安全中心和卸载入口。默认关闭，只在你明确开启后才生效。"));

        if (monitorEnabled && !monitorAlive && !lastFailure.isEmpty()) {
            root.addView(Ui.body(this, "后台保护最近状态：" + lastFailure));
        }
        if (monitorEnabled && isFreezeRisk()) {
            root.addView(Ui.body(this, "冻结风险：" + runtimeStatus + "。如果锁窗不稳定，请到“权限与红米设置引导”里开启自启动、电池无限制，并在最近任务里锁定守门应用锁。"));
        }
        if (transientStatusText != null && !transientStatusText.trim().isEmpty()) {
            root.addView(Ui.body(this, transientStatusText));
        }

        if (!hasUsage) {
            Button usageFix = Ui.button(this, "1. 去开启使用情况访问");
            usageFix.setOnClickListener(v -> {
                PermissionSetupAccess.grant(this);
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            });
            root.addView(usageFix);
        }

        if (!hasAccessibility) {
            Button accessibilityFix = Ui.button(this, "2. 去开启无障碍监测");
            accessibilityFix.setOnClickListener(v -> {
                PermissionSetupAccess.grant(this);
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            });
            root.addView(accessibilityFix);
        }

        if (!hasAdmin) {
            Button adminFix = Ui.button(this, "3. 启用设备管理员增加卸载阻力");
            adminFix.setOnClickListener(v -> requestDeviceAdmin());
            root.addView(adminFix);
        }

        Button webButton = Ui.button(this, "监管网页设置与激活链接");
        webButton.setOnClickListener(v -> startActivity(new Intent(this, GuardianWebSetupActivity.class)));
        root.addView(webButton);

        Button permissionButton = Ui.button(this, "权限与红米设置引导");
        permissionButton.setOnClickListener(v -> {
            PermissionSetupAccess.grant(this);
            startActivity(new Intent(this, PermissionActivity.class));
        });
        root.addView(permissionButton);

        Button appsButton = Ui.button(this, "限制应用 / 白名单 / 时间规则");
        appsButton.setOnClickListener(v -> {
            if (!monitorEnabled) {
                startActivity(new Intent(this, AppListActivity.class));
            } else {
                AdminGateActivity.open(this, AdminGateActivity.DEST_APPS);
            }
        });
        root.addView(appsButton);

        Button usageButton = Ui.button(this, "查看限制应用使用时长");
        usageButton.setOnClickListener(v -> startActivity(new Intent(this, UsageStatsActivity.class)));
        root.addView(usageButton);

        Button diagnosticsButton = Ui.button(this, "诊断与保护自检");
        diagnosticsButton.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        root.addView(diagnosticsButton);

        Button copyDiagnosticsButton = Ui.button(this, "一键复制诊断信息");
        copyDiagnosticsButton.setOnClickListener(v -> copyDiagnostics());
        root.addView(copyDiagnosticsButton);

        Button strongModeButton = Ui.button(this, lockStore.isRiskShieldEnabled() ? "关闭第二层加固" : "开启第二层加固");
        strongModeButton.setOnClickListener(v -> toggleStrongMode());
        root.addView(strongModeButton);

        if (monitorEnabled && !monitorAlive) {
            Button restartProtectionButton = Ui.button(this, "重新启动后台保护");
            restartProtectionButton.setOnClickListener(v -> restartBackgroundProtection());
            root.addView(restartProtectionButton);
        }

        if (!lockStore.isGuardModeActive() && (monitorEnabled || !lockStore.getLockedPackages().isEmpty())) {
            root.addView(Ui.body(this, "如果没开守门锁机，打开微信等 App 也弹锁窗，通常是旧的普通限制还在运行。这里可以单独关闭普通保护，或者清空旧的限制名单；不会影响守门白名单。"));

            Button disableNormalProtectionButton = Ui.button(this, "关闭普通保护");
            disableNormalProtectionButton.setOnClickListener(v -> disableNormalProtection());
            root.addView(disableNormalProtectionButton);

            Button clearLockedAppsButton = Ui.button(this, "清空限制名单");
            clearLockedAppsButton.setOnClickListener(v -> clearLockedApps());
            root.addView(clearLockedAppsButton);
        }

        root.addView(guardButton("守门锁机 30 分钟", AppLockStore.GUARD_MODE_WHITELIST, 30));
        root.addView(guardButton("守门锁机 1 小时", AppLockStore.GUARD_MODE_WHITELIST, 60));
        root.addView(guardButton("守门锁机 2 小时", AppLockStore.GUARD_MODE_WHITELIST, 120));
        root.addView(guardButton("锁应用模式 30 分钟", AppLockStore.GUARD_MODE_BLOCKLIST, 30));

        EditText customMinutesInput = new EditText(this);
        customMinutesInput.setHint("自定义分钟数，1 到 1440");
        customMinutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(customMinutesInput);

        Button customGuardButton = Ui.button(this, "按自定义时间启动守门锁机");
        customGuardButton.setOnClickListener(v -> startCustomGuardMode(customMinutesInput, AppLockStore.GUARD_MODE_WHITELIST));
        root.addView(customGuardButton);

        Button customAppLockButton = Ui.button(this, "按自定义时间启动锁应用模式");
        customAppLockButton.setOnClickListener(v -> startCustomGuardMode(customMinutesInput, AppLockStore.GUARD_MODE_BLOCKLIST));
        root.addView(customAppLockButton);

        Button adminButton = Ui.button(this, hasAdmin ? "打开设备管理员页面" : "启用设备管理员增加卸载阻力");
        adminButton.setOnClickListener(v -> requestDeviceAdmin());
        root.addView(adminButton);

        Button monitorButton = Ui.button(this, monitorEnabled ? "申请暂停保护" : "开启普通保护");
        monitorButton.setOnClickListener(v -> {
            if (lockStore.isMonitorEnabled()) {
                AdminGateActivity.open(this, AdminGateActivity.DEST_PAUSE);
                return;
            }
            startNormalProtection();
        });
        root.addView(monitorButton);

        setContentView(scrollView);
    }

    private Button guardButton(String text, String mode, int minutes) {
        Button button = Ui.button(this, text);
        button.setOnClickListener(v -> startGuardMode(mode, minutes));
        return button;
    }

    private void startCustomGuardMode(EditText input, String mode) {
        int minutes = parseCustomMinutes(input);
        if (minutes <= 0) {
            Toast.makeText(this, "请输入 1 到 1440 之间的分钟数。", Toast.LENGTH_LONG).show();
            return;
        }
        startGuardMode(mode, minutes);
    }

    private int parseCustomMinutes(EditText input) {
        if (input == null || input.getText() == null) {
            return -1;
        }
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return -1;
        }
        try {
            int minutes = Integer.parseInt(text);
            if (minutes < 1 || minutes > 24 * 60) {
                return -1;
            }
            return minutes;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private void disableNormalProtection() {
        lockStore.stopGuardMode();
        lockStore.setMonitorEnabled(false);
        lockStore.clearAllTemporaryPasses();
        AppMonitorService.start(this);
        StudentSyncManager.reportGuardStoppedAsync(this, "disable_normal_protection");
        transientStatusText = "已关闭普通保护。守门白名单没有被清空；后台会保持远程控制待命，不会拦截普通 App。";
        render();
    }

    private void clearLockedApps() {
        int count = lockStore.clearLockedPackages();
        lockStore.setMonitorEnabled(false);
        lockStore.clearAllTemporaryPasses();
        AppMonitorService.start(this);
        StudentSyncManager.reportGuardStoppedAsync(this, "clear_locked_apps");
        transientStatusText = "已清空限制名单 " + count + " 个，并关闭普通保护。守门白名单不受影响；后台会保持远程控制待命。";
        render();
    }

    private void toggleStrongMode() {
        boolean next = !lockStore.isRiskShieldEnabled();
        lockStore.setRiskShieldEnabled(next);
        transientStatusText = next
                ? "已开启第二层加固。守门模式运行时，系统设置、应用管理和卸载入口会被挡住。"
                : "已关闭第二层加固。系统设置和应用管理恢复普通状态。";
        render();
    }

    private void startGuardMode(String mode, int minutes) {
        if (!PermissionUtils.hasUsageAccess(this)) {
            Toast.makeText(this, "先把使用情况访问开好。", Toast.LENGTH_LONG).show();
            return;
        }
        if (!PermissionUtils.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "先把无障碍监测开好。", Toast.LENGTH_LONG).show();
            return;
        }
        lockStore.clearAllTemporaryPasses();
        lockStore.startGuardMode(mode, minutes);
        lockStore.setMonitorEnabled(true);
        boolean started = AppMonitorService.start(this);
        if (!started) {
            lockStore.stopGuardMode();
            lockStore.setMonitorEnabled(false);
            StudentSyncManager.syncAllAsync(this, "local_guard_start_failed");
            transientStatusText = "守门模式启动失败，本机没有显示成功，也不会同步云端为 active。";
            Toast.makeText(this, "守门模式启动失败，没有同步为成功。", Toast.LENGTH_LONG).show();
            render();
            return;
        }
        StudentSyncManager.reportGuardStartedAsync(this, minutes, mode, "local_start_guard_mode");
        transientStatusText = started
                ? "正在启动守门模式：" + lockStore.getGuardModeLabel() + "，" + minutes + " 分钟。"
                : "守门模式已设置，但后台保护启动失败，请打开诊断页查看原因。";
        GuardModeActivity.open(this, null, "local_start_guard_mode");
        finish();
    }

    private void startNormalProtection() {
        if (!PermissionUtils.hasUsageAccess(this)) {
            Toast.makeText(this, "先把使用情况访问开好。", Toast.LENGTH_LONG).show();
            return;
        }
        if (!PermissionUtils.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "先把无障碍监测开好。", Toast.LENGTH_LONG).show();
            return;
        }
        if (lockStore.getLockedPackages().isEmpty() && !lockStore.isRiskShieldEnabled()) {
            Toast.makeText(this, "先选择要限制的应用。系统设置锁定需要监管网页单独开启。", Toast.LENGTH_LONG).show();
            return;
        }

        lockStore.clearAllTemporaryPasses();
        lockStore.setMonitorEnabled(true);
        boolean startRequested = AppMonitorService.start(this);
        if (startRequested) {
            StudentSyncManager.syncAllAsync(this, "local_start_normal_protection");
        }
        transientStatusText = startRequested ? "正在启动后台保护..." : "后台保护启动失败，请打开诊断页查看原因。";
        render();
        if (!startRequested) {
            return;
        }

        getWindow().getDecorView().postDelayed(() -> {
            if (serviceStateStore.isMonitorAlive()) {
                transientStatusText = "保护已开启，后台保护运行中。桌面图标不会自动隐藏，需要监管网页单独控制。";
                Toast.makeText(this, "保护已开启，后台保护运行中。", Toast.LENGTH_LONG).show();
            } else {
                transientStatusText = "后台保护启动失败，桌面图标已保留。最近状态：" + latestBackgroundFailure();
                Toast.makeText(this, "后台保护启动失败，桌面图标已保留。", Toast.LENGTH_LONG).show();
            }
            render();
        }, 3500L);
    }

    private static String line(String label, boolean ok) {
        return label + "：" + (ok ? "已开启" : "未开启") + "\n";
    }

    private String runtimeStatusLabel(boolean monitorEnabled) {
        if (!monitorEnabled) {
            return "保护未开启";
        }
        boolean monitorStale = isHeartbeatStale(serviceStateStore.getMonitorHeartbeatAt());
        boolean accessibilityStale = isHeartbeatStale(serviceStateStore.getAccessibilityHeartbeatAt());
        if (monitorStale && accessibilityStale) {
            return "后台保护疑似被系统冻结";
        }
        if (monitorStale) {
            return "后台保护疑似被系统冻结";
        }
        if (accessibilityStale) {
            return "无障碍疑似停止响应";
        }
        return "后台保护正常";
    }

    private boolean isFreezeRisk() {
        return isHeartbeatStale(serviceStateStore.getMonitorHeartbeatAt())
                || isHeartbeatStale(serviceStateStore.getAccessibilityHeartbeatAt());
    }

    private String heartbeatLagSummary() {
        return "后台 " + heartbeatLagText(serviceStateStore.getMonitorHeartbeatAt())
                + " / 无障碍 " + heartbeatLagText(serviceStateStore.getAccessibilityHeartbeatAt());
    }

    private static boolean isHeartbeatStale(long timestamp) {
        return timestamp <= 0L || System.currentTimeMillis() - timestamp > HEARTBEAT_FREEZE_WARNING_MS;
    }

    private static String heartbeatLagText(long timestamp) {
        if (timestamp <= 0L) {
            return "暂无心跳";
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        return seconds + " 秒";
    }

    private void restartBackgroundProtection() {
        if (!lockStore.isMonitorEnabled()) {
            transientStatusText = "保护开关未开启，请先点击“开启普通保护”或启动守门模式。";
            render();
            return;
        }
        boolean startRequested = AppMonitorService.start(this);
        if (startRequested) {
            StudentSyncManager.syncAllAsync(this, "restart_background_protection");
        }
        transientStatusText = startRequested ? "正在重新启动后台保护..." : "后台保护启动失败，请打开诊断页查看原因。";
        render();
        if (startRequested) {
            getWindow().getDecorView().postDelayed(() -> {
                transientStatusText = serviceStateStore.isMonitorAlive()
                        ? "后台保护已经恢复运行。"
                        : "后台保护仍未运行。最近状态：" + latestBackgroundFailure();
                render();
            }, 3500L);
        }
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("守门应用锁诊断", buildDiagnosticsText()));
            Toast.makeText(this, "诊断信息已复制。", Toast.LENGTH_SHORT).show();
        }
    }

    private String buildDiagnosticsText() {
        DiagnosticStore diagnosticStore = new DiagnosticStore(this);
        ProtectionStatusSnapshot status = ProtectionStatusResolver.resolve(this);
        StringBuilder out = new StringBuilder();
        out.append("守门应用锁诊断\n");
        out.append("当前状态：").append(status.title).append('\n');
        out.append("状态说明：").append(status.detail).append('\n');
        out.append("保护开关：").append(lockStore.isMonitorEnabled() ? "开启" : "关闭").append('\n');
        out.append("普通设备管理员：").append(PermissionUtils.isDeviceAdminActive(this) ? "已开启" : "未开启").append('\n');
        out.append("后台保护：").append(runtimeStatusLabel(lockStore.isMonitorEnabled())).append('\n');
        out.append("心跳停滞：").append(heartbeatLagSummary()).append('\n');
        out.append("守门模式：").append(lockStore.getGuardModeLabel()).append('\n');
        out.append("守门类型：").append(lockStore.getGuardModeType()).append('\n');
        out.append("守门剩余：").append(formatRemaining(lockStore.getGuardModeRemainingMs())).append('\n');
        out.append("守门结束：").append(formatTime(lockStore.getGuardModeUntil())).append('\n');
        out.append("守门白名单数量：").append(lockStore.getGuardWhitelistPackages().size()).append('\n');
        out.append("最近守门判断包名：").append(empty(diagnosticStore.getLastGuardDecisionPackage())).append('\n');
        out.append("最近守门判断模式：").append(empty(diagnosticStore.getLastGuardDecisionMode())).append('\n');
        out.append("最近守门判断结果：").append(empty(diagnosticStore.getLastGuardDecisionResult())).append('\n');
        out.append("最近守门判断原因：").append(empty(diagnosticStore.getLastGuardDecisionReason())).append('\n');
        out.append("最近守门判断剩余：").append(formatRemaining(diagnosticStore.getLastGuardDecisionRemainingMs())).append('\n');
        out.append("最近守门判断时间：").append(formatTime(diagnosticStore.getLastGuardDecisionAt())).append('\n');
        out.append("后台保护心跳：").append(formatTime(serviceStateStore.getMonitorHeartbeatAt())).append('\n');
        out.append("最近后台事件：").append(empty(serviceStateStore.getLastMonitorEvent())).append('\n');
        out.append("最近后台循环异常：").append(empty(serviceStateStore.getLastMonitorLoopError())).append('\n');
        out.append("最近启动记录：").append(empty(serviceStateStore.getLastRestartAttempt())).append('\n');
        out.append("WakeLock 状态：").append(serviceStateStore.getLastWakeLockHeld() ? "持有中" : "未持有").append('\n');
        out.append("WakeLock 最近事件：").append(empty(serviceStateStore.getLastWakeLockEvent())).append('\n');
        out.append("无障碍授权：").append(PermissionUtils.isAccessibilityEnabled(this) ? "已开启" : "未开启").append('\n');
        out.append("无障碍心跳：").append(serviceStateStore.isAccessibilityAlive() ? "在线" : "离线").append('\n');
        out.append("最近无障碍事件：").append(empty(serviceStateStore.getLastAccessibilityEvent())).append('\n');
        out.append("最近无障碍循环异常：").append(empty(serviceStateStore.getLastAccessibilityLoopError())).append('\n');
        out.append("限制应用数：").append(lockStore.getLockedPackages().size()).append('\n');
        out.append("锁系统设置和卸载入口：").append(lockStore.isRiskShieldEnabled() ? "开启" : "关闭").append('\n');
        out.append("最近前台识别：").append(empty(diagnosticStore.getLastForegroundPackage())).append('\n');
        out.append("最近后台拦截候选：").append(empty(diagnosticStore.getLastMonitorBlockCandidatePackage())).append('\n');
        out.append("最近锁窗尝试目标：").append(empty(diagnosticStore.getLastLockAttemptTarget())).append('\n');
        out.append("最近锁窗尝试来源：").append(empty(diagnosticStore.getLastLockAttemptSource())).append('\n');
        out.append("最近最终前台校验：").append(empty(diagnosticStore.getLastLockAttemptFinalForeground())).append('\n');
        out.append("最近锁窗取消原因：").append(empty(diagnosticStore.getLastLockAttemptCancelReason())).append('\n');
        out.append("最近锁层目标：").append(empty(diagnosticStore.getLastLockTargetPackage())).append('\n');
        out.append("最近锁层来源：").append(empty(diagnosticStore.getLastLockSource())).append('\n');
        out.append("最近锁层显示：").append(formatTime(diagnosticStore.getLastLockShowAt())).append('\n');
        out.append("最近锁层隐藏原因：").append(empty(diagnosticStore.getLastLockHideReason())).append('\n');
        out.append("最近未显示锁层原因：").append(empty(diagnosticStore.getLastLockSkipReason())).append('\n');
        out.append("最近兜底锁层包名：").append(empty(diagnosticStore.getLastFallbackLockPackage())).append('\n');
        out.append("最近兜底锁层动作：").append(empty(diagnosticStore.getLastFallbackLockAction())).append('\n');
        out.append("最近兜底锁层结果：").append(empty(diagnosticStore.getLastFallbackLockResult())).append('\n');
        out.append("最近远程命令：").append(empty(diagnosticStore.getLastCommandResult())).append('\n');
        return out.toString();
    }

    private String latestBackgroundFailure() {
        String loopError = serviceStateStore.getLastMonitorLoopError();
        String restart = serviceStateStore.getLastRestartAttempt();
        String monitor = serviceStateStore.getLastMonitorEvent();
        if (loopError != null && !loopError.trim().isEmpty()) {
            return loopError;
        }
        if (restart != null && !restart.trim().isEmpty()) {
            return restart;
        }
        if (monitor != null && !monitor.trim().isEmpty()) {
            return monitor;
        }
        return "暂无记录，请点“重新启动后台保护”后再看。";
    }

    private static String empty(String value) {
        return value == null || value.trim().isEmpty() ? "暂无" : value;
    }

    private static String formatTime(long timestamp) {
        if (timestamp <= 0L) {
            return "暂无";
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(timestamp));
    }

    private static String formatRemaining(long ms) {
        if (ms <= 0L) {
            return "未开启";
        }
        long totalMinutes = Math.max(1L, (ms + 59_999L) / 60_000L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0L) {
            return hours + "小时" + (minutes > 0L ? minutes + "分钟" : "");
        }
        return minutes + "分钟";
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 33);
        }
    }

    private void requestDeviceAdmin() {
        PermissionSetupAccess.grant(this);
        if (PermissionUtils.isDeviceAdminActive(this)) {
            Toast.makeText(this, "设备管理员已开启。它只能增加卸载步骤，做不到系统级绝对禁止。", Toast.LENGTH_LONG).show();
            startActivity(new Intent(ACTION_DEVICE_ADMIN_SETTINGS));
            return;
        }

        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                new ComponentName(this, AppDeviceAdminReceiver.class)
        );
        intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "开启后，卸载前需要先在系统里关闭设备管理员。它能增加卸载阻力，但做不到企业设备所有者那种彻底禁止。"
        );
        startActivity(intent);
    }
}
