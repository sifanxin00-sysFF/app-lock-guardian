package com.codex.applockguard.util;

import android.content.Context;

import com.codex.applockguard.data.DiagnosticStore;

public final class LockForegroundVerifier {
    private static final long RECENT_DIAGNOSTIC_FOREGROUND_MS = 1_500L;

    private LockForegroundVerifier() {
    }

    public static String resolveCurrentForeground(Context context, DiagnosticStore diagnosticStore) {
        return resolveCurrentForeground(context, diagnosticStore, 0L);
    }

    public static String resolveCurrentForeground(Context context, DiagnosticStore diagnosticStore, long minDiagnosticAt) {
        long now = System.currentTimeMillis();
        if (diagnosticStore != null) {
            String diagnosticPackage = diagnosticStore.getLastForegroundPackage();
            long diagnosticAt = diagnosticStore.getLastForegroundAt();
            if (diagnosticPackage != null
                    && !diagnosticPackage.trim().isEmpty()
                    && diagnosticAt > 0L
                    && diagnosticAt >= minDiagnosticAt
                    && now - diagnosticAt <= RECENT_DIAGNOSTIC_FOREGROUND_MS) {
                return diagnosticPackage;
            }
        }
        return ForegroundAppReader.getForegroundPackage(context);
    }

    public static boolean isStillOnTarget(Context context, DiagnosticStore diagnosticStore, String targetPackage) {
        return targetPackage != null && targetPackage.equals(resolveCurrentForeground(context, diagnosticStore));
    }

    public static String describe(String packageName) {
        return packageName == null || packageName.trim().isEmpty() ? "unknown_foreground" : packageName;
    }
}
