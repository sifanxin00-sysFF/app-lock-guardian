package com.codex.applockguard.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.DeviceIdentityStore;
import com.codex.applockguard.net.ApprovalApi;
import com.codex.applockguard.service.AppMonitorService;
import com.codex.applockguard.util.Ui;

public final class AdminGateActivity extends android.app.Activity {
    public static final String EXTRA_DESTINATION = "destination";
    public static final String DEST_APPS = "apps";
    public static final String DEST_PAUSE = "pause";

    private final ApprovalApi api = new ApprovalApi();
    private DeviceIdentityStore identityStore;
    private String pendingRequestId;
    private String destination;
    private TextView statusView;
    private EditText reasonInput;
    private volatile boolean polling;

    public static void open(Context context, String destination) {
        Intent intent = new Intent(context, AdminGateActivity.class);
        intent.putExtra(EXTRA_DESTINATION, destination);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        identityStore = new DeviceIdentityStore(this);
        destination = getIntent().getStringExtra(EXTRA_DESTINATION);
        render();
    }

    @Override
    protected void onDestroy() {
        polling = false;
        super.onDestroy();
    }

    private void render() {
        LinearLayout root = Ui.root(this);
        root.addView(Ui.title(this, DEST_PAUSE.equals(destination) ? "申请暂停保护" : "申请进入应用管理"));
        root.addView(Ui.body(this, DEST_PAUSE.equals(destination)
                ? "暂停保护也需要监管人在网页里批准。批准后会立即停止保护服务。"
                : "应用管理、时间规则和高风险绕过设置，都需要监管人在网页里批准后才能进入。"));

        reasonInput = new EditText(this);
        reasonInput.setHint("申请说明（可选）");
        reasonInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(reasonInput);

        statusView = Ui.body(this, "还没有发起请求。");
        root.addView(statusView);

        root.addView(requestButton("申请 10 分钟", 10));
        root.addView(requestButton("申请 30 分钟", 30));
        root.addView(requestButton("申请 1 小时", 60));

        Button closeButton = Ui.button(this, "关闭");
        closeButton.setOnClickListener(v -> finish());
        root.addView(closeButton);

        setContentView(root);
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
                api.bootstrap(identityStore.getDeviceId(), identityStore.getDeviceSecret(),
                        android.os.Build.MODEL == null ? "Android Device" : android.os.Build.MODEL);
                ApprovalApi.CreateRequestResult result = api.createRequest(
                        identityStore.getDeviceId(),
                        identityStore.getDeviceSecret(),
                        DEST_PAUSE.equals(destination) ? "pause_protection" : "settings_access",
                        null,
                        DEST_PAUSE.equals(destination) ? "暂停保护" : "进入应用管理",
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
                    ApprovalApi.RequestStatusResult result = api.getRequestStatus(
                            pendingRequestId,
                            identityStore.getDeviceId(),
                            identityStore.getDeviceSecret()
                    );
                    if ("approved".equals(result.status)) {
                        polling = false;
                        runOnUiThread(this::handleApproved);
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

    private void handleApproved() {
        statusView.setText("监管人已批准。");
        if (DEST_PAUSE.equals(destination)) {
            AppLockStore store = new AppLockStore(this);
            store.stopGuardMode();
            store.setMonitorEnabled(false);
            AppMonitorService.stop(this);
            Toast.makeText(this, "保护已暂停。", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Toast.makeText(this, "已获准进入应用管理。", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, AppListActivity.class));
        finish();
    }
}
