package com.codex.applockguard.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.DiagnosticStore;
import com.codex.applockguard.service.AppMonitorService;
import com.codex.applockguard.sync.StudentSyncManager;
import com.codex.applockguard.util.AppCatalog;
import com.codex.applockguard.util.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public final class GuardModeActivity extends android.app.Activity {
    public static final String EXTRA_BLOCKED_PACKAGE = "blocked_package";
    public static final String EXTRA_SOURCE = "source";
    private static final String TAG = "GuardLockPage";

    private static volatile boolean showing;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            if (showing) {
                handler.postDelayed(this, 1_000L);
            }
        }
    };

    private AppLockStore store;
    private DiagnosticStore diagnosticStore;
    private String blockedPackage;
    private String source;

    public static void open(Context context, String blockedPackage, String source) {
        Intent intent = new Intent(context, GuardModeActivity.class);
        intent.putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage);
        intent.putExtra(EXTRA_SOURCE, source);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    public static boolean isShowing() {
        return showing;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );
        store = new AppLockStore(this);
        diagnosticStore = new DiagnosticStore(this);
        applyIntent(getIntent());
        Log.i(TAG, "opened from GuardModeActivity source=" + safeSource() + " blockedPackage=" + blockedPackage);
        showing = true;
        AppMonitorService.start(this);
        StudentSyncManager.syncAllAsync(this, "guard_mode_activity_open");
        render();
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 1_000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = true;
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        showing = false;
    }

    @Override
    protected void onDestroy() {
        showing = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (diagnosticStore != null) {
            diagnosticStore.recordFallbackLockResult(
                    getPackageName(),
                    "守门页返回键拦截",
                    "守门模式运行中，已留在守门页"
            );
        }
        render();
    }

    private void applyIntent(Intent intent) {
        blockedPackage = intent == null ? null : intent.getStringExtra(EXTRA_BLOCKED_PACKAGE);
        source = intent == null ? "" : intent.getStringExtra(EXTRA_SOURCE);
        if (source == null) {
            source = "";
        }
    }

    private void render() {
        if (store == null) {
            store = new AppLockStore(this);
        }
        if (!store.isGuardModeActive()) {
            StudentSyncManager.reportGuardStoppedAsync(this, "guard_mode_activity_inactive");
            finishToMain();
            return;
        }

        if (diagnosticStore != null && blockedPackage != null && !blockedPackage.trim().isEmpty()) {
            diagnosticStore.recordGuardDecision(
                    blockedPackage,
                    store.getGuardModeLabel(),
                    false,
                    store.getGuardDecisionReason(blockedPackage),
                    store.getGuardModeRemainingMs()
            );
            diagnosticStore.recordLockShown(blockedPackage, safeSource(), getPackageName());
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = Ui.root(this);
        scrollView.addView(root);

        root.addView(Ui.title(this, "守门模式运行中"));
        root.addView(Ui.body(this,
                "当前模式：" + store.getGuardModeLabel() + "\n"
                        + "剩余时间：" + formatRemaining(store.getGuardModeRemainingMs()) + "\n"
                        + "结束时间：" + formatTime(store.getGuardModeUntil()) + "\n"
                        + "白名单数量：" + store.getGuardWhitelistPackages().size() + " 个"
        ));

        if (blockedPackage != null && !blockedPackage.trim().isEmpty()) {
            root.addView(Ui.body(this,
                    "刚才拦截：" + AppCatalog.getLabel(this, blockedPackage) + "\n"
                            + "原因：" + store.getGuardDecisionReason(blockedPackage)
            ));
        } else {
            root.addView(Ui.body(this, "锁机期间只允许白名单应用。白名单外应用会被拉回这个页面。"));
        }

        root.addView(Ui.body(this, "锁机期间不能自由回桌面。白名单应用可以从下面入口打开，白名单外应用会被挡回守门状态。"));

        Button release = Ui.button(this, "申请临时放行");
        release.setOnClickListener(v -> openStudentRoute("temporaryAccess"));
        root.addView(release);

        Button detail = Ui.button(this, "查看守门状态");
        detail.setOnClickListener(v -> openStudentRoute("guardDetail"));
        root.addView(detail);

        Button whitelist = Ui.button(this, "打开白名单应用");
        LinearLayout whitelistContainer = new LinearLayout(this);
        whitelistContainer.setOrientation(LinearLayout.VERTICAL);
        whitelist.setOnClickListener(v -> populateWhitelistEntries(whitelistContainer));
        root.addView(whitelist);
        root.addView(whitelistContainer);


        setContentView(scrollView);
    }

    private void populateWhitelistEntries(LinearLayout root) {
        root.removeAllViews();
        Set<String> packages = store.getGuardWhitelistPackages();
        int added = 0;
        for (String packageName : packages) {
            if (packageName == null
                    || packageName.trim().isEmpty()
                    || getPackageName().equals(packageName)) {
                continue;
            }
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                continue;
            }
            String label = AppCatalog.getLabel(this, packageName);
            Button button = Ui.button(this, "打开 " + label);
            button.setOnClickListener(v -> openAllowedIntent(launchIntent, label, packageName));
            root.addView(button);
            added++;
            if (added >= 6) {
                return;
            }
        }
        if (added == 0) {
            root.addView(Ui.body(this, "当前没有可打开的白名单应用。"));
        }
    }

    private String safeSource() {
        return source == null || source.trim().isEmpty() ? "guard_mode" : source;
    }

    private void finishToMain() {
        showing = false;
        handler.removeCallbacksAndMessages(null);
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void openStudentRoute(String route) {
        Intent intent = new Intent(this, StudentWebActivity.class);
        intent.putExtra("route", route);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void openAllowedIntent(Intent intent, String label, String packageName) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            store.markGuardAllowedLaunch(packageName);
            startActivity(intent);
            if (diagnosticStore != null) {
                diagnosticStore.recordFallbackLockResult(
                        getPackageName(),
                        "守门页允许入口",
                        "已打开" + label
                );
            }
        } catch (RuntimeException exception) {
            Toast.makeText(this, label + "打开失败", Toast.LENGTH_SHORT).show();
            if (diagnosticStore != null) {
                diagnosticStore.recordFallbackLockResult(
                        getPackageName(),
                        "守门页允许入口失败",
                        label + "：" + exception.getClass().getSimpleName() + ": " + exception.getMessage()
                );
            }
        }
    }

    private String formatRemaining(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0) {
            return String.format(Locale.CHINA, "%d 小时 %d 分钟", hours, remainingMinutes);
        }
        if (minutes > 0) {
            return String.format(Locale.CHINA, "%d 分钟 %d 秒", minutes, remainingSeconds);
        }
        return remainingSeconds + " 秒";
    }

    private String formatTime(long at) {
        if (at <= 0L) {
            return "未设置";
        }
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(at));
    }
}
