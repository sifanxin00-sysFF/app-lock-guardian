package com.codex.applockguard.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.codex.applockguard.data.DeviceIdentityStore;
import com.codex.applockguard.net.AppConfig;
import com.codex.applockguard.net.ApprovalApi;
import com.codex.applockguard.util.Ui;

import java.util.Locale;

public final class GuardianWebSetupActivity extends android.app.Activity {
    private final ApprovalApi api = new ApprovalApi();
    private TextView statusView;
    private TextView activationView;
    private String dashboardUrl = AppConfig.BASE_URL;
    private String activationUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        render();
        refresh();
    }

    private void render() {
        LinearLayout root = Ui.root(this);
        root.addView(Ui.title(this, "审批网页设置"));
        root.addView(Ui.body(this, "让监管人在另一台设备上打开激活链接。激活后，监管人可以在网页里批准 10 分钟、30 分钟、1 小时，或者拒绝。"));

        statusView = Ui.body(this, "正在检查后端状态...");
        activationView = Ui.body(this, "激活链接：加载中");
        root.addView(statusView);
        root.addView(activationView);

        Button refreshButton = Ui.button(this, "刷新网页状态");
        refreshButton.setOnClickListener(v -> refresh());
        root.addView(refreshButton);

        Button openDashboard = Ui.button(this, "打开审批网页");
        openDashboard.setOnClickListener(v -> openUrl(dashboardUrl));
        root.addView(openDashboard);

        Button openActivation = Ui.button(this, "打开首次激活链接");
        openActivation.setOnClickListener(v -> {
            if (activationUrl == null || activationUrl.isEmpty()) {
                Toast.makeText(this, "当前没有激活链接，说明监管人已经完成激活。", Toast.LENGTH_SHORT).show();
                return;
            }
            openUrl(activationUrl);
        });
        root.addView(openActivation);

        setContentView(root);
    }

    private void refresh() {
        statusView.setText("正在检查后端状态...");
        activationView.setText("激活链接：加载中");

        DeviceIdentityStore identityStore = new DeviceIdentityStore(this);
        String deviceId = identityStore.getDeviceId();
        String deviceSecret = identityStore.getDeviceSecret();
        String deviceName = android.os.Build.MODEL == null ? "Android Device" : android.os.Build.MODEL;

        new Thread(() -> {
            try {
                ApprovalApi.BootstrapResult result = api.bootstrap(deviceId, deviceSecret, deviceName);
                dashboardUrl = result.dashboardUrl == null || result.dashboardUrl.isEmpty() ? AppConfig.BASE_URL : result.dashboardUrl;
                activationUrl = result.activationUrl == null ? "" : result.activationUrl;
                runOnUiThread(() -> {
                    statusView.setText(result.guardianConfigured
                            ? "审批网页状态：监管人已激活，可以直接登录审批。"
                            : "审批网页状态：尚未激活，请把激活链接发给监管人。");
                    activationView.setText(result.guardianConfigured
                            ? "激活链接：已完成，无需再次使用"
                            : String.format(Locale.getDefault(), "激活链接：%s", activationUrl));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusView.setText("审批网页状态：连接失败，请检查网络后重试。");
                    activationView.setText(String.format(Locale.getDefault(), "后端地址：%s", AppConfig.BASE_URL));
                });
            }
        }).start();
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}
