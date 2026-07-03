package com.codex.applockguard.model;

import android.graphics.drawable.Drawable;

public final class InstalledApp {
    public final String label;
    public final String packageName;
    public final Drawable icon;

    public InstalledApp(String label, String packageName, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.icon = icon;
    }
}
