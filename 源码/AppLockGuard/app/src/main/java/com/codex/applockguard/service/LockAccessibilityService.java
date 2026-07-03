package com.codex.applockguard.service;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.codex.applockguard.R;
import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.DiagnosticStore;
import com.codex.applockguard.data.ServiceStateStore;
import com.codex.applockguard.ui.AdminGateActivity;
import com.codex.applockguard.ui.GuardModeActivity;
import com.codex.applockguard.util.AppCatalog;
import com.codex.applockguard.util.ForegroundAppReader;
import com.codex.applockguard.util.PermissionUtils;
import com.codex.applockguard.util.SystemUsageReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LockAccessibilityService extends AccessibilityService {
    private static final String TAG = "LockAccessibilityService";
    private static final long PROMPT_COOLDOWN_MS = 2_500L;
    private static final long HEARTBEAT_MS = 4_000L;
    private static final long WINDOW_CHECK_MS = 250L;
    private static final long JUST_SHOWN_GRACE_MS = 2_200L;
    private static final long GUARD_EXIT_CONFIRM_MS = 700L;
    private static final long RECENT_ALLOWED_GRACE_MS = 2_500L;
    private static volatile LockAccessibilityService currentInstance;

    private static final String[] SELF_MARKERS = new String[]{
            "com.codex.applockguard",
            "守门应用锁",
            "AppLockGuard"
    };

    private static final String[] UNINSTALL_KEYWORDS = new String[]{
            "卸载",
            "删除应用",
            "移除应用",
            "应用信息",
            "应用详情",
            "强行停止",
            "强制停止",
            "停止运行",
            "清除数据",
            "清除全部数据",
            "清除存储",
            "清除缓存",
            "停用",
            "关闭设备管理员",
            "取消激活",
            "小爱同学",
            "删除"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            try {
                if (serviceStateStore != null) {
                    serviceStateStore.touchAccessibilityHeartbeat();
                }
                checkActiveWindowPackage("heartbeat");
            } catch (Throwable throwable) {
                recordAccessibilityLoopError("无障碍心跳循环异常", throwable);
            } finally {
                if (!destroyed) {
                    handler.postDelayed(this, HEARTBEAT_MS);
                }
            }
        }
    };
    private final Runnable windowChecker = new Runnable() {
        @Override
        public void run() {
            try {
                checkActiveWindowPackage("window_poll");
            } catch (Throwable throwable) {
                recordAccessibilityLoopError("无障碍窗口轮询异常", throwable);
            } finally {
                if (!destroyed) {
                    handler.postDelayed(this, WINDOW_CHECK_MS);
                }
            }
        }
    };

    private AppLockStore store;
    private ServiceStateStore serviceStateStore;
    private DiagnosticStore diagnosticStore;
    private LockOverlayController overlayController;
    private String lastPromptKey;
    private long lastPromptAt;
    private String lastGuardExitConfirmPackage;
    private long lastGuardExitConfirmAt;
    private String lastGuardAllowedPackage;
    private long lastGuardAllowedAt;
    private boolean destroyed;

    public static boolean requestLockOverlay(String packageName, String source, String foregroundPackage) {
        LockAccessibilityService service = currentInstance;
        if (service == null) {
            return false;
        }
        try {
            service.showRequestedLockOverlay(packageName, source, foregroundPackage);
            return true;
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to request lock overlay", throwable);
            ServiceStateStore stateStore = service.serviceStateStore;
            if (stateStore != null) {
                stateStore.recordAccessibilityLoopError(formatThrowable("外部请求锁层异常", throwable));
            }
            return false;
        }
    }

    public static boolean isOverlayShowingForPackage(String packageName) {
        LockAccessibilityService service = currentInstance;
        return service != null
                && service.overlayController != null
                && service.overlayController.isShowingFor(packageName);
    }

    public static boolean requestGuardModeBlock(String packageName, String source, String foregroundPackage) {
        LockAccessibilityService service = currentInstance;
        if (service == null) {
            return false;
        }
        try {
            if (service.store != null
                    && service.store.isGuardModeActive()
                    && AppCatalog.isGuardExitSurface(packageName)) {
                service.scheduleGuardExitSurfaceCheck(packageName, source, foregroundPackage);
                return true;
            }
            service.openGuardModePage(packageName, source, foregroundPackage);
            return true;
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to request guard mode block", throwable);
            ServiceStateStore stateStore = service.serviceStateStore;
            if (stateStore != null) {
                stateStore.recordAccessibilityLoopError(formatThrowable("外部请求守门拦截异常", throwable));
            }
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        destroyed = false;
        currentInstance = this;
        store = new AppLockStore(this);
        serviceStateStore = new ServiceStateStore(this);
        diagnosticStore = new DiagnosticStore(this);
        overlayController = new LockOverlayController(this);
        serviceStateStore.touchAccessibilityHeartbeat();
        serviceStateStore.recordAccessibilityEvent("无障碍服务已连接");
        if (store.isMonitorEnabled() && PermissionUtils.hasUsageAccess(this) && !serviceStateStore.isMonitorAlive()) {
            boolean started = AppMonitorService.start(this);
            serviceStateStore.recordRestartAttempt(started
                    ? "无障碍重连后补拉监控服务"
                    : "无障碍重连后补拉监控服务失败");
        }
        handler.removeCallbacks(heartbeat);
        handler.removeCallbacks(windowChecker);
        handler.post(heartbeat);
        handler.post(windowChecker);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        ensureStores();
        serviceStateStore.touchAccessibilityHeartbeat();

        String eventPackage = packageOf(event.getPackageName());
        if (diagnosticStore != null && eventPackage != null && !eventPackage.trim().isEmpty()) {
            diagnosticStore.recordForeground(eventPackage);
        }
        if (getPackageName().equals(eventPackage)) {
            if (store.isGuardModeActive()) {
                if (GuardModeActivity.isShowing()) {
                    if (overlayController != null) {
                        overlayController.hide("guard_page_foreground", eventPackage);
                    }
                    recordSkip("守门页已在前台", eventPackage, null);
                    return;
                }
                if (isAllowedAppActuallyForeground("self_foreground_guard_redirect")) {
                    return;
                }
                openGuardModePage(eventPackage, "self_foreground_guard_redirect", eventPackage);
                return;
            }
            if (overlayController != null && overlayController.getShowingPackage() != null) {
                recordSkip("锁层已显示，忽略锁层自身事件", eventPackage, overlayController.getShowingPackage());
                return;
            }
            if (overlayController != null) {
                overlayController.hide("host_app_foreground", eventPackage);
            }
            return;
        }

        if (!store.isMonitorEnabled()) {
            if (overlayController != null) {
                overlayController.hide("protection_not_enabled", eventPackage);
            }
            recordSkip("保护未开启", eventPackage, null);
            return;
        }

        if (tryImmediateLockFromEvent(eventPackage)) {
            return;
        }

        String pageText = flattenPageText(event);
        if (isSelfUninstallFlow(eventPackage, pageText)) {
            maybeOpenAdminGate(eventPackage);
            return;
        }

        checkActiveWindowPackage("accessibility_event", eventPackage);
    }

    @Override
    public void onInterrupt() {
        if (serviceStateStore != null) {
            serviceStateStore.recordAccessibilityEvent("无障碍服务收到中断");
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (overlayController != null) {
            overlayController.hide("service_destroyed", getPackageName());
        }
        if (serviceStateStore != null) {
            serviceStateStore.recordAccessibilityEvent("无障碍服务销毁");
            serviceStateStore.clearAccessibilityHeartbeat();
        }
        if (currentInstance == this) {
            currentInstance = null;
        }
        super.onDestroy();
    }

    private void ensureStores() {
        if (store == null) {
            store = new AppLockStore(this);
        }
        if (serviceStateStore == null) {
            serviceStateStore = new ServiceStateStore(this);
        }
        if (diagnosticStore == null) {
            diagnosticStore = new DiagnosticStore(this);
        }
        if (overlayController == null) {
            overlayController = new LockOverlayController(this);
        }
    }

    private void showRequestedLockOverlay(String packageName, String source, String foregroundPackage) {
        ensureStores();
        serviceStateStore.touchAccessibilityHeartbeat();
        if (!store.isMonitorEnabled()) {
            recordSkip("保护未开启", foregroundPackage, packageName);
            return;
        }
        long now = System.currentTimeMillis();
        long todayUsage = SystemUsageReader.getTodayUsage(this, packageName);
        if (!store.shouldBlockPackage(packageName, todayUsage, now)) {
            recordGuardDecision(packageName, true);
            recordSkip(blockSkipReason(packageName, todayUsage, now), foregroundPackage, packageName);
            return;
        }
        recordGuardDecision(packageName, false);
        if (store.isGuardModeActive()) {
            openGuardModePage(packageName, source, foregroundPackage);
            return;
        }
        if (false && overlayController.isShowingFor(packageName)) {
            recordSkip("锁层已经显示，跳过后台兜底重复弹出", foregroundPackage, packageName);
            return;
        }
        serviceStateStore.recordAccessibilityEvent("后台保护请求显示锁层：" + packageName);
        overlayController.show(packageName, source, foregroundPackage);
    }

    private boolean tryImmediateLockFromEvent(String eventPackage) {
        if (TextUtils.isEmpty(eventPackage) || getPackageName().equals(eventPackage)) {
            return false;
        }
        if (store.isGuardModeActive() && AppCatalog.isGuardExitSurface(eventPackage)) {
            recordGuardDecision(eventPackage, false);
            scheduleGuardExitSurfaceCheck(eventPackage, "accessibility_event_guard_exit", eventPackage);
            return true;
        }
        long now = System.currentTimeMillis();
        long todayUsage = SystemUsageReader.getTodayUsage(this, eventPackage);
        if (!store.shouldBlockPackage(eventPackage, todayUsage, now)) {
            recordGuardDecision(eventPackage, true);
            recordSkip(blockSkipReason(eventPackage, todayUsage, now), eventPackage, eventPackage);
            return true;
        }
        recordGuardDecision(eventPackage, false);
        if (store.isGuardModeActive()) {
            openGuardModePage(eventPackage, "accessibility_event_guard_mode", eventPackage);
            return true;
        }
        if (overlayController != null && overlayController.isShowingFor(eventPackage)) {
            recordSkip("锁层已经显示，跳过无障碍即时重复弹出", eventPackage, eventPackage);
            return true;
        }

        lastPromptKey = "lock:" + eventPackage;
        lastPromptAt = now;
        if (diagnosticStore != null) {
            diagnosticStore.recordLockAttempt(eventPackage, "accessibility_event_immediate");
            diagnosticStore.recordForeground(eventPackage);
        }
        serviceStateStore.recordAccessibilityEvent("无障碍即时锁层：" + eventPackage);
        Log.d(TAG, "Blocking package immediately from event target=" + eventPackage);
        overlayController.show(eventPackage, "accessibility_event_immediate", eventPackage);
        return true;
    }

    private void maybeOpenAdminGate(String eventPackage) {
        long now = System.currentTimeMillis();
        String promptKey = "admin_gate:" + eventPackage;
        if (isInCooldown(promptKey, now)) {
            return;
        }
        lastPromptKey = promptKey;
        lastPromptAt = now;
        Log.d(TAG, "Opening admin gate for package " + eventPackage);
        AdminGateActivity.open(this, AdminGateActivity.DEST_APPS);
    }

    private boolean isInCooldown(String promptKey, long now) {
        return promptKey.equals(lastPromptKey) && now - lastPromptAt < PROMPT_COOLDOWN_MS;
    }

    private boolean isSelfUninstallFlow(String eventPackage, String pageText) {
        if (TextUtils.isEmpty(pageText)) {
            return false;
        }
        if (TextUtils.isEmpty(eventPackage) || !AppCatalog.isRiskPackage(eventPackage)) {
            return false;
        }
        String lower = pageText.toLowerCase(Locale.ROOT);
        boolean hasSelfMarker = false;
        for (String marker : SELF_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                hasSelfMarker = true;
                break;
            }
        }
        if (!hasSelfMarker) {
            String appName = getString(R.string.app_name);
            hasSelfMarker = lower.contains(appName.toLowerCase(Locale.ROOT));
        }
        if (!hasSelfMarker) {
            return false;
        }
        for (String keyword : UNINSTALL_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String flattenPageText(AccessibilityEvent event) {
        List<String> parts = new ArrayList<>();
        if (event.getText() != null) {
            for (CharSequence text : event.getText()) {
                addText(parts, text);
            }
        }
        addText(parts, event.getContentDescription());
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            collectNodeText(root, parts, 0);
        }
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(part);
        }
        return out.toString();
    }

    private void collectNodeText(AccessibilityNodeInfo node, List<String> parts, int depth) {
        if (node == null || depth > 6 || parts.size() > 120) {
            return;
        }
        addText(parts, node.getText());
        addText(parts, node.getContentDescription());
        for (int i = 0; i < node.getChildCount(); i++) {
            collectNodeText(node.getChild(i), parts, depth + 1);
        }
    }

    private static void addText(List<String> parts, CharSequence text) {
        if (text == null) {
            return;
        }
        String value = text.toString().trim();
        if (!value.isEmpty() && !parts.contains(value)) {
            parts.add(value);
        }
    }

    private void checkActiveWindowPackage(String source) {
        checkActiveWindowPackage(source, null);
    }

    private void checkActiveWindowPackage(String source, String eventPackageHint) {
        ensureStores();
        serviceStateStore.touchAccessibilityHeartbeat();

        long now = System.currentTimeMillis();
        if (!store.isMonitorEnabled()) {
            if (overlayController != null) {
                overlayController.hide("protection_not_enabled", getPackageName());
            }
            recordSkip("保护未开启", getPackageName(), null);
            return;
        }

        String packageName = resolveActiveWindowPackage();
        if ((packageName == null || getPackageName().equals(packageName))
                && store.isProtectedPackage(eventPackageHint)
                && !getPackageName().equals(eventPackageHint)) {
            packageName = eventPackageHint;
        }
        if (diagnosticStore != null && packageName != null && !getPackageName().equals(packageName)) {
            diagnosticStore.recordForeground(packageName);
        }

        if (store.isGuardModeActive() && getPackageName().equals(packageName)) {
            if (GuardModeActivity.isShowing()) {
                if (overlayController != null) {
                    overlayController.hide("guard_page_foreground", packageName);
                }
                recordSkip("守门页已在前台", packageName, null);
                return;
            }
            if (isAllowedAppActuallyForeground(source + "_self_guard_redirect")) {
                return;
            }
            openGuardModePage(packageName, source + "_self_guard_redirect", packageName);
            return;
        }

        if (packageName == null || getPackageName().equals(packageName)) {
            if (overlayController != null && overlayController.getShowingPackage() != null) {
                recordSkip(
                        packageName == null ? "锁层已显示，前台临时识别为空，保持锁层" : "锁层已显示，前台临时识别为守门应用锁自己，保持锁层",
                        packageName,
                        overlayController.getShowingPackage()
                );
                return;
            }
            recordSkip(packageName == null ? "前台应用识别为空" : "当前前台是守门应用锁自己", packageName, null);
            return;
        }

        if (overlayController != null) {
            if (store.isGuardModeActive() && overlayController.isGuardModeOverlayShowing()) {
                if (AppCatalog.isGuardExitSurface(packageName)) {
                    scheduleGuardExitSurfaceCheck(packageName, source + "_guard_exit_surface", packageName);
                    return;
                }
                if (store.isAllowedInGuardMode(packageName)) {
                    overlayController.hide("guard_allowed_foreground", packageName);
                    recordGuardDecision(packageName, true);
                    return;
                }
                openGuardModePage(packageName, source + "_guard_overlay_update", packageName);
                return;
            }
            String showingPackage = overlayController.getShowingPackage();
            if (showingPackage != null && !showingPackage.equals(packageName)) {
                long lastShowAt = diagnosticStore == null ? 0L : diagnosticStore.getLastLockShowAt();
                long lastAttemptAt = diagnosticStore == null ? 0L : diagnosticStore.getLastLockAttemptAt();
                long lockStartedAt = Math.max(lastShowAt, lastAttemptAt);
                if (lockStartedAt > 0L && now - lockStartedAt <= JUST_SHOWN_GRACE_MS) {
                    recordSkip("锁层刚显示，忽略启动过渡前台：" + packageName, packageName, showingPackage);
                    return;
                }
                Log.d(TAG, "Hiding overlay because active package changed from " + showingPackage + " to " + packageName);
                overlayController.hide("foreground_changed", packageName);
            }
        }

        if (store.isGuardModeActive() && AppCatalog.isGuardExitSurface(packageName)) {
            recordGuardDecision(packageName, false);
            scheduleGuardExitSurfaceCheck(packageName, source + "_guard_exit_surface", packageName);
            return;
        }

        if (store.isGuardModeActive() && store.isAllowedInGuardMode(packageName)) {
            recordGuardDecision(packageName, true);
            if (overlayController != null && overlayController.getShowingPackage() != null) {
                overlayController.hide("guard_allowed_foreground", packageName);
            }
            recordSkip("守门模式允许入口前台", packageName, packageName);
            return;
        }

        long todayUsage = SystemUsageReader.getTodayUsage(this, packageName);
        if (!store.shouldBlockPackage(packageName, todayUsage, now)) {
            recordGuardDecision(packageName, true);
            if (overlayController != null && overlayController.isShowingFor(packageName)) {
                overlayController.hide("package_not_blocked", packageName);
            }
            recordSkip(blockSkipReason(packageName, todayUsage, now), packageName, packageName);
            return;
        }
        recordGuardDecision(packageName, false);
        if (store.isGuardModeActive()) {
            openGuardModePage(packageName, source + "_guard_mode", packageName);
            return;
        }

        if (overlayController != null && overlayController.isShowingFor(packageName)) {
            recordSkip("锁层已经显示，跳过重复弹出", packageName, packageName);
            return;
        }

        String promptKey = "lock:" + packageName;
        if (isInCooldown(promptKey, now) && overlayController != null && overlayController.isShowingFor(packageName)) {
            return;
        }

        lastPromptKey = promptKey;
        lastPromptAt = now;
        Log.d(TAG, "Blocking package via accessibility target=" + packageName + " source=" + source);
        if (diagnosticStore != null) {
            diagnosticStore.recordLockAttempt(packageName, source);
        }
            overlayController.show(packageName, source, packageName);
    }

    private void scheduleGuardExitSurfaceCheck(String surfacePackage, String source, String foregroundPackage) {
        long now = System.currentTimeMillis();
        if (surfacePackage != null
                && surfacePackage.equals(lastGuardExitConfirmPackage)
                && now - lastGuardExitConfirmAt < GUARD_EXIT_CONFIRM_MS) {
            recordSkip("guard exit surface confirm already scheduled", foregroundPackage, surfacePackage);
            return;
        }
        lastGuardExitConfirmPackage = surfacePackage;
        lastGuardExitConfirmAt = now;
        recordSkip("guard exit surface delayed confirm", foregroundPackage, surfacePackage);
        handler.postDelayed(() -> confirmGuardExitSurface(surfacePackage, source), GUARD_EXIT_CONFIRM_MS);
    }

    private boolean isAllowedAppActuallyForeground(String source) {
        String foregroundPackage = ForegroundAppReader.getForegroundPackage(this);
        if (!TextUtils.isEmpty(foregroundPackage)
                && !getPackageName().equals(foregroundPackage)
                && !AppCatalog.isGuardExitSurface(foregroundPackage)
                && store.isAllowedInGuardMode(foregroundPackage)) {
            recordGuardDecision(foregroundPackage, true);
            if (overlayController != null) {
                overlayController.hide("guard_allowed_actual_foreground", foregroundPackage);
            }
            if (diagnosticStore != null) {
                diagnosticStore.recordForeground(foregroundPackage);
            }
            recordSkip("guard self event ignored because allowed app is foreground: " + source, foregroundPackage, foregroundPackage);
            return true;
        }
        return false;
    }

    private void confirmGuardExitSurface(String surfacePackage, String source) {
        ensureStores();
        if (!store.isGuardModeActive()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastGuardAllowedAt > 0L
                && now - lastGuardAllowedAt < RECENT_ALLOWED_GRACE_MS
                && !TextUtils.isEmpty(lastGuardAllowedPackage)) {
            String allowedForeground = ForegroundAppReader.getForegroundPackage(this);
            if (TextUtils.isEmpty(allowedForeground)
                    || getPackageName().equals(allowedForeground)
                    || AppCatalog.isGuardExitSurface(allowedForeground)
                    || lastGuardAllowedPackage.equals(allowedForeground)) {
                recordSkip("guard exit surface ignored after whitelist foreground", allowedForeground, lastGuardAllowedPackage);
                return;
            }
        }
        String recentLaunchPackage = store.getRecentGuardAllowedLaunchPackage(RECENT_ALLOWED_GRACE_MS);
        if (!TextUtils.isEmpty(recentLaunchPackage)) {
            if (AppCatalog.isGuardExitSurface(surfacePackage)) {
                recordSkip("guard exit surface ignored during whitelist app launch transition", surfacePackage, recentLaunchPackage);
                return;
            }
            String allowedForeground = ForegroundAppReader.getForegroundPackage(this);
            if (TextUtils.isEmpty(allowedForeground)
                    || getPackageName().equals(allowedForeground)
                    || AppCatalog.isGuardExitSurface(allowedForeground)
                    || recentLaunchPackage.equals(allowedForeground)) {
                recordSkip("guard exit surface ignored while launching whitelist app", allowedForeground, recentLaunchPackage);
                return;
            }
        }
        String currentPackage = resolveActiveWindowPackage();
        if (TextUtils.isEmpty(currentPackage) || getPackageName().equals(currentPackage)) {
            String usagePackage = ForegroundAppReader.getForegroundPackage(this);
            if (!TextUtils.isEmpty(usagePackage)) {
                currentPackage = usagePackage;
            }
        }
        if (TextUtils.isEmpty(currentPackage)) {
            currentPackage = surfacePackage;
        }
        if (diagnosticStore != null && !TextUtils.isEmpty(currentPackage)) {
            diagnosticStore.recordForeground(currentPackage);
        }
        if (AppCatalog.isGuardExitSurface(currentPackage)) {
            recordGuardDecision(currentPackage, false);
            openGuardModePage(currentPackage, source + "_confirmed", currentPackage);
            return;
        }
        if (store.isAllowedInGuardMode(currentPackage)) {
            recordGuardDecision(currentPackage, true);
            if (overlayController != null) {
                overlayController.hide("guard_allowed_after_exit_surface", currentPackage);
            }
            recordSkip("guard whitelist app launched after exit surface", currentPackage, currentPackage);
            return;
        }
        long todayUsage = SystemUsageReader.getTodayUsage(this, currentPackage);
        if (store.shouldBlockPackage(currentPackage, todayUsage, now)) {
            recordGuardDecision(currentPackage, false);
            openGuardModePage(currentPackage, source + "_confirmed_blocked", currentPackage);
            return;
        }
        recordGuardDecision(currentPackage, true);
        recordSkip(blockSkipReason(currentPackage, todayUsage, now), currentPackage, currentPackage);
    }

    private void openGuardModePage(String packageName, String source, String foregroundPackage) {
        String promptKey = "guard:" + packageName;
        long now = System.currentTimeMillis();
        if (GuardModeActivity.isShowing() && !AppCatalog.isGuardExitSurface(packageName)) {
            if (overlayController != null) {
                overlayController.hide("guard_activity_showing", foregroundPackage);
            }
            recordSkip("守门页已经显示，跳过重复拉回", foregroundPackage, packageName);
            return;
        }
        if (overlayController != null && overlayController.isGuardModeOverlayShowing()) {
            overlayController.showGuardMode(packageName, source, foregroundPackage);
            if (shouldForceHomeForGuardTarget(packageName)) {
                pullBackToHome(packageName);
            }
            recordSkip("守门层已经显示，刷新守门状态", foregroundPackage, packageName);
            return;
        }
        if (!AppCatalog.isGuardExitSurface(packageName) && isInCooldown(promptKey, now)) {
            recordSkip("守门页拉回冷却中", foregroundPackage, packageName);
            return;
        }
        lastPromptKey = promptKey;
        lastPromptAt = now;
        if (diagnosticStore != null) {
            diagnosticStore.recordLockAttempt(packageName, source);
            diagnosticStore.recordForeground(packageName);
            diagnosticStore.recordFallbackLockResult(packageName, "守门层拉回", "无障碍全屏守门层");
        }
        serviceStateStore.recordAccessibilityEvent("守门模式显示全屏守门层：" + packageName);
        if (overlayController != null) {
            overlayController.showGuardMode(packageName, source, foregroundPackage);
            if (shouldForceHomeForGuardTarget(packageName)) {
                pullBackToHome(packageName);
            }
            return;
        }
        try {
            GuardModeActivity.open(this, packageName, source);
        } catch (RuntimeException exception) {
            if (diagnosticStore != null) {
                diagnosticStore.recordFallbackLockResult(
                        packageName,
                        "守门页拉回失败",
                        exception.getClass().getSimpleName() + ": " + exception.getMessage()
                );
            }
            serviceStateStore.recordAccessibilityEvent("守门页拉回失败：" + exception.getClass().getSimpleName());
            Log.e(TAG, "Failed to open guard mode page", exception);
        }
    }

    private boolean shouldForceHomeForGuardTarget(String packageName) {
        return store != null
                && store.isGuardModeActive()
                && !TextUtils.isEmpty(packageName)
                && !getPackageName().equals(packageName)
                && !AppCatalog.isGuardExitSurface(packageName)
                && !store.isAllowedInGuardMode(packageName);
    }

    private void pullBackToHome(String packageName) {
        boolean performed = false;
        try {
            performed = performGlobalAction(GLOBAL_ACTION_HOME);
        } catch (RuntimeException exception) {
            if (diagnosticStore != null) {
                diagnosticStore.recordFallbackLockResult(
                        packageName,
                        "无障碍拉回桌面失败",
                        exception.getClass().getSimpleName() + ": " + exception.getMessage()
                );
            }
            serviceStateStore.recordAccessibilityEvent("无障碍拉回桌面失败：" + exception.getClass().getSimpleName());
            return;
        }
        if (diagnosticStore != null) {
            diagnosticStore.recordFallbackLockResult(
                    packageName,
                    "无障碍拉回桌面",
                    performed ? "已执行" : "系统拒绝"
            );
        }
        serviceStateStore.recordAccessibilityEvent("守门模式无障碍拉回桌面：" + packageName + "，结果：" + performed);
    }

    private String resolveActiveWindowPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String rootPackage = root == null ? null : packageOf(root.getPackageName());
        if (getPackageName().equals(rootPackage)) {
            if (overlayController == null || overlayController.getShowingPackage() == null) {
                return getPackageName();
            }
        } else if (!TextUtils.isEmpty(rootPackage)) {
            if (store.isProtectedPackage(rootPackage)) {
                return rootPackage;
            }
            return rootPackage;
        }

        boolean sawApplicationWindow = false;
        String firstApplicationPackage = null;
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) {
            windows = new ArrayList<>();
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) {
                continue;
            }
            if (!window.isActive() && !window.isFocused()) {
                continue;
            }
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            AccessibilityNodeInfo windowRoot = window.getRoot();
            String windowPackage = windowRoot == null ? null : packageOf(windowRoot.getPackageName());
            if (TextUtils.isEmpty(windowPackage)) {
                continue;
            }
            sawApplicationWindow = true;
            if (firstApplicationPackage == null && !getPackageName().equals(windowPackage)) {
                firstApplicationPackage = windowPackage;
            }
            if (getPackageName().equals(windowPackage)) {
                continue;
            }
            if (store.isProtectedPackage(windowPackage)) {
                return windowPackage;
            }
        }
        if (firstApplicationPackage != null) {
            return firstApplicationPackage;
        }

        String foregroundPackage = ForegroundAppReader.getForegroundPackage(this);
        if (getPackageName().equals(foregroundPackage)) {
            return getPackageName();
        }
        if (store.isProtectedPackage(foregroundPackage)) {
            return foregroundPackage;
        }
        if (!TextUtils.isEmpty(foregroundPackage)) {
            return foregroundPackage;
        }
        if (!sawApplicationWindow) {
            return null;
        }
        return null;
    }

    private String blockSkipReason(String packageName, long todayUsage, long now) {
        if (packageName == null) {
            return "前台应用识别为空";
        }
        if (!store.isMonitorEnabled()) {
            return "保护未开启";
        }
        if (store.isGuardModeActive()) {
            return store.getGuardDecisionReason(packageName);
        }
        if (!store.isProtectedPackage(packageName)) {
            return "前台应用不在锁定名单内";
        }
        if (store.hasSetupAccess(packageName)) {
            return "配置放行时间内";
        }
        if (store.hasTemporaryPass(packageName) && !store.isRuleBlocking(packageName, todayUsage, now)) {
            return "监管人临时放行中";
        }
        return "未命中拦截条件";
    }

    private void recordSkip(String reason, String foregroundPackage, String targetPackage) {
        if (diagnosticStore != null) {
            diagnosticStore.recordLockSkipped(reason, foregroundPackage, targetPackage);
        }
    }

    private void recordGuardDecision(String packageName, boolean allowed) {
        if (diagnosticStore == null || store == null || !store.isGuardModeActive()) {
            return;
        }
        if (allowed
                && !TextUtils.isEmpty(packageName)
                && !getPackageName().equals(packageName)
                && !AppCatalog.isGuardExitSurface(packageName)
                && store.isAllowedInGuardMode(packageName)) {
            lastGuardAllowedPackage = packageName;
            lastGuardAllowedAt = System.currentTimeMillis();
        }
        diagnosticStore.recordGuardDecision(
                packageName,
                store.getGuardModeLabel(),
                allowed,
                store.getGuardDecisionReason(packageName),
                store.getGuardModeRemainingMs()
        );
    }

    private void recordAccessibilityLoopError(String prefix, Throwable throwable) {
        String message = formatThrowable(prefix, throwable);
        if (serviceStateStore != null) {
            serviceStateStore.recordAccessibilityEvent(message);
            serviceStateStore.recordAccessibilityLoopError(message);
        }
        Log.e(TAG, message, throwable);
    }

    private static String formatThrowable(String prefix, Throwable throwable) {
        if (throwable == null) {
            return prefix;
        }
        return prefix + "：" + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
    }

    private static String packageOf(CharSequence text) {
        return text == null ? null : text.toString();
    }
}
