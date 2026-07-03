package com.codex.applockguard.util;

import android.content.Context;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.ServiceStateStore;

public final class ProtectionStatusResolver {
    private ProtectionStatusResolver() {
    }

    public static ProtectionStatusSnapshot resolve(Context context) {
        AppLockStore store = new AppLockStore(context);
        ServiceStateStore serviceStateStore = new ServiceStateStore(context);

        boolean hasUsage = PermissionUtils.hasUsageAccess(context);
        boolean hasAccessibilityEnabled = PermissionUtils.isAccessibilityEnabled(context);
        boolean hasAccessibilityAlive = serviceStateStore.isAccessibilityAlive();
        boolean monitorEnabled = store.isMonitorEnabled();
        boolean monitorAlive = serviceStateStore.isMonitorAlive();
        boolean hasTargets = !store.getLockedPackages().isEmpty() || store.isRiskShieldEnabled();

        if (!hasUsage) {
            return new ProtectionStatusSnapshot(
                    "setup_required",
                    "配置未完成",
                    "还没有开启使用情况访问，正式保护不会生效。",
                    false
            );
        }
        if (!hasTargets) {
            return new ProtectionStatusSnapshot(
                    "setup_required",
                    "配置未完成",
                    "还没有选择要锁定的应用，也没有开启高风险绕过拦截。",
                    false
            );
        }
        if (!monitorEnabled) {
            return new ProtectionStatusSnapshot(
                    "paused",
                    "保护未开启",
                    "当前还没有进入正式保护阶段。",
                    false
            );
        }
        if (!hasAccessibilityEnabled) {
            return new ProtectionStatusSnapshot(
                    "accessibility_missing",
                    "保护已降级",
                    "无障碍没有开启，当前不能稳定显示当场锁层。",
                    false
            );
        }
        if (!hasAccessibilityAlive && !monitorAlive) {
            return new ProtectionStatusSnapshot(
                    "protection_error",
                    "保护异常",
                    "无障碍和监控服务都没有活着，当前保护基本失效。",
                    false
            );
        }
        if (!hasAccessibilityAlive) {
            return new ProtectionStatusSnapshot(
                    "accessibility_degraded",
                    "保护已降级",
                    "无障碍已授权，但当前没有真正连上，锁层显示不可靠。",
                    false
            );
        }
        if (!monitorAlive) {
            return new ProtectionStatusSnapshot(
                    "monitor_dead",
                    "保护异常",
                    "监控服务没有活着，当前状态不可信，需要重新拉起服务。",
                    false
            );
        }
        return new ProtectionStatusSnapshot(
                "running",
                "保护正常",
                "前台识别、无障碍和监控服务都在线，正式拦截应当可用。",
                true
        );
    }
}
