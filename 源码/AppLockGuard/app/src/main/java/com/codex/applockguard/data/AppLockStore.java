package com.codex.applockguard.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.codex.applockguard.util.AppCatalog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AppLockStore {
    public static final String GUARD_MODE_WHITELIST = "whitelist_mode";
    public static final String GUARD_MODE_BLOCKLIST = "blocklist_mode";

    private static final String PREFS = "app_lock_store";
    private static final String KEY_LOCKED_PACKAGES = "locked_packages";
    private static final String KEY_MONITOR_ENABLED = "monitor_enabled";
    private static final String KEY_UNLOCK_MINUTES = "unlock_minutes";
    private static final String KEY_RISK_SHIELD_ENABLED = "risk_shield_enabled";
    private static final String KEY_RISK_SHIELD_DEFAULT_MIGRATED = "risk_shield_default_migrated";
    private static final String KEY_GUARD_MODE_ENABLED = "guard_mode_enabled";
    private static final String KEY_GUARD_MODE_START_AT = "guard_mode_start_at";
    private static final String KEY_GUARD_MODE_UNTIL = "guard_mode_until";
    private static final String KEY_GUARD_MODE_DURATION_MINUTES = "guard_mode_duration_minutes";
    private static final String KEY_GUARD_MODE_TYPE = "guard_mode_type";
    private static final String KEY_GUARD_MODE_AUTO_DAILY = "guard_mode_auto_daily";
    private static final String KEY_GUARD_WHITELIST_PACKAGES = "guard_whitelist_packages";
    private static final String KEY_RECENT_GUARD_ALLOWED_LAUNCH_PACKAGE = "recent_guard_allowed_launch_package";
    private static final String KEY_RECENT_GUARD_ALLOWED_LAUNCH_AT = "recent_guard_allowed_launch_at";
    private static final String KEY_DAILY_TIME_RANGES_ENABLED = "daily_time_ranges_enabled";
    private static final String KEY_DAILY_TIME_RANGES_DATE = "daily_time_ranges_date";
    private static final String KEY_DAILY_TIME_RANGES_JSON = "daily_time_ranges_json";
    private static final String KEY_DAILY_TIME_RANGES_MODE = "daily_time_ranges_mode";
    private static final String TIME_RANGE_MODE_ALLOWED = "allowed";
    private static final String TIME_RANGE_MODE_BLOCKED = "blocked";
    private static final String PASS_PREFIX = "pass_until_";
    private static final String SETUP_PASS_PREFIX = "setup_pass_until_";
    private static final String LIMIT_PREFIX = "limit_minutes_";
    private static final String WINDOW_START_PREFIX = "window_start_";
    private static final String WINDOW_END_PREFIX = "window_end_";
    private static final int DEFAULT_UNLOCK_MINUTES = 10;

    private final Context context;
    private final SharedPreferences prefs;

    public AppLockStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateRiskShieldDefault();
        migrateGuardWhitelistDefault();
    }

    public Set<String> getLockedPackages() {
        return new HashSet<>(prefs.getStringSet(KEY_LOCKED_PACKAGES, Collections.emptySet()));
    }

    public boolean isLocked(String packageName) {
        return getLockedPackages().contains(packageName);
    }

    public boolean isProtectedPackage(String packageName) {
        return isLocked(packageName) || (isRiskShieldEnabled() && AppCatalog.isRiskPackage(packageName));
    }

    public void setLocked(String packageName, boolean locked) {
        Set<String> packages = getLockedPackages();
        if (locked) {
            packages.add(packageName);
        } else {
            packages.remove(packageName);
            clearTemporaryPass(packageName);
            clearRules(packageName);
        }
        prefs.edit().putStringSet(KEY_LOCKED_PACKAGES, packages).apply();
    }

    public int clearLockedPackages() {
        Set<String> packages = getLockedPackages();
        SharedPreferences.Editor editor = prefs.edit();
        for (String packageName : packages) {
            if (packageName == null || packageName.trim().isEmpty()) {
                continue;
            }
            editor.remove(PASS_PREFIX + packageName);
            editor.remove(LIMIT_PREFIX + packageName);
            editor.remove(WINDOW_START_PREFIX + packageName);
            editor.remove(WINDOW_END_PREFIX + packageName);
        }
        editor.putStringSet(KEY_LOCKED_PACKAGES, new HashSet<>());
        editor.apply();
        return packages.size();
    }

    public boolean isMonitorEnabled() {
        return prefs.getBoolean(KEY_MONITOR_ENABLED, false);
    }

    public void setMonitorEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MONITOR_ENABLED, enabled).apply();
    }

    public int getUnlockMinutes() {
        return prefs.getInt(KEY_UNLOCK_MINUTES, DEFAULT_UNLOCK_MINUTES);
    }

    public void setUnlockMinutes(int minutes) {
        int safeMinutes = Math.max(1, Math.min(minutes, 240));
        prefs.edit().putInt(KEY_UNLOCK_MINUTES, safeMinutes).apply();
    }

    public boolean isRiskShieldEnabled() {
        return prefs.getBoolean(KEY_RISK_SHIELD_ENABLED, false);
    }

    public void setRiskShieldEnabled(boolean enabled) {
        prefs.edit()
                .putBoolean(KEY_RISK_SHIELD_DEFAULT_MIGRATED, true)
                .putBoolean(KEY_RISK_SHIELD_ENABLED, enabled)
                .apply();
    }

    private void migrateRiskShieldDefault() {
        if (prefs.getBoolean(KEY_RISK_SHIELD_DEFAULT_MIGRATED, false)) {
            return;
        }
        prefs.edit()
                .putBoolean(KEY_RISK_SHIELD_DEFAULT_MIGRATED, true)
                .putBoolean(KEY_RISK_SHIELD_ENABLED, false)
                .apply();
    }

    private void migrateGuardWhitelistDefault() {
        Set<String> existing = prefs.getStringSet(KEY_GUARD_WHITELIST_PACKAGES, Collections.emptySet());
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        prefs.edit().putStringSet(KEY_GUARD_WHITELIST_PACKAGES, AppCatalog.getDefaultGuardWhitelist(context)).apply();
    }

    public boolean hasTemporaryPass(String packageName) {
        return System.currentTimeMillis() < prefs.getLong(PASS_PREFIX + packageName, 0L);
    }

    public long getTemporaryPassUntil(String packageName) {
        return prefs.getLong(PASS_PREFIX + packageName, 0L);
    }

    public void grantTemporaryPass(String packageName) {
        grantTemporaryPass(packageName, getUnlockMinutes());
    }

    public void grantTemporaryPass(String packageName, int minutes) {
        int safeMinutes = Math.max(1, Math.min(minutes, 240));
        long until = System.currentTimeMillis() + safeMinutes * 60_000L;
        grantTemporaryPassUntil(packageName, until);
    }

    public void grantTemporaryPassUntil(String packageName, long untilMs) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return;
        }
        prefs.edit().putLong(PASS_PREFIX + packageName, Math.max(untilMs, 0L)).apply();
    }

    public void clearTemporaryPass(String packageName) {
        prefs.edit().remove(PASS_PREFIX + packageName).apply();
    }

    public boolean hasSetupAccess(String packageName) {
        return System.currentTimeMillis() < prefs.getLong(SETUP_PASS_PREFIX + packageName, 0L);
    }

    public void grantSetupAccess(String packageName, int minutes) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return;
        }
        int safeMinutes = Math.max(1, Math.min(minutes, 60));
        long until = System.currentTimeMillis() + safeMinutes * 60_000L;
        prefs.edit().putLong(SETUP_PASS_PREFIX + packageName, until).apply();
    }

    public void clearAllTemporaryPasses() {
        Map<String, ?> all = new HashMap<>(prefs.getAll());
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : all.keySet()) {
            if (key != null && key.startsWith(PASS_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    public void startGuardMode(String mode, int minutes) {
        String safeMode = GUARD_MODE_BLOCKLIST.equals(mode) ? GUARD_MODE_BLOCKLIST : GUARD_MODE_WHITELIST;
        int safeMinutes = Math.max(1, Math.min(minutes, 24 * 60));
        long startAt = System.currentTimeMillis();
        prefs.edit()
                .putBoolean(KEY_GUARD_MODE_ENABLED, true)
                .putLong(KEY_GUARD_MODE_START_AT, startAt)
                .putLong(KEY_GUARD_MODE_UNTIL, startAt + safeMinutes * 60_000L)
                .putInt(KEY_GUARD_MODE_DURATION_MINUTES, safeMinutes)
                .putString(KEY_GUARD_MODE_TYPE, safeMode)
                .putBoolean(KEY_GUARD_MODE_AUTO_DAILY, false)
                .apply();
    }

    public void startDailyTimeRangeGuardMode(int minutes) {
        int safeMinutes = Math.max(1, Math.min(minutes, 24 * 60));
        long startAt = System.currentTimeMillis();
        prefs.edit()
                .putBoolean(KEY_GUARD_MODE_ENABLED, true)
                .putLong(KEY_GUARD_MODE_START_AT, startAt)
                .putLong(KEY_GUARD_MODE_UNTIL, startAt + safeMinutes * 60_000L)
                .putInt(KEY_GUARD_MODE_DURATION_MINUTES, safeMinutes)
                .putString(KEY_GUARD_MODE_TYPE, GUARD_MODE_WHITELIST)
                .putBoolean(KEY_GUARD_MODE_AUTO_DAILY, true)
                .apply();
    }

    public void stopGuardMode() {
        prefs.edit()
                .putBoolean(KEY_GUARD_MODE_ENABLED, false)
                .remove(KEY_GUARD_MODE_START_AT)
                .remove(KEY_GUARD_MODE_UNTIL)
                .remove(KEY_GUARD_MODE_DURATION_MINUTES)
                .remove(KEY_GUARD_MODE_AUTO_DAILY)
                .apply();
    }

    public boolean isGuardModeActive() {
        if (!prefs.getBoolean(KEY_GUARD_MODE_ENABLED, false)) {
            return false;
        }
        if (getGuardModeUntil() <= System.currentTimeMillis()) {
            stopGuardMode();
            return false;
        }
        return true;
    }

    public long getGuardModeUntil() {
        return prefs.getLong(KEY_GUARD_MODE_UNTIL, 0L);
    }

    public long getGuardModeStartAt() {
        return prefs.getLong(KEY_GUARD_MODE_START_AT, 0L);
    }

    public int getGuardModeDurationMinutes() {
        return Math.max(0, prefs.getInt(KEY_GUARD_MODE_DURATION_MINUTES, 0));
    }

    public long getGuardModeRemainingMs() {
        return isGuardModeActive() ? Math.max(0L, getGuardModeUntil() - System.currentTimeMillis()) : 0L;
    }

    public String getGuardModeType() {
        return prefs.getString(KEY_GUARD_MODE_TYPE, GUARD_MODE_WHITELIST);
    }

    public boolean isAutoDailyGuardMode() {
        return isGuardModeActive() && prefs.getBoolean(KEY_GUARD_MODE_AUTO_DAILY, false);
    }

    public String getGuardModeLabel() {
        if (!isGuardModeActive()) {
            return "未开启";
        }
        return GUARD_MODE_BLOCKLIST.equals(getGuardModeType()) ? "锁应用模式" : "锁机模式";
    }

    public Set<String> getGuardWhitelistPackages() {
        Set<String> packages = new HashSet<>(prefs.getStringSet(KEY_GUARD_WHITELIST_PACKAGES, Collections.emptySet()));
        packages.addAll(AppCatalog.getDefaultGuardWhitelist(context));
        return packages;
    }

    public boolean isGuardWhitelisted(String packageName) {
        return getGuardWhitelistPackages().contains(packageName);
    }

    public void setGuardWhitelisted(String packageName, boolean allowed) {
        Set<String> packages = new HashSet<>(prefs.getStringSet(KEY_GUARD_WHITELIST_PACKAGES, Collections.emptySet()));
        if (allowed) {
            packages.add(packageName);
        } else {
            packages.remove(packageName);
        }
        prefs.edit().putStringSet(KEY_GUARD_WHITELIST_PACKAGES, packages).apply();
    }

    public int replaceGuardWhitelistPackages(Set<String> packageNames) {
        Set<String> packages = new HashSet<>();
        if (packageNames != null) {
            for (String raw : packageNames) {
                String packageName = raw == null ? "" : raw.trim();
                if (packageName.contains(".") && packageName.length() >= 3) {
                    packages.add(packageName);
                }
            }
        }
        prefs.edit().putStringSet(KEY_GUARD_WHITELIST_PACKAGES, packages).apply();
        return packages.size();
    }

    public int addRecommendedGuardWhitelist() {
        Set<String> packages = new HashSet<>(prefs.getStringSet(KEY_GUARD_WHITELIST_PACKAGES, Collections.emptySet()));
        int before = packages.size();
        packages.addAll(AppCatalog.getRecommendedGuardWhitelist(context));
        prefs.edit().putStringSet(KEY_GUARD_WHITELIST_PACKAGES, packages).apply();
        return Math.max(0, packages.size() - before);
    }

    public int clearCustomGuardWhitelist() {
        Set<String> packages = new HashSet<>(prefs.getStringSet(KEY_GUARD_WHITELIST_PACKAGES, Collections.emptySet()));
        prefs.edit().putStringSet(KEY_GUARD_WHITELIST_PACKAGES, new HashSet<>()).apply();
        return packages.size();
    }

    public boolean isAllowedInGuardMode(String packageName) {
        if (!isGuardModeActive()) {
            return true;
        }
        if (AppCatalog.isAlwaysAllowedPackage(context, packageName)
                && !(isRiskShieldEnabled() && AppCatalog.isRiskPackage(packageName))) {
            return true;
        }
        if (GUARD_MODE_BLOCKLIST.equals(getGuardModeType())) {
            return !isProtectedPackage(packageName);
        }
        return isGuardWhitelisted(packageName);
    }

    public void markGuardAllowedLaunch(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return;
        }
        prefs.edit()
                .putString(KEY_RECENT_GUARD_ALLOWED_LAUNCH_PACKAGE, packageName.trim())
                .putLong(KEY_RECENT_GUARD_ALLOWED_LAUNCH_AT, System.currentTimeMillis())
                .apply();
    }

    public String getRecentGuardAllowedLaunchPackage(long maxAgeMs) {
        long at = prefs.getLong(KEY_RECENT_GUARD_ALLOWED_LAUNCH_AT, 0L);
        if (at <= 0L || System.currentTimeMillis() - at > maxAgeMs) {
            return null;
        }
        return prefs.getString(KEY_RECENT_GUARD_ALLOWED_LAUNCH_PACKAGE, null);
    }

    public String getGuardDecisionReason(String packageName) {
        if (!isGuardModeActive()) {
            return "守门模式未开启";
        }
        if (isRiskShieldEnabled() && AppCatalog.isRiskPackage(packageName)) {
            return "锁系统设置和卸载入口已开启：命中高风险入口";
        }
        if (AppCatalog.isGuardExitSurface(packageName)) {
            return "守门模式中，桌面和最近任务只作为过渡页，不能自由停留";
        }
        if (AppCatalog.isAlwaysAllowedPackage(context, packageName)) {
            return "系统关键应用默认允许";
        }
        if (GUARD_MODE_BLOCKLIST.equals(getGuardModeType())) {
            return isProtectedPackage(packageName) ? "锁应用模式：命中限制应用" : "锁应用模式：不在限制名单";
        }
        return isGuardWhitelisted(packageName) ? "锁机模式：命中白名单" : "锁机模式：白名单外应用";
    }

    public int getDailyLimitMinutes(String packageName) {
        return Math.max(0, prefs.getInt(LIMIT_PREFIX + packageName, 0));
    }

    public void setDailyLimitMinutes(String packageName, int minutes) {
        if (minutes <= 0) {
            prefs.edit().remove(LIMIT_PREFIX + packageName).apply();
            return;
        }
        prefs.edit().putInt(LIMIT_PREFIX + packageName, Math.min(minutes, 24 * 60)).apply();
    }

    public boolean hasDailyLimit(String packageName) {
        return getDailyLimitMinutes(packageName) > 0;
    }

    public int getWindowStartMinutes(String packageName) {
        return prefs.getInt(WINDOW_START_PREFIX + packageName, -1);
    }

    public int getWindowEndMinutes(String packageName) {
        return prefs.getInt(WINDOW_END_PREFIX + packageName, -1);
    }

    public boolean hasAllowedWindow(String packageName) {
        return getWindowStartMinutes(packageName) >= 0 && getWindowEndMinutes(packageName) >= 0;
    }

    public void setAllowedWindow(String packageName, int startMinutes, int endMinutes) {
        int safeStart = clampMinute(startMinutes);
        int safeEnd = clampMinute(endMinutes);
        prefs.edit()
                .putInt(WINDOW_START_PREFIX + packageName, safeStart)
                .putInt(WINDOW_END_PREFIX + packageName, safeEnd)
                .apply();
    }

    public void clearAllowedWindow(String packageName) {
        prefs.edit()
                .remove(WINDOW_START_PREFIX + packageName)
                .remove(WINDOW_END_PREFIX + packageName)
                .apply();
    }

    public void clearRules(String packageName) {
        prefs.edit()
                .remove(LIMIT_PREFIX + packageName)
                .remove(WINDOW_START_PREFIX + packageName)
                .remove(WINDOW_END_PREFIX + packageName)
                .apply();
    }

    public boolean isWithinAllowedWindow(String packageName, long nowMs) {
        if (!hasAllowedWindow(packageName)) {
            return true;
        }
        int start = getWindowStartMinutes(packageName);
        int end = getWindowEndMinutes(packageName);
        int minuteOfDay = minuteOfDay(nowMs);
        if (start == end) {
            return true;
        }
        if (start < end) {
            return minuteOfDay >= start && minuteOfDay < end;
        }
        return minuteOfDay >= start || minuteOfDay < end;
    }

    public boolean isRuleBlocking(String packageName, long todayUsageMs, long nowMs) {
        if (!isWithinAllowedWindow(packageName, nowMs)) {
            return true;
        }
        int dailyLimitMinutes = getDailyLimitMinutes(packageName);
        return dailyLimitMinutes > 0 && todayUsageMs >= dailyLimitMinutes * 60_000L;
    }

    public boolean shouldBlockPackage(String packageName, long todayUsageMs, long nowMs) {
        if (!isMonitorEnabled()) {
            return false;
        }
        if (hasSetupAccess(packageName)) {
            return false;
        }
        if (isGuardModeActive()) {
            if (isAllowedInGuardMode(packageName)) {
                return false;
            }
            return !hasTemporaryPass(packageName);
        }
        if (!isProtectedPackage(packageName)) {
            return false;
        }
        if (!hasTemporaryPass(packageName)) {
            return true;
        }
        return isRuleBlocking(packageName, todayUsageMs, nowMs);
    }

    public String getBlockingReason(String packageName, long todayUsageMs, long nowMs) {
        if (isGuardModeActive() && !isAllowedInGuardMode(packageName)) {
            return getGuardDecisionReason(packageName) + "，需要监管人批准后才能进入。";
        }
        if (!isWithinAllowedWindow(packageName, nowMs)) {
            return "当前不在允许时间段内。";
        }
        int dailyLimitMinutes = getDailyLimitMinutes(packageName);
        if (dailyLimitMinutes > 0 && todayUsageMs >= dailyLimitMinutes * 60_000L) {
            return "今日使用时长已经达到上限。";
        }
        if (AppCatalog.isRiskPackage(packageName) && isRiskShieldEnabled() && !isLocked(packageName)) {
            return "这是高风险绕过入口，需要监管人批准后才能进入。";
        }
        return "需要先获得监管人批准。";
    }

    public String getRuleSummary(String packageName) {
        StringBuilder summary = new StringBuilder();
        if (hasDailyLimit(packageName)) {
            summary.append("每日上限 ").append(getDailyLimitMinutes(packageName)).append(" 分钟");
        }
        if (hasAllowedWindow(packageName)) {
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append("允许时段 ")
                    .append(formatMinuteOfDay(getWindowStartMinutes(packageName)))
                    .append("-")
                    .append(formatMinuteOfDay(getWindowEndMinutes(packageName)));
        }
        if (summary.length() == 0) {
            return "未设置时间规则";
        }
        return summary.toString();
    }

    public void setDailyTimeRanges(String date, String rangesJson) {
        String safeDate = date == null ? "" : date.trim();
        String safeRanges = rangesJson == null ? "[]" : rangesJson.trim();
        if (safeDate.isEmpty() || safeRanges.isEmpty()) {
            clearDailyTimeRanges();
            return;
        }
        prefs.edit()
                .putBoolean(KEY_DAILY_TIME_RANGES_ENABLED, true)
                .putString(KEY_DAILY_TIME_RANGES_DATE, safeDate)
                .putString(KEY_DAILY_TIME_RANGES_JSON, safeRanges)
                .putString(KEY_DAILY_TIME_RANGES_MODE, TIME_RANGE_MODE_ALLOWED)
                .apply();
    }

    public void setBlockedTimeRanges(String date, String rangesJson) {
        String safeDate = date == null ? "" : date.trim();
        String safeRanges = rangesJson == null ? "[]" : rangesJson.trim();
        if (safeDate.isEmpty() || safeRanges.isEmpty()) {
            clearDailyTimeRanges();
            return;
        }
        prefs.edit()
                .putBoolean(KEY_DAILY_TIME_RANGES_ENABLED, true)
                .putString(KEY_DAILY_TIME_RANGES_DATE, safeDate)
                .putString(KEY_DAILY_TIME_RANGES_JSON, safeRanges)
                .putString(KEY_DAILY_TIME_RANGES_MODE, TIME_RANGE_MODE_BLOCKED)
                .apply();
    }

    public void clearDailyTimeRanges() {
        prefs.edit()
                .putBoolean(KEY_DAILY_TIME_RANGES_ENABLED, false)
                .remove(KEY_DAILY_TIME_RANGES_DATE)
                .remove(KEY_DAILY_TIME_RANGES_JSON)
                .remove(KEY_DAILY_TIME_RANGES_MODE)
                .apply();
    }

    public boolean hasDailyTimeRangesForToday(long nowMs) {
        return prefs.getBoolean(KEY_DAILY_TIME_RANGES_ENABLED, false)
                && todayString(nowMs).equals(prefs.getString(KEY_DAILY_TIME_RANGES_DATE, ""));
    }

    public String getDailyTimeRangesJson() {
        return prefs.getString(KEY_DAILY_TIME_RANGES_JSON, "[]");
    }

    public boolean isDailyTimeRangesBlockedMode() {
        return TIME_RANGE_MODE_BLOCKED.equals(prefs.getString(KEY_DAILY_TIME_RANGES_MODE, TIME_RANGE_MODE_ALLOWED));
    }

    public boolean isWithinDailyTimeRanges(long nowMs) {
        if (!hasDailyTimeRangesForToday(nowMs)) {
            return true;
        }
        int current = minuteOfDay(nowMs);
        try {
            JSONArray ranges = new JSONArray(getDailyTimeRangesJson());
            if (ranges.length() == 0) {
                return false;
            }
            for (int i = 0; i < ranges.length(); i++) {
                JSONObject range = ranges.optJSONObject(i);
                if (range == null) {
                    continue;
                }
                int start = parseClock(range.optString("startTime"));
                int end = parseClock(range.optString("endTime"));
                if (isMinuteWithinRange(current, start, end)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return true;
        }
        return false;
    }

    public boolean isWithinDailyBlockedRanges(long nowMs) {
        if (!hasDailyTimeRangesForToday(nowMs) || !isDailyTimeRangesBlockedMode()) {
            return false;
        }
        int current = minuteOfDay(nowMs);
        try {
            JSONArray ranges = new JSONArray(getDailyTimeRangesJson());
            for (int i = 0; i < ranges.length(); i++) {
                JSONObject range = ranges.optJSONObject(i);
                if (range == null) {
                    continue;
                }
                int start = parseClock(range.optString("startTime"));
                int end = parseClock(range.optString("endTime"));
                if (isMinuteWithinRange(current, start, end)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    public int minutesUntilNextDailyAllowedRange(long nowMs) {
        if (!hasDailyTimeRangesForToday(nowMs)) {
            return 0;
        }
        int current = minuteOfDay(nowMs);
        int best = Integer.MAX_VALUE;
        try {
            JSONArray ranges = new JSONArray(getDailyTimeRangesJson());
            for (int i = 0; i < ranges.length(); i++) {
                JSONObject range = ranges.optJSONObject(i);
                if (range == null) {
                    continue;
                }
                int start = parseClock(range.optString("startTime"));
                int end = parseClock(range.optString("endTime"));
                if (isMinuteWithinRange(current, start, end)) {
                    return 0;
                }
                int diff = start - current;
                if (diff > 0 && diff < best) {
                    best = diff;
                }
            }
        } catch (Exception ignored) {
            return 0;
        }
        if (best != Integer.MAX_VALUE) {
            return Math.max(1, best);
        }
        return Math.max(1, (24 * 60) - current);
    }

    public int minutesUntilCurrentDailyBlockedRangeEnds(long nowMs) {
        if (!hasDailyTimeRangesForToday(nowMs) || !isDailyTimeRangesBlockedMode()) {
            return 0;
        }
        int current = minuteOfDay(nowMs);
        int best = Integer.MAX_VALUE;
        try {
            JSONArray ranges = new JSONArray(getDailyTimeRangesJson());
            for (int i = 0; i < ranges.length(); i++) {
                JSONObject range = ranges.optJSONObject(i);
                if (range == null) {
                    continue;
                }
                int start = parseClock(range.optString("startTime"));
                int end = parseClock(range.optString("endTime"));
                if (!isMinuteWithinRange(current, start, end)) {
                    continue;
                }
                int diff;
                if (start == end) {
                    diff = 24 * 60;
                } else if (start < end) {
                    diff = end - current;
                } else {
                    diff = current >= start ? (24 * 60 - current) + end : end - current;
                }
                if (diff > 0 && diff < best) {
                    best = diff;
                }
            }
        } catch (Exception ignored) {
            return 0;
        }
        return best == Integer.MAX_VALUE ? 0 : Math.max(1, best);
    }

    private static int minuteOfDay(long nowMs) {
        long dayMs = 24L * 60L * 60L * 1000L;
        long offset = (nowMs + java.util.TimeZone.getDefault().getOffset(nowMs)) % dayMs;
        if (offset < 0) {
            offset += dayMs;
        }
        return (int) (offset / 60_000L);
    }

    private static boolean isMinuteWithinRange(int minute, int start, int end) {
        if (start == end) {
            return true;
        }
        if (start < end) {
            return minute >= start && minute < end;
        }
        return minute >= start || minute < end;
    }

    private static int parseClock(String value) {
        if (value == null) {
            return 0;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 2) {
            return 0;
        }
        try {
            int hour = Math.max(0, Math.min(Integer.parseInt(parts[0]), 23));
            int minute = Math.max(0, Math.min(Integer.parseInt(parts[1]), 59));
            return hour * 60 + minute;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String todayString(long nowMs) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(nowMs));
    }

    private static int clampMinute(int value) {
        return Math.max(0, Math.min(value, 23 * 60 + 59));
    }

    public static String formatMinuteOfDay(int minuteOfDay) {
        int hour = Math.max(0, Math.min(minuteOfDay / 60, 23));
        int minute = Math.max(0, Math.min(minuteOfDay % 60, 59));
        return String.format(java.util.Locale.CHINA, "%02d:%02d", hour, minute);
    }
}
