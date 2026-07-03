package com.codex.applockguard.util;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;

import java.util.List;

public final class ForegroundAppReader {
    private static final long EVENTS_LOOKBACK_MS = 30_000L;
    private static final long STATS_LOOKBACK_MS = 2 * 60_000L;

    private ForegroundAppReader() {
    }

    public static String getForegroundPackage(Context context) {
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        UsageEvents events = manager.queryEvents(now - EVENTS_LOOKBACK_MS, now);
        if (events == null) {
            return getFallbackPackage(manager, now);
        }
        UsageEvents.Event event = new UsageEvents.Event();
        String foreground = null;
        long foregroundTime = 0L;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            boolean resumed = type == UsageEvents.Event.MOVE_TO_FOREGROUND;
            if (Build.VERSION.SDK_INT >= 29) {
                resumed = resumed || type == UsageEvents.Event.ACTIVITY_RESUMED;
            }
            if (resumed && event.getTimeStamp() >= foregroundTime) {
                foreground = event.getPackageName();
                foregroundTime = event.getTimeStamp();
            }
        }
        if (foreground != null) {
            return foreground;
        }
        return getFallbackPackage(manager, now);
    }

    private static String getFallbackPackage(UsageStatsManager manager, long now) {
        List<UsageStats> stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - STATS_LOOKBACK_MS,
                now
        );
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        String candidate = null;
        long candidateTime = 0L;
        for (UsageStats stat : stats) {
            if (stat == null || stat.getPackageName() == null || stat.getPackageName().trim().isEmpty()) {
                continue;
            }
            long lastSeen = stat.getLastTimeUsed();
            if (Build.VERSION.SDK_INT >= 29) {
                lastSeen = Math.max(lastSeen, stat.getLastTimeVisible());
            }
            if (lastSeen >= candidateTime) {
                candidate = stat.getPackageName();
                candidateTime = lastSeen;
            }
        }
        return candidate;
    }
}
