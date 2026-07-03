package com.codex.applockguard.util;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SystemUsageReader {
    private SystemUsageReader() {
    }

    public static long getTodayUsage(Context context, String packageName) {
        long now = System.currentTimeMillis();
        return getForegroundMs(context, packageName, startOfDay(now), now);
    }

    public static long getRollingUsage(Context context, String packageName, int days) {
        if (days <= 0) {
            return 0L;
        }
        long total = 0L;
        for (int i = 0; i < days; i++) {
            long[] range = dayRange(i);
            total += getForegroundMs(context, packageName, range[0], range[1]);
        }
        return total;
    }

    public static Map<String, Long> getDailyUsageSeries(Context context, String packageName, int days) {
        Map<String, Long> series = new LinkedHashMap<>();
        if (days <= 0) {
            return series;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        for (int i = days - 1; i >= 0; i--) {
            long[] range = dayRange(i);
            series.put(format.format(new Date(range[0])), getForegroundMs(context, packageName, range[0], range[1]));
        }
        return series;
    }

    public static long getForegroundMs(Context context, String packageName, long startMs, long endMs) {
        if (packageName == null || packageName.trim().isEmpty() || endMs <= startMs) {
            return 0L;
        }
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return 0L;
        }
        List<UsageStats> stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs);
        if (stats == null || stats.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (UsageStats stat : stats) {
            if (!packageName.equals(stat.getPackageName())) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                total += Math.max(stat.getTotalTimeVisible(), stat.getTotalTimeInForeground());
            } else {
                total += stat.getTotalTimeInForeground();
            }
        }
        return total;
    }

    public static long getLastVisibleAt(Context context, String packageName, long startMs, long endMs) {
        if (packageName == null || packageName.trim().isEmpty() || endMs <= startMs) {
            return 0L;
        }
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return 0L;
        }
        List<UsageStats> stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs);
        if (stats == null || stats.isEmpty()) {
            return 0L;
        }
        long latest = 0L;
        for (UsageStats stat : stats) {
            if (stat == null || !packageName.equals(stat.getPackageName())) {
                continue;
            }
            long lastSeen = stat.getLastTimeUsed();
            if (Build.VERSION.SDK_INT >= 29) {
                lastSeen = Math.max(lastSeen, stat.getLastTimeVisible());
            }
            latest = Math.max(latest, lastSeen);
        }
        return latest;
    }

    private static long startOfDay(long timeMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeMs);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static long[] dayRange(int daysAgo) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo);
        long start = calendar.getTimeInMillis();
        long end;
        if (daysAgo == 0) {
            end = System.currentTimeMillis();
        } else {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
            end = calendar.getTimeInMillis();
        }
        return new long[]{start, end};
    }
}
