package com.codex.applockguard.util;

import android.content.Context;

import com.codex.applockguard.data.AppLockStore;

public final class PermissionSetupAccess {
    private static final int SETUP_ACCESS_MINUTES = 20;
    private static final String[] REQUIRED_PACKAGES = new String[]{
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.miui.securitycenter",
            "com.miui.permcenter",
            "com.miui.permission",
            "com.miui.appmanager",
            "com.miui.powerkeeper",
            "com.miui.powerkeeper:ui",
            "com.miui.securityadd",
            "com.miui.securitycore",
            "com.miui.guardprovider",
            "com.miui.packageinstaller",
            "com.xiaomi.market",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.lbe.security.miui"
    };

    private PermissionSetupAccess() {
    }

    public static void grant(Context context) {
        AppLockStore store = new AppLockStore(context);
        for (String packageName : REQUIRED_PACKAGES) {
            store.grantSetupAccess(packageName, SETUP_ACCESS_MINUTES);
        }
    }
}
