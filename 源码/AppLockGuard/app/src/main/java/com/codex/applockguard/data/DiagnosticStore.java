package com.codex.applockguard.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class DiagnosticStore {
    private static final String PREFS = "diagnostic_store";
    private static final String KEY_LAST_FOREGROUND_PACKAGE = "last_foreground_package";
    private static final String KEY_LAST_FOREGROUND_AT = "last_foreground_at";
    private static final String KEY_LAST_COMMAND_POLL_AT = "last_command_poll_at";
    private static final String KEY_LAST_COMMAND_RESULT = "last_command_result";
    private static final String KEY_LAST_API_ERROR = "last_api_error";
    private static final String KEY_LAST_LOCK_TARGET_PACKAGE = "last_lock_target_package";
    private static final String KEY_LAST_LOCK_SOURCE = "last_lock_source";
    private static final String KEY_LAST_LOCK_SHOW_AT = "last_lock_show_at";
    private static final String KEY_LAST_LOCK_SHOW_FOREGROUND = "last_lock_show_foreground";
    private static final String KEY_LAST_LOCK_HIDE_AT = "last_lock_hide_at";
    private static final String KEY_LAST_LOCK_HIDE_REASON = "last_lock_hide_reason";
    private static final String KEY_LAST_LOCK_HIDE_FOREGROUND = "last_lock_hide_foreground";
    private static final String KEY_LAST_LOCK_SKIP_AT = "last_lock_skip_at";
    private static final String KEY_LAST_LOCK_SKIP_REASON = "last_lock_skip_reason";
    private static final String KEY_LAST_LOCK_SKIP_FOREGROUND = "last_lock_skip_foreground";
    private static final String KEY_LAST_LOCK_SKIP_TARGET = "last_lock_skip_target";
    private static final String KEY_LAST_MONITOR_BLOCK_CANDIDATE_PACKAGE = "last_monitor_block_candidate_package";
    private static final String KEY_LAST_MONITOR_BLOCK_CANDIDATE_AT = "last_monitor_block_candidate_at";
    private static final String KEY_LAST_FALLBACK_LOCK_PACKAGE = "last_fallback_lock_package";
    private static final String KEY_LAST_FALLBACK_LOCK_ACTION = "last_fallback_lock_action";
    private static final String KEY_LAST_FALLBACK_LOCK_RESULT = "last_fallback_lock_result";
    private static final String KEY_LAST_FALLBACK_LOCK_AT = "last_fallback_lock_at";
    private static final String KEY_LAST_LOCK_ATTEMPT_TARGET = "last_lock_attempt_target";
    private static final String KEY_LAST_LOCK_ATTEMPT_SOURCE = "last_lock_attempt_source";
    private static final String KEY_LAST_LOCK_ATTEMPT_AT = "last_lock_attempt_at";
    private static final String KEY_LAST_LOCK_ATTEMPT_FINAL_FOREGROUND = "last_lock_attempt_final_foreground";
    private static final String KEY_LAST_LOCK_ATTEMPT_CANCEL_REASON = "last_lock_attempt_cancel_reason";
    private static final String KEY_LAST_LOCK_ATTEMPT_CANCEL_AT = "last_lock_attempt_cancel_at";
    private static final String KEY_LAST_LAUNCHER_ACTION = "last_launcher_action";
    private static final String KEY_LAST_LAUNCHER_ACTION_AT = "last_launcher_action_at";
    private static final String KEY_LAST_LAUNCHER_STATE = "last_launcher_state";
    private static final String KEY_LAST_LAUNCHER_DESIRED_VISIBLE = "last_launcher_desired_visible";
    private static final String KEY_LAST_LAUNCHER_VISIBLE_AFTER = "last_launcher_visible_after";
    private static final String KEY_LAST_LAUNCHER_SUCCESS = "last_launcher_success";
    private static final String KEY_LAST_GUARD_DECISION_PACKAGE = "last_guard_decision_package";
    private static final String KEY_LAST_GUARD_DECISION_MODE = "last_guard_decision_mode";
    private static final String KEY_LAST_GUARD_DECISION_RESULT = "last_guard_decision_result";
    private static final String KEY_LAST_GUARD_DECISION_REASON = "last_guard_decision_reason";
    private static final String KEY_LAST_GUARD_DECISION_REMAINING_MS = "last_guard_decision_remaining_ms";
    private static final String KEY_LAST_GUARD_DECISION_AT = "last_guard_decision_at";

    private final SharedPreferences prefs;

    public DiagnosticStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void recordForeground(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return;
        }
        prefs.edit()
                .putString(KEY_LAST_FOREGROUND_PACKAGE, packageName)
                .putLong(KEY_LAST_FOREGROUND_AT, System.currentTimeMillis())
                .apply();
    }

    public void recordCommandPoll(String result) {
        prefs.edit()
                .putLong(KEY_LAST_COMMAND_POLL_AT, System.currentTimeMillis())
                .putString(KEY_LAST_COMMAND_RESULT, safe(result))
                .apply();
    }

    public void recordApiError(String error) {
        prefs.edit().putString(KEY_LAST_API_ERROR, safe(error)).apply();
    }

    public void recordLockShown(String targetPackage, String source, String foregroundPackage) {
        prefs.edit()
                .putString(KEY_LAST_LOCK_TARGET_PACKAGE, safe(targetPackage))
                .putString(KEY_LAST_LOCK_SOURCE, safe(source))
                .putLong(KEY_LAST_LOCK_SHOW_AT, System.currentTimeMillis())
                .putString(KEY_LAST_LOCK_SHOW_FOREGROUND, safe(foregroundPackage))
                .apply();
    }

    public void recordLockHidden(String reason, String foregroundPackage) {
        prefs.edit()
                .putLong(KEY_LAST_LOCK_HIDE_AT, System.currentTimeMillis())
                .putString(KEY_LAST_LOCK_HIDE_REASON, safe(reason))
                .putString(KEY_LAST_LOCK_HIDE_FOREGROUND, safe(foregroundPackage))
                .apply();
    }

    public void recordLockSkipped(String reason, String foregroundPackage, String targetPackage) {
        prefs.edit()
                .putLong(KEY_LAST_LOCK_SKIP_AT, System.currentTimeMillis())
                .putString(KEY_LAST_LOCK_SKIP_REASON, safe(reason))
                .putString(KEY_LAST_LOCK_SKIP_FOREGROUND, safe(foregroundPackage))
                .putString(KEY_LAST_LOCK_SKIP_TARGET, safe(targetPackage))
                .apply();
    }

    public void recordMonitorBlockCandidate(String packageName) {
        prefs.edit()
                .putString(KEY_LAST_MONITOR_BLOCK_CANDIDATE_PACKAGE, safe(packageName))
                .putLong(KEY_LAST_MONITOR_BLOCK_CANDIDATE_AT, System.currentTimeMillis())
                .apply();
    }

    public void recordFallbackLockResult(String packageName, String action, String result) {
        prefs.edit()
                .putString(KEY_LAST_FALLBACK_LOCK_PACKAGE, safe(packageName))
                .putString(KEY_LAST_FALLBACK_LOCK_ACTION, safe(action))
                .putString(KEY_LAST_FALLBACK_LOCK_RESULT, safe(result))
                .putLong(KEY_LAST_FALLBACK_LOCK_AT, System.currentTimeMillis())
                .apply();
    }

    public void recordLockAttempt(String targetPackage, String source) {
        prefs.edit()
                .putString(KEY_LAST_LOCK_ATTEMPT_TARGET, safe(targetPackage))
                .putString(KEY_LAST_LOCK_ATTEMPT_SOURCE, safe(source))
                .putLong(KEY_LAST_LOCK_ATTEMPT_AT, System.currentTimeMillis())
                .remove(KEY_LAST_LOCK_ATTEMPT_CANCEL_REASON)
                .remove(KEY_LAST_LOCK_ATTEMPT_CANCEL_AT)
                .apply();
    }

    public void recordLockAttemptFinalForeground(String targetPackage, String foregroundPackage) {
        prefs.edit()
                .putString(KEY_LAST_LOCK_ATTEMPT_TARGET, safe(targetPackage))
                .putString(KEY_LAST_LOCK_ATTEMPT_FINAL_FOREGROUND, safe(foregroundPackage))
                .apply();
    }

    public void recordLockAttemptCancelled(String targetPackage, String foregroundPackage, String reason) {
        prefs.edit()
                .putString(KEY_LAST_LOCK_ATTEMPT_TARGET, safe(targetPackage))
                .putString(KEY_LAST_LOCK_ATTEMPT_FINAL_FOREGROUND, safe(foregroundPackage))
                .putString(KEY_LAST_LOCK_ATTEMPT_CANCEL_REASON, safe(reason))
                .putLong(KEY_LAST_LOCK_ATTEMPT_CANCEL_AT, System.currentTimeMillis())
                .apply();
    }

    public void recordLauncherAction(String action, String launcherState) {
        recordLauncherAction(action, false, launcherState, false, false);
    }

    public void recordLauncherAction(String action, boolean desiredVisible, String launcherState, boolean visibleAfter, boolean success) {
        prefs.edit()
                .putLong(KEY_LAST_LAUNCHER_ACTION_AT, System.currentTimeMillis())
                .putString(KEY_LAST_LAUNCHER_ACTION, safe(action))
                .putString(KEY_LAST_LAUNCHER_STATE, safe(launcherState))
                .putBoolean(KEY_LAST_LAUNCHER_DESIRED_VISIBLE, desiredVisible)
                .putBoolean(KEY_LAST_LAUNCHER_VISIBLE_AFTER, visibleAfter)
                .putBoolean(KEY_LAST_LAUNCHER_SUCCESS, success)
                .apply();
    }

    public void recordGuardDecision(String packageName, String mode, boolean allowed, String reason, long remainingMs) {
        prefs.edit()
                .putString(KEY_LAST_GUARD_DECISION_PACKAGE, safe(packageName))
                .putString(KEY_LAST_GUARD_DECISION_MODE, safe(mode))
                .putString(KEY_LAST_GUARD_DECISION_RESULT, allowed ? "allowed" : "blocked")
                .putString(KEY_LAST_GUARD_DECISION_REASON, safe(reason))
                .putLong(KEY_LAST_GUARD_DECISION_REMAINING_MS, Math.max(0L, remainingMs))
                .putLong(KEY_LAST_GUARD_DECISION_AT, System.currentTimeMillis())
                .apply();
    }

    public String getLastForegroundPackage() {
        return prefs.getString(KEY_LAST_FOREGROUND_PACKAGE, "");
    }

    public long getLastForegroundAt() {
        return prefs.getLong(KEY_LAST_FOREGROUND_AT, 0L);
    }

    public long getLastCommandPollAt() {
        return prefs.getLong(KEY_LAST_COMMAND_POLL_AT, 0L);
    }

    public String getLastCommandResult() {
        return prefs.getString(KEY_LAST_COMMAND_RESULT, "");
    }

    public String getLastApiError() {
        return prefs.getString(KEY_LAST_API_ERROR, "");
    }

    public String getLastLockTargetPackage() {
        return prefs.getString(KEY_LAST_LOCK_TARGET_PACKAGE, "");
    }

    public String getLastLockSource() {
        return prefs.getString(KEY_LAST_LOCK_SOURCE, "");
    }

    public long getLastLockShowAt() {
        return prefs.getLong(KEY_LAST_LOCK_SHOW_AT, 0L);
    }

    public String getLastLockShowForeground() {
        return prefs.getString(KEY_LAST_LOCK_SHOW_FOREGROUND, "");
    }

    public long getLastLockHideAt() {
        return prefs.getLong(KEY_LAST_LOCK_HIDE_AT, 0L);
    }

    public String getLastLockHideReason() {
        return prefs.getString(KEY_LAST_LOCK_HIDE_REASON, "");
    }

    public String getLastLockHideForeground() {
        return prefs.getString(KEY_LAST_LOCK_HIDE_FOREGROUND, "");
    }

    public long getLastLockSkipAt() {
        return prefs.getLong(KEY_LAST_LOCK_SKIP_AT, 0L);
    }

    public String getLastLockSkipReason() {
        return prefs.getString(KEY_LAST_LOCK_SKIP_REASON, "");
    }

    public String getLastLockSkipForeground() {
        return prefs.getString(KEY_LAST_LOCK_SKIP_FOREGROUND, "");
    }

    public String getLastLockSkipTarget() {
        return prefs.getString(KEY_LAST_LOCK_SKIP_TARGET, "");
    }

    public String getLastMonitorBlockCandidatePackage() {
        return prefs.getString(KEY_LAST_MONITOR_BLOCK_CANDIDATE_PACKAGE, "");
    }

    public long getLastMonitorBlockCandidateAt() {
        return prefs.getLong(KEY_LAST_MONITOR_BLOCK_CANDIDATE_AT, 0L);
    }

    public String getLastFallbackLockPackage() {
        return prefs.getString(KEY_LAST_FALLBACK_LOCK_PACKAGE, "");
    }

    public String getLastFallbackLockAction() {
        return prefs.getString(KEY_LAST_FALLBACK_LOCK_ACTION, "");
    }

    public String getLastFallbackLockResult() {
        return prefs.getString(KEY_LAST_FALLBACK_LOCK_RESULT, "");
    }

    public long getLastFallbackLockAt() {
        return prefs.getLong(KEY_LAST_FALLBACK_LOCK_AT, 0L);
    }

    public String getLastLockAttemptTarget() {
        return prefs.getString(KEY_LAST_LOCK_ATTEMPT_TARGET, "");
    }

    public String getLastLockAttemptSource() {
        return prefs.getString(KEY_LAST_LOCK_ATTEMPT_SOURCE, "");
    }

    public long getLastLockAttemptAt() {
        return prefs.getLong(KEY_LAST_LOCK_ATTEMPT_AT, 0L);
    }

    public String getLastLockAttemptFinalForeground() {
        return prefs.getString(KEY_LAST_LOCK_ATTEMPT_FINAL_FOREGROUND, "");
    }

    public String getLastLockAttemptCancelReason() {
        return prefs.getString(KEY_LAST_LOCK_ATTEMPT_CANCEL_REASON, "");
    }

    public long getLastLockAttemptCancelAt() {
        return prefs.getLong(KEY_LAST_LOCK_ATTEMPT_CANCEL_AT, 0L);
    }

    public String getLastLauncherAction() {
        return prefs.getString(KEY_LAST_LAUNCHER_ACTION, "");
    }

    public long getLastLauncherActionAt() {
        return prefs.getLong(KEY_LAST_LAUNCHER_ACTION_AT, 0L);
    }

    public String getLastLauncherState() {
        return prefs.getString(KEY_LAST_LAUNCHER_STATE, "");
    }

    public boolean getLastLauncherDesiredVisible() {
        return prefs.getBoolean(KEY_LAST_LAUNCHER_DESIRED_VISIBLE, false);
    }

    public boolean getLastLauncherVisibleAfter() {
        return prefs.getBoolean(KEY_LAST_LAUNCHER_VISIBLE_AFTER, false);
    }

    public boolean getLastLauncherSuccess() {
        return prefs.getBoolean(KEY_LAST_LAUNCHER_SUCCESS, false);
    }

    public String getLastGuardDecisionPackage() {
        return prefs.getString(KEY_LAST_GUARD_DECISION_PACKAGE, "");
    }

    public String getLastGuardDecisionMode() {
        return prefs.getString(KEY_LAST_GUARD_DECISION_MODE, "");
    }

    public String getLastGuardDecisionResult() {
        return prefs.getString(KEY_LAST_GUARD_DECISION_RESULT, "");
    }

    public String getLastGuardDecisionReason() {
        return prefs.getString(KEY_LAST_GUARD_DECISION_REASON, "");
    }

    public long getLastGuardDecisionRemainingMs() {
        return prefs.getLong(KEY_LAST_GUARD_DECISION_REMAINING_MS, 0L);
    }

    public long getLastGuardDecisionAt() {
        return prefs.getLong(KEY_LAST_GUARD_DECISION_AT, 0L);
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
