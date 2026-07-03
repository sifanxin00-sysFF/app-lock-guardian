package com.codex.applockguard.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class ServiceStateStore {
    private static final String PREFS = "service_state_store";
    private static final String KEY_ACCESSIBILITY_HEARTBEAT_AT = "accessibility_heartbeat_at";
    private static final String KEY_MONITOR_HEARTBEAT_AT = "monitor_heartbeat_at";
    private static final String KEY_LAST_MONITOR_EVENT = "last_monitor_event";
    private static final String KEY_LAST_MONITOR_EVENT_AT = "last_monitor_event_at";
    private static final String KEY_LAST_ACCESSIBILITY_EVENT = "last_accessibility_event";
    private static final String KEY_LAST_ACCESSIBILITY_EVENT_AT = "last_accessibility_event_at";
    private static final String KEY_LAST_MONITOR_LOOP_ERROR = "last_monitor_loop_error";
    private static final String KEY_LAST_MONITOR_LOOP_ERROR_AT = "last_monitor_loop_error_at";
    private static final String KEY_LAST_ACCESSIBILITY_LOOP_ERROR = "last_accessibility_loop_error";
    private static final String KEY_LAST_ACCESSIBILITY_LOOP_ERROR_AT = "last_accessibility_loop_error_at";
    private static final String KEY_LAST_RESTART_ATTEMPT = "last_restart_attempt";
    private static final String KEY_LAST_RESTART_ATTEMPT_AT = "last_restart_attempt_at";
    private static final String KEY_LAST_WAKE_LOCK_HELD = "last_wake_lock_held";
    private static final String KEY_LAST_WAKE_LOCK_EVENT = "last_wake_lock_event";
    private static final String KEY_LAST_WAKE_LOCK_EVENT_AT = "last_wake_lock_event_at";
    private static final long ACCESSIBILITY_ALIVE_MS = 12_000L;
    private static final long MONITOR_ALIVE_MS = 8_000L;

    private final SharedPreferences prefs;

    public ServiceStateStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void touchAccessibilityHeartbeat() {
        prefs.edit().putLong(KEY_ACCESSIBILITY_HEARTBEAT_AT, System.currentTimeMillis()).apply();
    }

    public void touchMonitorHeartbeat() {
        prefs.edit().putLong(KEY_MONITOR_HEARTBEAT_AT, System.currentTimeMillis()).apply();
    }

    public void recordMonitorEvent(String event) {
        prefs.edit()
                .putString(KEY_LAST_MONITOR_EVENT, safe(event))
                .putLong(KEY_LAST_MONITOR_EVENT_AT, System.currentTimeMillis())
                .apply();
    }

    public void recordAccessibilityEvent(String event) {
        prefs.edit()
                .putString(KEY_LAST_ACCESSIBILITY_EVENT, safe(event))
                .putLong(KEY_LAST_ACCESSIBILITY_EVENT_AT, System.currentTimeMillis())
                .apply();
    }

    public void recordMonitorLoopError(String error) {
        prefs.edit()
                .putString(KEY_LAST_MONITOR_LOOP_ERROR, safe(error))
                .putLong(KEY_LAST_MONITOR_LOOP_ERROR_AT, System.currentTimeMillis())
                .apply();
    }

    public void recordAccessibilityLoopError(String error) {
        prefs.edit()
                .putString(KEY_LAST_ACCESSIBILITY_LOOP_ERROR, safe(error))
                .putLong(KEY_LAST_ACCESSIBILITY_LOOP_ERROR_AT, System.currentTimeMillis())
                .apply();
    }

    public void recordRestartAttempt(String event) {
        prefs.edit()
                .putString(KEY_LAST_RESTART_ATTEMPT, safe(event))
                .putLong(KEY_LAST_RESTART_ATTEMPT_AT, System.currentTimeMillis())
                .apply();
    }

    public void recordWakeLockState(boolean held, String event) {
        prefs.edit()
                .putBoolean(KEY_LAST_WAKE_LOCK_HELD, held)
                .putString(KEY_LAST_WAKE_LOCK_EVENT, safe(event))
                .putLong(KEY_LAST_WAKE_LOCK_EVENT_AT, System.currentTimeMillis())
                .apply();
    }

    public boolean isAccessibilityAlive() {
        long last = prefs.getLong(KEY_ACCESSIBILITY_HEARTBEAT_AT, 0L);
        return last > 0L && System.currentTimeMillis() - last <= ACCESSIBILITY_ALIVE_MS;
    }

    public long getAccessibilityHeartbeatAt() {
        return prefs.getLong(KEY_ACCESSIBILITY_HEARTBEAT_AT, 0L);
    }

    public boolean isMonitorAlive() {
        long last = prefs.getLong(KEY_MONITOR_HEARTBEAT_AT, 0L);
        return last > 0L && System.currentTimeMillis() - last <= MONITOR_ALIVE_MS;
    }

    public long getMonitorHeartbeatAt() {
        return prefs.getLong(KEY_MONITOR_HEARTBEAT_AT, 0L);
    }

    public String getLastMonitorEvent() {
        return prefs.getString(KEY_LAST_MONITOR_EVENT, "");
    }

    public long getLastMonitorEventAt() {
        return prefs.getLong(KEY_LAST_MONITOR_EVENT_AT, 0L);
    }

    public String getLastAccessibilityEvent() {
        return prefs.getString(KEY_LAST_ACCESSIBILITY_EVENT, "");
    }

    public long getLastAccessibilityEventAt() {
        return prefs.getLong(KEY_LAST_ACCESSIBILITY_EVENT_AT, 0L);
    }

    public String getLastMonitorLoopError() {
        return prefs.getString(KEY_LAST_MONITOR_LOOP_ERROR, "");
    }

    public long getLastMonitorLoopErrorAt() {
        return prefs.getLong(KEY_LAST_MONITOR_LOOP_ERROR_AT, 0L);
    }

    public String getLastAccessibilityLoopError() {
        return prefs.getString(KEY_LAST_ACCESSIBILITY_LOOP_ERROR, "");
    }

    public long getLastAccessibilityLoopErrorAt() {
        return prefs.getLong(KEY_LAST_ACCESSIBILITY_LOOP_ERROR_AT, 0L);
    }

    public String getLastRestartAttempt() {
        return prefs.getString(KEY_LAST_RESTART_ATTEMPT, "");
    }

    public long getLastRestartAttemptAt() {
        return prefs.getLong(KEY_LAST_RESTART_ATTEMPT_AT, 0L);
    }

    public boolean getLastWakeLockHeld() {
        return prefs.getBoolean(KEY_LAST_WAKE_LOCK_HELD, false);
    }

    public String getLastWakeLockEvent() {
        return prefs.getString(KEY_LAST_WAKE_LOCK_EVENT, "");
    }

    public long getLastWakeLockEventAt() {
        return prefs.getLong(KEY_LAST_WAKE_LOCK_EVENT_AT, 0L);
    }

    public void clearMonitorHeartbeat() {
        prefs.edit().remove(KEY_MONITOR_HEARTBEAT_AT).apply();
    }

    public void clearAccessibilityHeartbeat() {
        prefs.edit().remove(KEY_ACCESSIBILITY_HEARTBEAT_AT).apply();
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
