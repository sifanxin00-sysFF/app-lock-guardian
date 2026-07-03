package com.codex.applockguard.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.net.ApprovalApi;
import com.codex.applockguard.util.AppCatalog;
import com.codex.applockguard.util.SystemUsageReader;
import com.codex.applockguard.util.Ui;

import java.util.Calendar;
import java.util.Locale;

public final class LockActivity extends android.app.Activity {
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    private static final String TAG = "GuardLockPage";
    private static volatile String showingPackage;

    private final ApprovalApi api = new ApprovalApi();
    private String targetPackage;
    private String pendingRequestId;
    private TextView statusView;
    private EditText reasonInput;
    private volatile boolean polling;

    public static void open(Context context, String packageName) {
        Intent intent = new Intent(context, LockActivity.class);
        intent.putExtra(EXTRA_PACKAGE_NAME, packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    public static boolean isShowingForPackage(String packageName) {
        return packageName != null && packageName.equals(showingPackage);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        targetPackage = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        Log.i(TAG, "opened from LockActivity targetPackage=" + targetPackage);
        AppLockStore lockStore = new AppLockStore(this);
        if (lockStore.isGuardModeActive()) {
            GuardModeActivity.open(this, targetPackage, "lock_activity_guard_redirect");
            finish();
            return;
        }
        if (targetPackage == null || !lockStore.isProtectedPackage(targetPackage)) {
            finish();
            return;
        }

        long now = System.currentTimeMillis();
        long todayUsage = SystemUsageReader.getTodayUsage(this, targetPackage);
        if (!lockStore.shouldBlockPackage(targetPackage, todayUsage, now)) {
            finish();
            return;
        }
        showingPackage = targetPackage;

        String label = AppCatalog.getLabel(this, targetPackage);
        LinearLayout root = Ui.root(this);
        root.addView(Ui.title(this, label + " 当前不可使用"));
        root.addView(Ui.body(this, lockStore.isLocked(targetPackage)
                ? "原因：家长已限制"
                : "原因：" + lockStore.getBlockingReason(targetPackage, todayUsage, now)));

        reasonInput = new EditText(this);
        reasonInput.setHint("申请说明（可选）");
        reasonInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(reasonInput);

        statusView = Ui.body(this, "还没有发起请求。");
        root.addView(statusView);

        root.addView(requestButton("申请临时放行", 30));

        Button leave = Ui.button(this, "返回桌面");
        leave.setOnClickListener(v -> goHome());
        root.addView(leave);

        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        polling = false;
        if (targetPackage != null && targetPackage.equals(showingPackage)) {
            showingPackage = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (targetPackage != null) {
            showingPackage = targetPackage;
        }
    }

    @Override
    protected void onPause() {
        if (targetPackage != null && targetPackage.equals(showingPackage)) {
            showingPackage = null;
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    private Button requestButton(String text, int minutes) {
        Button button = Ui.button(this, text);
        button.setOnClickListener(v -> createRequest(minutes));
        return button;
    }

    private void createRequest(int minutes) {
        statusView.setText("正在提交请求...");
        String reason = reasonInput.getText() == null ? "" : reasonInput.getText().toString().trim();
        new Thread(() -> {
            try {
                ApprovalApi.CreateRequestResult result = api.createStudentReleaseRequest(
                        targetPackage,
                        AppCatalog.getLabel(this, targetPackage),
                        minutes,
                        reason
                );
                pendingRequestId = result.requestId;
                runOnUiThread(() -> statusView.setText("请求已提交，等待监管人审批。"));
                startPolling();
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("提交失败，请检查网络后重试。"));
            }
        }).start();
    }

    private void startPolling() {
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
                        runOnUiThread(() -> handleApproved(result.approvedMinutes, result.approvedMode, result.guardianNote));
                        return;
                    }
                    if ("rejected".equals(result.status)) {
                        polling = false;
                        runOnUiThread(() -> statusView.setText("监管人已经拒绝这次请求。"));
                        return;
                    }
                    runOnUiThread(() -> statusView.setText("请求处理中，等待监管人审批..."));
                    Thread.sleep(3000L);
                } catch (Exception e) {
                    runOnUiThread(() -> statusView.setText("轮询失败，稍后会继续重试。"));
                    try {
                        Thread.sleep(4000L);
                    } catch (InterruptedException ignored) {
                        polling = false;
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }).start();
    }

    private void handleApproved(int approvedMinutes, String approvedMode, String guardianNote) {
        AppLockStore lockStore = new AppLockStore(this);
        if ("today".equalsIgnoreCase(approvedMode)) {
            lockStore.grantTemporaryPassUntil(targetPackage, endOfToday());
        } else {
            int safeMinutes = approvedMinutes > 0 ? approvedMinutes : 10;
            lockStore.grantTemporaryPassUntil(targetPackage, System.currentTimeMillis() + safeMinutes * 60_000L);
        }

        if (guardianNote != null && !guardianNote.trim().isEmpty()) {
            statusView.setText(String.format(Locale.CHINA, "监管人已批准：%s", guardianNote));
        } else {
            statusView.setText("监管人已批准，正在打开应用。");
        }
        AppCatalog.openApp(this, targetPackage);
        finish();
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

    private void goHome() {
        moveTaskToBack(true);
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        finish();
    }
}
