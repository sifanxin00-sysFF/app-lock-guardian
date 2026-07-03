package com.codex.applockguard.sync;

import android.content.Context;
import android.os.Build;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.DeviceIdentityStore;
import com.codex.applockguard.data.DiagnosticStore;
import com.codex.applockguard.data.ServiceStateStore;
import com.codex.applockguard.model.InstalledApp;
import com.codex.applockguard.net.StudentApi;
import com.codex.applockguard.util.AppCatalog;
import com.codex.applockguard.util.PermissionUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public final class StudentSyncManager {
    private StudentSyncManager() {
    }

    public static void syncAllAsync(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> syncAll(appContext, reason), "StudentSyncAll").start();
    }

    public static void reportPermissionsAsync(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                JSONArray permissions = buildPermissionsJson(appContext);
                StudentApi.ApiResult result = new StudentApi().reportPermissions(permissions);
                record(appContext, result.ok ? "Student permission sync ok: " + reason : "Student permission sync failed: " + reason);
                syncAll(appContext, reason);
            } catch (Exception exception) {
                record(appContext, "Student permission sync error: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
        }, "StudentPermissionSync").start();
    }

    public static void reportGuardStartedAsync(Context context, int durationMinutes, String localMode, String reason) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                String cloudMode = AppLockStore.GUARD_MODE_BLOCKLIST.equals(localMode) ? "app_lock" : "focus";
                StudentApi.ApiResult result = new StudentApi().reportGuardStart(durationMinutes, cloudMode);
                record(appContext, result.ok ? "Student guard start sync ok: " + reason : "Student guard start sync failed: " + reason);
            } catch (Exception exception) {
                record(appContext, "Student guard start sync error: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            } finally {
                syncAll(appContext, reason);
            }
        }, "StudentGuardStartSync").start();
    }

    public static void reportGuardStoppedAsync(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                StudentApi.ApiResult result = new StudentApi().reportGuardEnd();
                record(appContext, result.ok ? "Student guard end sync ok: " + reason : "Student guard end sync failed: " + reason);
            } catch (Exception exception) {
                record(appContext, "Student guard end sync error: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            } finally {
                syncAll(appContext, reason);
            }
        }, "StudentGuardEndSync").start();
    }

    public static void reportWhitelistChangedAsync(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("apps", buildAppsJson(appContext));
                StudentApi.ApiResult result = new StudentApi().updateWhitelist(body);
                record(appContext, result.ok ? "Student whitelist sync ok: " + reason : "Student whitelist sync failed: " + reason);
            } catch (Exception exception) {
                record(appContext, "Student whitelist sync error: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            } finally {
                syncAll(appContext, reason);
            }
        }, "StudentWhitelistSync").start();
    }

    private static void syncAll(Context context, String reason) {
        try {
            JSONObject body = new JSONObject();
            body.put("reason", reason == null ? "" : reason);
            body.put("device", buildDeviceJson(context));
            body.put("permissions", buildPermissionsJson(context));
            body.put("guard", buildGuardJson(context));
            body.put("apps", buildAppsJson(context));
            body.put("whitelist", buildWhitelistJson(context));
            body.put("service", buildServiceJson(context));
            body.put("diagnostics", buildDiagnosticsJson(context));
            StudentApi.ApiResult result = new StudentApi().sync(body);
            record(context, result.ok ? "Student full sync ok: " + reason : "Student full sync failed: " + reason);
        } catch (Exception exception) {
            record(context, "Student full sync error: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static JSONObject buildDeviceJson(Context context) throws Exception {
        DeviceIdentityStore identityStore = new DeviceIdentityStore(context);
        JSONObject json = new JSONObject();
        json.put("deviceId", identityStore.getDeviceId());
        json.put("deviceSecret", identityStore.getDeviceSecret());
        json.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
        json.put("deviceModel", Build.MODEL);
        json.put("manufacturer", Build.MANUFACTURER);
        json.put("androidVersion", Build.VERSION.RELEASE);
        json.put("sdkInt", Build.VERSION.SDK_INT);
        json.put("packageName", context.getPackageName());
        return json;
    }

    private static JSONArray buildPermissionsJson(Context context) throws Exception {
        PermissionUtils.PermissionReport report = PermissionUtils.getPermissionReport(context);
        JSONArray array = new JSONArray();
        for (PermissionUtils.PermissionItem item : report.permissions) {
            JSONObject json = new JSONObject();
            json.put("key", item.key);
            json.put("name", item.name);
            json.put("description", item.description);
            json.put("status", item.status);
            json.put("required", item.required);
            array.put(json);
        }
        return array;
    }

    private static JSONObject buildGuardJson(Context context) throws Exception {
        AppLockStore store = new AppLockStore(context);
        long startAt = store.getGuardModeStartAt();
        long endAt = store.getGuardModeUntil();
        long now = System.currentTimeMillis();
        boolean active = store.isGuardModeActive();
        int durationMinutes = store.getGuardModeDurationMinutes();
        long totalMs = Math.max(1L, endAt - startAt);
        int progress = active && startAt > 0L && endAt > startAt
                ? (int) Math.max(0L, Math.min(100L, ((now - startAt) * 100L) / totalMs))
                : 0;
        JSONObject json = new JSONObject();
        json.put("guardStatus", active ? "active" : "inactive");
        json.put("mode", store.getGuardModeType());
        json.put("modeName", store.getGuardModeLabel());
        json.put("startTimeMs", startAt);
        json.put("endTimeMs", endAt);
        json.put("durationMinutes", durationMinutes);
        json.put("remainingSeconds", store.getGuardModeRemainingMs() / 1000L);
        json.put("progressPercent", progress);
        json.put("allowedAppCount", store.getGuardWhitelistPackages().size());
        json.put("monitorEnabled", store.isMonitorEnabled());
        json.put("riskShieldEnabled", store.isRiskShieldEnabled());
        return json;
    }

    private static JSONArray buildAppsJson(Context context) throws Exception {
        AppLockStore store = new AppLockStore(context);
        List<InstalledApp> apps = AppCatalog.loadSelectableApps(context);
        JSONArray array = new JSONArray();
        for (InstalledApp app : apps) {
            JSONObject json = new JSONObject();
            json.put("appId", app.packageName);
            json.put("packageName", app.packageName);
            json.put("name", app.label);
            json.put("category", AppCatalog.isRiskPackage(app.packageName) ? "系统入口" : "本机应用");
            json.put("iconUrl", "/app-icon.svg");
            json.put("allowed", store.isGuardWhitelisted(app.packageName));
            json.put("locked", store.isLocked(app.packageName));
            array.put(json);
        }
        return array;
    }

    private static JSONArray buildWhitelistJson(Context context) throws Exception {
        JSONArray array = new JSONArray();
        for (String packageName : new AppLockStore(context).getGuardWhitelistPackages()) {
            array.put(packageName);
        }
        return array;
    }

    private static JSONObject buildServiceJson(Context context) throws Exception {
        ServiceStateStore stateStore = new ServiceStateStore(context);
        JSONObject json = new JSONObject();
        json.put("monitorAlive", stateStore.isMonitorAlive());
        json.put("monitorHeartbeatAt", stateStore.getMonitorHeartbeatAt());
        json.put("accessibilityAlive", stateStore.isAccessibilityAlive());
        json.put("accessibilityHeartbeatAt", stateStore.getAccessibilityHeartbeatAt());
        json.put("lastMonitorEvent", stateStore.getLastMonitorEvent());
        json.put("lastAccessibilityEvent", stateStore.getLastAccessibilityEvent());
        json.put("lastMonitorLoopError", stateStore.getLastMonitorLoopError());
        json.put("lastAccessibilityLoopError", stateStore.getLastAccessibilityLoopError());
        json.put("wakeLockHeld", stateStore.getLastWakeLockHeld());
        json.put("wakeLockEvent", stateStore.getLastWakeLockEvent());
        return json;
    }

    private static JSONObject buildDiagnosticsJson(Context context) throws Exception {
        DiagnosticStore diagnosticStore = new DiagnosticStore(context);
        JSONObject json = new JSONObject();
        json.put("lastForegroundPackage", diagnosticStore.getLastForegroundPackage());
        json.put("lastLockTargetPackage", diagnosticStore.getLastLockTargetPackage());
        json.put("lastLockSource", diagnosticStore.getLastLockSource());
        json.put("lastApiError", diagnosticStore.getLastApiError());
        return json;
    }

    private static void record(Context context, String message) {
        try {
            new ServiceStateStore(context).recordMonitorEvent(message);
        } catch (RuntimeException ignored) {
        }
    }
}
