package com.codex.applockguard.ui;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.codex.applockguard.R;
import com.codex.applockguard.receiver.AppDeviceAdminReceiver;
import com.codex.applockguard.sync.StudentSyncManager;
import com.codex.applockguard.util.PermissionSetupAccess;
import com.codex.applockguard.util.PermissionUtils;
import com.codex.applockguard.util.Ui;

public final class PermissionActivity extends android.app.Activity {
    private static final String ACTION_DEVICE_ADMIN_SETTINGS = "android.settings.DEVICE_ADMIN_SETTINGS";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        StudentSyncManager.reportPermissionsAsync(this, "permission_page_resume");
        render();
    }

    private void render() {
        LinearLayout root = Ui.root(this);
        root.addView(Ui.title(this, "权限与红米设置"));
        root.addView(Ui.body(this, "红米/小米系统会比较积极地限制后台。先开必备权限，守门模式才能识别和挡住应用；再按小米专项加固做保活，锁机才更稳定。"));

        root.addView(Ui.title(this, "必备权限"));
        root.addView(status("使用情况访问", PermissionUtils.hasUsageAccess(this)));
        root.addView(status("无障碍监测服务", PermissionUtils.isAccessibilityEnabled(this)));
        root.addView(status("通知权限", hasNotificationPermission()));
        root.addView(status("忽略电池优化", isIgnoringBatteryOptimization()));
        root.addView(status("设备管理员", PermissionUtils.isDeviceAdminActive(this)));

        root.addView(button("1. 开启使用情况访问", () -> open(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        root.addView(button("2. 开启无障碍监测服务", () -> open(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(button("3. 允许通知", this::requestNotifications));
        root.addView(button("4. 打开本应用通知设置", this::openNotificationSettings));
        root.addView(button("5. 忽略电池优化", this::openBatteryOptimization));

        root.addView(Ui.title(this, "小米专项加固"));
        root.addView(Ui.body(this, "这些项目很多不能被普通 App 自动确认，所以页面会给入口和手动路径。做完后回到这里继续，不要把“需要手动确认”当成已经自动完成。"));
        root.addView(button("6. 打开小米/红米自启动管理", this::openXiaomiAutostart));
        root.addView(Ui.body(this, "自启动：需要手动确认守门应用锁已允许自启动。"));
        root.addView(button("7. 打开小米/红米省电策略", this::openXiaomiBatterySaver));
        root.addView(Ui.body(this, "电池无限制：需要手动把守门应用锁设为无限制，避免后台被系统冻结。"));
        root.addView(Ui.body(this, "后台运行：如果系统页面里有“后台运行”或“后台弹出界面”，请允许守门应用锁。"));
        root.addView(Ui.body(this, "最近任务加锁：打开守门应用锁后进入最近任务，长按或下拉本应用卡片，选择锁定。这个步骤通常只能手动做。"));
        root.addView(Ui.body(this, "通知常驻：保持通知允许，后台保护运行时会显示常驻通知。"));
        root.addView(button("8. 打开本应用系统详情页", this::openAppDetails));
        root.addView(Ui.body(this, "常用手动路径：系统设置 -> 应用设置 -> 应用管理 -> 守门应用锁 -> 省电策略/电池 -> 无限制；系统设置 -> 应用设置 -> 授权管理 -> 自启动管理 -> 允许守门应用锁。"));

        root.addView(button("9. 启用设备管理员", this::requestDeviceAdmin));
        root.addView(button("继续：申请进入应用管理", () -> AdminGateActivity.open(this, AdminGateActivity.DEST_APPS)));

        setContentView(root);
    }

    private Button button(String text, Runnable action) {
        Button button = Ui.button(this, text);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView status(String label, boolean enabled) {
        return Ui.body(this, label + "：" + (enabled ? "已开启" : "未开启"));
    }

    private void open(String action) {
        PermissionSetupAccess.grant(this);
        startActivity(new Intent(action));
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBatteryOptimization() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 34);
        } else {
            Toast.makeText(this, "通知权限已允许，或当前系统不需要单独授权。", Toast.LENGTH_SHORT).show();
        }
    }

    private void openNotificationSettings() {
        PermissionSetupAccess.grant(this);
        Intent intent;
        if (Build.VERSION.SDK_INT >= 26) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
        }
        safeStart(intent);
    }

    private void openBatteryOptimization() {
        PermissionSetupAccess.grant(this);
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openXiaomiAutostart() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ));
        safeStart(intent);
    }

    private void openXiaomiBatterySaver() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        ));
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("package_label", getString(R.string.app_name));
        safeStart(intent);
    }

    private void requestDeviceAdmin() {
        PermissionSetupAccess.grant(this);
        if (PermissionUtils.isDeviceAdminActive(this)) {
            Toast.makeText(this, "设备管理员已经开启。", Toast.LENGTH_SHORT).show();
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
                "开启后，卸载前需要先关闭设备管理员。它只能增加卸载阻力，不能真正阻止卸载。"
        );
        startActivity(intent);
    }

    private void openAppDetails() {
        PermissionSetupAccess.grant(this);
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void safeStart(Intent intent) {
        try {
            PermissionSetupAccess.grant(this);
            startActivity(intent);
        } catch (Exception e) {
            openAppDetails();
            Toast.makeText(this, "当前系统入口可能变了，已打开本应用详情页。请手动设置自启动、电池无限制、通知允许，并在最近任务锁定本应用。", Toast.LENGTH_LONG).show();
        }
    }
}
