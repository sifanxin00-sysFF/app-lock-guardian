package com.codex.applockguard.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.DeviceIdentityStore;
import com.codex.applockguard.data.DiagnosticStore;
import com.codex.applockguard.net.ApprovalApi;
import com.codex.applockguard.ui.StudentWebActivity;
import com.codex.applockguard.util.AppCatalog;
import com.codex.applockguard.util.LockForegroundVerifier;
import com.codex.applockguard.util.SystemUsageReader;
import com.codex.applockguard.util.Ui;

import java.util.Calendar;
import java.util.Locale;
import java.util.Set;

final class LockOverlayController {
    private static final String TAG = "LockOverlayController";

    private final AccessibilityService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ApprovalApi api = new ApprovalApi();
    private final AppLockStore store;
    private final DeviceIdentityStore identityStore;
    private final DiagnosticStore diagnosticStore;
    private final WindowManager windowManager;

    private View overlayView;
    private TextView statusView;
    private String showingPackage;
    private String pendingRequestId;
    private boolean polling;
    private boolean guardModeOverlay;

    LockOverlayController(AccessibilityService service) {
        this.service = service;
        this.store = new AppLockStore(service);
        this.identityStore = new DeviceIdentityStore(service);
        this.diagnosticStore = new DiagnosticStore(service);
        this.windowManager = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    void show(String packageName, String source, String foregroundPackage) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        mainHandler.post(() -> showInternal(packageName, source, foregroundPackage));
    }

    void showGuardMode(String packageName, String source, String foregroundPackage) {
        String target = TextUtils.isEmpty(packageName) ? service.getPackageName() : packageName;
        mainHandler.post(() -> showGuardModeInternal(target, source, foregroundPackage));
    }

    void hide(String reason, String foregroundPackage) {
        mainHandler.post(() -> hideInternal(reason, foregroundPackage));
    }

    String getShowingPackage() {
        return showingPackage;
    }

    boolean isShowingFor(String packageName) {
        if (overlayView != null && diagnosticStore.getLastLockHideAt() > diagnosticStore.getLastLockShowAt()) {
            clearStaleOverlayState("锁层已经被隐藏，清理旧显示状态");
            return false;
        }
        if (overlayView != null && !overlayView.isAttachedToWindow()) {
            clearStaleOverlayState("锁层 View 已脱离窗口，允许重新显示");
            return false;
        }
        return packageName != null && packageName.equals(showingPackage) && overlayView != null;
    }

    boolean isGuardModeOverlayShowing() {
        return guardModeOverlay && overlayView != null;
    }

    private void clearStaleOverlayState(String reason) {
        String stalePackage = showingPackage;
        overlayView = null;
        showingPackage = null;
        statusView = null;
        pendingRequestId = null;
        polling = false;
        guardModeOverlay = false;
        diagnosticStore.recordLockSkipped(reason, null, stalePackage);
        Log.d(TAG, "Overlay state was stale, clearing target=" + stalePackage + " reason=" + reason);
    }

    private void showGuardModeInternal(String packageName, String source, String foregroundPackage) {
        if (!store.isGuardModeActive()) {
            hideInternal("guard_mode_inactive", foregroundPackage);
            return;
        }
        if (isGuardModeOverlayShowing()) {
            updateGuardStatus(packageName);
            return;
        }

        hideInternal("replace_with_guard_overlay", foregroundPackage);
        Log.i("GuardLockPage", "opened from LockOverlayController guard overlay target="
                + packageName + " source=" + source + " foreground=" + foregroundPackage);

        ScrollView scrollView = new ScrollView(service);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFFF6F7F9);
        scrollView.setClickable(true);
        scrollView.setFocusable(true);

        LinearLayout root = Ui.root(service);
        root.setClickable(true);
        root.setFocusable(true);
        scrollView.addView(root);

        root.addView(Ui.title(service, "守门模式运行中"));
        statusView = Ui.body(service, guardStatusText(packageName));
        root.addView(statusView);
        root.addView(Ui.body(service, "守门期间不能自由回桌面。白名单应用可以从下面入口打开，白名单外应用会被挡回守门状态。"));

        Button releaseButton = Ui.button(service, "申请临时放行");
        releaseButton.setOnClickListener(v -> openStudentRoute("temporaryAccess"));
        root.addView(releaseButton);

        Button detailButton = Ui.button(service, "查看守门状态");
        detailButton.setOnClickListener(v -> openStudentRoute("guardDetail"));
        root.addView(detailButton);

        Button whitelistButton = Ui.button(service, "打开白名单应用");
        LinearLayout whitelistContainer = new LinearLayout(service);
        whitelistContainer.setOrientation(LinearLayout.VERTICAL);
        whitelistButton.setOnClickListener(v -> populateWhitelistEntryButtons(whitelistContainer));
        root.addView(whitelistButton);
        root.addView(whitelistContainer);

        try {
            windowManager.addView(scrollView, fullScreenParams());
        } catch (RuntimeException exception) {
            overlayView = null;
            showingPackage = null;
            pendingRequestId = null;
            polling = false;
            guardModeOverlay = false;
            diagnosticStore.recordLockSkipped(
                    "守门层添加失败：" + exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    foregroundPackage,
                    packageName
            );
            Log.e(TAG, "Failed to add guard overlay target=" + packageName, exception);
            return;
        }

        overlayView = scrollView;
        showingPackage = packageName;
        pendingRequestId = null;
        polling = false;
        guardModeOverlay = true;
        diagnosticStore.recordLockShown(packageName, source == null ? "guard_mode_overlay" : source, foregroundPackage);
        Log.d(TAG, "Guard overlay shown target=" + packageName + " source=" + source + " foreground=" + foregroundPackage);
    }

    private void openStudentRoute(String route) {
        Intent intent = new Intent(service, StudentWebActivity.class);
        intent.putExtra("route", route);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        hide("open_student_route", service.getPackageName());
        service.startActivity(intent);
    }

    private Button guardEntryButton(String text, Intent intent, String label, String packageName) {
        Button button = Ui.button(service, text);
        button.setOnClickListener(v -> {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                store.markGuardAllowedLaunch(packageName);
                hide("open_guard_allowed_entry", service.getPackageName());
                service.startActivity(intent);
                diagnosticStore.recordFallbackLockResult(service.getPackageName(), "守门层允许入口", "已打开" + label);
            } catch (RuntimeException exception) {
                if (statusView != null) {
                    statusView.setText(label + "打开失败，请稍后重试。");
                }
                diagnosticStore.recordFallbackLockResult(
                        service.getPackageName(),
                        "守门层允许入口失败",
                        label + "：" + exception.getClass().getSimpleName() + ": " + exception.getMessage()
                );
            }
        });
        return button;
    }

    private void populateWhitelistEntryButtons(LinearLayout root) {
        root.removeAllViews();
        Set<String> packages = store.getGuardWhitelistPackages();
        int added = 0;
        for (String packageName : packages) {
            if (TextUtils.isEmpty(packageName)
                    || service.getPackageName().equals(packageName)) {
                continue;
            }
            Intent launchIntent = service.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                continue;
            }
            String label = AppCatalog.getLabel(service, packageName);
            root.addView(guardEntryButton("打开 " + label, launchIntent, label, packageName));
            added++;
            if (added >= 6) {
                return;
            }
        }
        if (added == 0 && statusView != null) {
            statusView.setText(guardStatusText(showingPackage) + "\n当前没有可打开的白名单应用。");
        }
    }

    private void updateGuardStatus(String packageName) {
        if (statusView != null) {
            statusView.setText(guardStatusText(packageName));
        }
    }

    private String guardStatusText(String packageName) {
        String target = TextUtils.isEmpty(packageName) ? "桌面/系统页面" : AppCatalog.getLabel(service, packageName);
        return "当前模式：" + store.getGuardModeLabel() + "\n"
                + "剩余时间：" + formatRemaining(store.getGuardModeRemainingMs()) + "\n"
                + "刚才拦截：" + target + "\n"
                + "原因：" + store.getGuardDecisionReason(packageName);
    }

    private void showInternal(String packageName, String source, String foregroundPackage) {
        long now = System.currentTimeMillis();
        long todayUsage = SystemUsageReader.getTodayUsage(service, packageName);
        if (!store.shouldBlockPackage(packageName, todayUsage, now)) {
            recordGuardDecision(packageName, true);
            if (packageName.equals(showingPackage)) {
                hideInternal("target_no_longer_blocked", foregroundPackage);
            }
            return;
        }
        recordGuardDecision(packageName, false);
        if (isShowingFor(packageName)) {
            return;
        }

        hideInternal("replace_overlay", foregroundPackage);
        Log.i("GuardLockPage", "opened from LockOverlayController app overlay target="
                + packageName + " source=" + source + " foreground=" + foregroundPackage);

        ScrollView scrollView = new ScrollView(service);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFFF6F7F9);
        scrollView.setClickable(true);
        scrollView.setFocusable(true);

        LinearLayout root = Ui.root(service);
        root.setClickable(true);
        root.setFocusable(true);
        scrollView.addView(root);

        String label = AppCatalog.getLabel(service, packageName);
        root.addView(Ui.title(service, label + " 当前不可使用"));
        root.addView(Ui.body(service, store.isLocked(packageName)
                ? "原因：家长已限制"
                : "原因：" + store.getBlockingReason(packageName, todayUsage, now)));

        statusView = Ui.body(service, "还没有发起请求。");
        root.addView(statusView);

        root.addView(requestButton("申请临时放行", 30));

        Button leaveButton = Ui.button(service, "离开");
        leaveButton.setOnClickListener(v -> {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            hide("leave_to_home", service.getPackageName());
        });
        root.addView(leaveButton);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        long attemptAt = packageName.equals(diagnosticStore.getLastLockAttemptTarget())
                ? diagnosticStore.getLastLockAttemptAt()
                : 0L;
        String finalForeground = LockForegroundVerifier.resolveCurrentForeground(service, diagnosticStore, attemptAt);
        diagnosticStore.recordLockAttemptFinalForeground(packageName, finalForeground);
        if (!packageName.equals(finalForeground)) {
            String reason = "锁层显示前目标已离开，当前前台：" + LockForegroundVerifier.describe(finalForeground);
            diagnosticStore.recordLockAttemptCancelled(packageName, finalForeground, reason);
            diagnosticStore.recordLockSkipped(reason, finalForeground, packageName);
            Log.d(TAG, "Skipping overlay because target left target=" + packageName + " foreground=" + finalForeground);
            return;
        }

        try {
            windowManager.addView(scrollView, params);
        } catch (RuntimeException exception) {
            overlayView = null;
            showingPackage = null;
            pendingRequestId = null;
            polling = false;
            diagnosticStore.recordLockSkipped(
                    "锁层添加失败：" + exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    foregroundPackage,
                    packageName
            );
            Log.e(TAG, "Failed to add lock overlay target=" + packageName, exception);
            return;
        }
        overlayView = scrollView;
        showingPackage = packageName;
        pendingRequestId = null;
        polling = false;
        guardModeOverlay = false;

        diagnosticStore.recordLockShown(packageName, source, foregroundPackage);
        Log.d(TAG, "Overlay shown target=" + packageName + " source=" + source + " foreground=" + foregroundPackage);
    }

    private WindowManager.LayoutParams fullScreenParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
    }

    private String formatRemaining(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.CHINA, "%d 小时 %d 分钟", hours, remainingMinutes);
        }
        if (minutes > 0L) {
            return String.format(Locale.CHINA, "%d 分钟 %d 秒", minutes, remainingSeconds);
        }
        return remainingSeconds + " 秒";
    }

    private Button requestButton(String text, int minutes) {
        Button button = Ui.button(service, text);
        button.setOnClickListener(v -> createRequest(minutes));
        return button;
    }

    private void recordGuardDecision(String packageName, boolean allowed) {
        if (!store.isGuardModeActive()) {
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

    private void createRequest(int minutes) {
        String targetPackage = showingPackage;
        if (targetPackage == null || statusView == null) {
            return;
        }
        statusView.setText("正在提交请求...");
        new Thread(() -> {
            try {
                ApprovalApi.CreateRequestResult result = api.createStudentReleaseRequest(
                        targetPackage,
                        AppCatalog.getLabel(service, targetPackage),
                        minutes,
                        ""
                );
                pendingRequestId = result.requestId;
                mainHandler.post(() -> {
                    if (statusView != null) {
                        statusView.setText("请求已提交，等待监管人审批。");
                    }
                });
                startPolling(targetPackage);
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (statusView != null) {
                        statusView.setText("提交失败，请检查网络后重试。");
                    }
                });
            }
        }).start();
    }

    private void startPolling(String targetPackage) {
        if (pendingRequestId == null || pendingRequestId.isEmpty() || polling) {
            return;
        }
        polling = true;
        new Thread(() -> {
            while (polling) {
                try {
                    ApprovalApi.RequestStatusResult result = api.getStudentReleaseRequestStatus(pendingRequestId);
                    if ("approved".equals(result.status)) {
                        polling = false;
                        mainHandler.post(() -> handleApproved(targetPackage, result.approvedMinutes, result.approvedMode, result.guardianNote));
                        return;
                    }
                    if ("rejected".equals(result.status)) {
                        polling = false;
                        mainHandler.post(() -> {
                            if (statusView != null) {
                                statusView.setText("监管人已经拒绝这次请求。");
                            }
                        });
                        return;
                    }
                    mainHandler.post(() -> {
                        if (statusView != null) {
                            statusView.setText("请求处理中，等待监管人审批...");
                        }
                    });
                    Thread.sleep(3000L);
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (statusView != null) {
                            statusView.setText("轮询失败，稍后会继续重试。");
                        }
                    });
                    try {
                        Thread.sleep(4000L);
                    } catch (InterruptedException interruptedException) {
                        polling = false;
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }).start();
    }

    private void handleApproved(String packageName, int approvedMinutes, String approvedMode, String guardianNote) {
        if ("today".equalsIgnoreCase(approvedMode)) {
            store.grantTemporaryPassUntil(packageName, endOfToday());
        } else {
            int safeMinutes = approvedMinutes > 0 ? approvedMinutes : 10;
            store.grantTemporaryPassUntil(packageName, System.currentTimeMillis() + safeMinutes * 60_000L);
        }

        if (statusView != null) {
            if (!TextUtils.isEmpty(guardianNote)) {
                statusView.setText(String.format(Locale.CHINA, "监管人已批准：%s", guardianNote));
            } else {
                statusView.setText("监管人已批准。");
            }
        }
        mainHandler.postDelayed(() -> hideInternal("approved_pass_granted", packageName), 600L);
    }

    private long endOfToday() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTimeInMillis();
    }

    private void hideInternal(String reason, String foregroundPackage) {
        polling = false;
        pendingRequestId = null;
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
        if (showingPackage != null) {
            diagnosticStore.recordLockHidden(reason, foregroundPackage);
            Log.d(TAG, "Overlay hidden target=" + showingPackage + " reason=" + reason + " foreground=" + foregroundPackage);
        }
        showingPackage = null;
        statusView = null;
        guardModeOverlay = false;
    }
}
