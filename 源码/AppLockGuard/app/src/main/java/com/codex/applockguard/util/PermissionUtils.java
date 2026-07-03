package com.codex.applockguard.util;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;

import com.codex.applockguard.data.ServiceStateStore;
import com.codex.applockguard.receiver.AppDeviceAdminReceiver;
import com.codex.applockguard.service.LockAccessibilityService;

import java.util.ArrayList;
import java.util.List;

public final class PermissionUtils {
    private PermissionUtils() {
    }

    public static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode;
        if (Build.VERSION.SDK_INT >= 29) {
            mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.getPackageName()
            );
        } else {
            mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.getPackageName()
            );
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public static boolean isAccessibilityEnabled(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager != null) {
            List<AccessibilityServiceInfo> enabledServices =
                    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabledServices != null) {
                String targetId = new ComponentName(context, LockAccessibilityService.class).flattenToString();
                for (AccessibilityServiceInfo info : enabledServices) {
                    if (info == null || info.getId() == null) {
                        continue;
                    }
                    if (targetId.equalsIgnoreCase(info.getId())) {
                        return true;
                    }
                }
            }
        }

        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }

        ComponentName componentName = new ComponentName(context, LockAccessibilityService.class);
        String fullName = componentName.flattenToString();
        String shortName = componentName.flattenToShortString();
        for (String service : enabled.split(":")) {
            String normalized = service == null ? "" : service.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (fullName.equalsIgnoreCase(normalized) || shortName.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAccessibilityActive(Context context) {
        return isAccessibilityEnabled(context) && new ServiceStateStore(context).isAccessibilityAlive();
    }

    public static boolean isDeviceAdminActive(Context context) {
        DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (manager == null) {
            return false;
        }
        return manager.isAdminActive(new ComponentName(context, AppDeviceAdminReceiver.class));
    }

    public static boolean isCoreProtectionReady(Context context) {
        return hasUsageAccess(context) && isAccessibilityEnabled(context);
    }

    public static String getProtectionIssue(Context context) {
        if (!hasUsageAccess(context)) {
            return "使用情况访问未开启，请先补上。";
        }
        if (!isAccessibilityEnabled(context)) {
            return "无障碍监测未开启，锁窗无法稳定显示。";
        }
        return null;
    }

    public static PermissionReport getPermissionReport(Context context) {
        List<PermissionItem> items = new ArrayList<>();
        items.add(new PermissionItem(
                "accessibility",
                "无障碍权限",
                "用于识别并限制非白名单应用",
                enabledStatus(isAccessibilityEnabled(context)),
                true
        ));
        items.add(new PermissionItem(
                "overlay",
                "悬浮窗权限",
                "用于显示守门提示；当前主要使用无障碍覆盖层",
                enabledStatus(canDrawOverlays(context)),
                true
        ));
        items.add(new PermissionItem(
                "usage_access",
                "使用情况访问",
                "用于判断当前正在使用哪个应用",
                enabledStatus(hasUsageAccess(context)),
                true
        ));
        items.add(new PermissionItem(
                "notification",
                "通知权限",
                "用于保持后台保护常驻并接收状态提示",
                enabledStatus(hasNotificationPermission(context)),
                true
        ));
        items.add(new PermissionItem(
                "battery_background",
                "电池后台",
                "防止守门服务被系统关闭",
                isIgnoringBatteryOptimization(context) ? "enabled" : "recommended",
                false
        ));
        items.add(new PermissionItem(
                "auto_start",
                "自启动权限",
                "保证重启后自动恢复守门；多数小米系统只能手动确认",
                "recommended",
                false
        ));

        int enabledCount = 0;
        int recommendedCount = 0;
        for (PermissionItem item : items) {
            if ("enabled".equals(item.status)) {
                enabledCount++;
            } else if ("recommended".equals(item.status)) {
                recommendedCount++;
            }
        }
        return new PermissionReport(items.size(), enabledCount, recommendedCount, items);
    }

    public static boolean hasNotificationPermission(Context context) {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(context);
    }

    public static boolean isIgnoringBatteryOptimization(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static String enabledStatus(boolean enabled) {
        return enabled ? "enabled" : "disabled";
    }

    public static final class PermissionReport {
        public final int total;
        public final int enabledCount;
        public final int recommendedCount;
        public final List<PermissionItem> permissions;

        PermissionReport(int total, int enabledCount, int recommendedCount, List<PermissionItem> permissions) {
            this.total = total;
            this.enabledCount = enabledCount;
            this.recommendedCount = recommendedCount;
            this.permissions = permissions;
        }
    }

    public static final class PermissionItem {
        public final String key;
        public final String name;
        public final String description;
        public final String status;
        public final boolean required;

        PermissionItem(String key, String name, String description, String status, boolean required) {
            this.key = key;
            this.name = name;
            this.description = description;
            this.status = status;
            this.required = required;
        }
    }
}
