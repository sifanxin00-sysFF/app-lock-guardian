package com.codex.applockguard.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class UsageStore {
    private static final String PREFS = "usage_store";
    private static final String PREFIX = "usage_";

    private final SharedPreferences prefs;

    public UsageStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void addUsage(String packageName, long durationMs) {
        if (packageName == null || durationMs <= 0L) {
            return;
        }
        long safeDuration = Math.min(durationMs, 10_000L);
        String key = buildKey(dateKey(0), packageName);
        long current = prefs.getLong(key, 0L);
        prefs.edit().putLong(key, current + safeDuration).apply();
    }

    public long getTodayUsage(String packageName) {
        return getUsage(packageName, 0);
    }

    public long getUsage(String packageName, int daysAgo) {
        return prefs.getLong(buildKey(dateKey(daysAgo), packageName), 0L);
    }

    public long getRollingUsage(String packageName, int days) {
        long total = 0L;
        for (int i = 0; i < days; i++) {
            total += getUsage(packageName, i);
        }
        return total;
    }

    public Map<String, Long> getTodayUsageForPackages(Set<String> packageNames) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String packageName : packageNames) {
            result.put(packageName, getTodayUsage(packageName));
        }
        return result;
    }

    public Map<String, Long> getRollingUsageForPackages(Set<String> packageNames, int days) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String packageName : packageNames) {
            result.put(packageName, getRollingUsage(packageName, days));
        }
        return result;
    }

    public Map<String, Long> getDailyUsageSeries(String packageName, int days) {
        Map<String, Long> series = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            series.put(dateKey(i), getUsage(packageName, i));
        }
        return series;
    }

    public static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.CHINA, "%d小时%d分钟", hours, minutes);
        }
        if (minutes > 0) {
            return String.format(Locale.CHINA, "%d分钟%d秒", minutes, seconds);
        }
        return String.format(Locale.CHINA, "%d秒", seconds);
    }

    private static String dateKey(int daysAgo) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(calendar.getTime());
    }

    private static String buildKey(String dateKey, String packageName) {
        return PREFIX + dateKey + "_" + packageName;
    }
}
