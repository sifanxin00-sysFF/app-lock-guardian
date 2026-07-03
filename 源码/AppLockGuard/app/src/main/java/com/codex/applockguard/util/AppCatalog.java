package com.codex.applockguard.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

import com.codex.applockguard.model.InstalledApp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AppCatalog {
    private static final String[][] RISK_PACKAGES = new String[][]{
            {"com.android.settings", "系统设置"},
            {"com.miui.securitycenter", "小米安全中心"},
            {"com.miui.permcenter", "小米权限中心"},
            {"com.miui.appmanager", "小米应用管理"},
            {"com.miui.powerkeeper", "小米省电策略"},
            {"com.miui.cleanmaster", "小米清理"},
            {"com.miui.securityadd", "小米安全组件"},
            {"com.miui.guardprovider", "小米安全守护"},
            {"com.miui.packageinstaller", "小米安装器"},
            {"com.google.android.packageinstaller", "Google 安装器"},
            {"com.android.packageinstaller", "系统安装器"},
            {"com.android.permissioncontroller", "系统权限管理"},
            {"com.google.android.permissioncontroller", "Google 权限管理"},
            {"com.xiaomi.market", "小米应用商店"},
            {"com.android.vending", "Google Play 商店"},
            {"com.miui.voiceassist", "小爱同学"},
            {"com.miui.personalassistant", "智能助理"}
    };

    private static final String[][] CORE_DISPLAY_PACKAGES = new String[][]{
            {"com.tencent.mm", "微信"},
            {"com.tencent.mobileqq", "QQ"},
            {"com.android.contacts", "联系人"},
            {"com.android.dialer", "电话"},
            {"com.android.incallui", "电话"},
            {"com.google.android.dialer", "电话"},
            {"com.android.mms", "短信"},
            {"com.google.android.apps.messaging", "短信"},
            {"com.android.camera", "相机"},
            {"com.miui.camera", "相机"},
            {"com.android.browser", "浏览器"},
            {"com.android.chrome", "Chrome"},
            {"com.miui.gallery", "相册"},
            {"com.android.calendar", "日历"},
            {"com.miui.notes", "笔记"}
    };

    private AppCatalog() {
    }

    public static List<InstalledApp> loadSelectableApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(intent, 0);
        Map<String, InstalledApp> apps = new LinkedHashMap<>();
        String self = context.getPackageName();

        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (self.equals(packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(pm);
            apps.put(packageName, new InstalledApp(
                    label == null ? packageName : label.toString(),
                    packageName,
                    info.loadIcon(pm)
            ));
        }

        for (String[] riskPackage : RISK_PACKAGES) {
            addKnownPackage(pm, apps, self, riskPackage[0], riskPackage[1]);
        }

        for (String[] displayPackage : CORE_DISPLAY_PACKAGES) {
            addKnownPackage(pm, apps, self, displayPackage[0], displayPackage[1]);
        }

        List<InstalledApp> result = new ArrayList<>(apps.values());
        Collections.sort(result, Comparator
                .comparingInt((InstalledApp app) -> isPriorityDisplayPackage(app.packageName) ? 0 : 1)
                .thenComparing(app -> app.label.toLowerCase(Locale.ROOT)));
        return result;
    }

    private static boolean isPriorityDisplayPackage(String packageName) {
        for (String[] entry : CORE_DISPLAY_PACKAGES) {
            if (entry[0].equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static void addKnownPackage(
            PackageManager pm,
            Map<String, InstalledApp> apps,
            String self,
            String packageName,
            String fallbackLabel
    ) {
        if (apps.containsKey(packageName) || self.equals(packageName)) {
            return;
        }
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            apps.put(packageName, new InstalledApp(
                    label == null ? fallbackLabel : label.toString(),
                    packageName,
                    pm.getApplicationIcon(info)
            ));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    public static boolean isRiskPackage(String packageName) {
        for (String[] entry : RISK_PACKAGES) {
            if (entry[0].equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> getDefaultGuardWhitelist(Context context) {
        Set<String> packages = new HashSet<>();
        packages.add(context.getPackageName());
        packages.add("com.android.systemui");
        packages.add("com.miui.home");
        packages.add("com.android.launcher");
        packages.add("com.android.quicksearchbox");
        packages.add("com.android.settings");
        addResolvedPackage(context, packages, new Intent(Intent.ACTION_DIAL));
        addResolvedPackage(context, packages, new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")));
        addResolvedPackage(context, packages, new Intent(Intent.ACTION_VIEW, Uri.parse("https://approval.example.com")));
        addCurrentInputMethod(context, packages);
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        addResolvedPackage(context, packages, home);
        return packages;
    }

    public static Set<String> getRecommendedGuardWhitelist(Context context) {
        Set<String> packages = new HashSet<>();
        addIfInstalled(context, packages, "com.tencent.mm");
        addResolvedPackage(context, packages, new Intent(Intent.ACTION_DIAL));
        addResolvedPackage(context, packages, new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")));
        addResolvedPackage(context, packages, new Intent(Intent.ACTION_VIEW, Uri.parse("https://approval.example.com")));
        return packages;
    }

    public static boolean isAlwaysAllowedPackage(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        if (context.getPackageName().equals(packageName)) {
            return true;
        }
        if ("com.android.systemui".equals(packageName)
                || "com.miui.home".equals(packageName)
                || "com.android.launcher".equals(packageName)
                || "com.android.quicksearchbox".equals(packageName)) {
            return true;
        }
        return getDefaultGuardWhitelist(context).contains(packageName);
    }

    public static boolean isGuardExitSurface(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        return "com.miui.home".equals(packageName)
                || "com.android.launcher".equals(packageName)
                || "com.android.quicksearchbox".equals(packageName)
                || "com.android.systemui".equals(packageName);
    }

    private static void addResolvedPackage(Context context, Set<String> packages, Intent intent) {
        try {
            ResolveInfo info = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null && info.activityInfo != null && info.activityInfo.packageName != null) {
                packages.add(info.activityInfo.packageName);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void addCurrentInputMethod(Context context, Set<String> packages) {
        try {
            String inputMethod = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD
            );
            ComponentName componentName = ComponentName.unflattenFromString(inputMethod);
            if (componentName != null && componentName.getPackageName() != null) {
                packages.add(componentName.getPackageName());
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void addIfInstalled(Context context, Set<String> packages, String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            packages.add(packageName);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    public static List<String> getInstalledRiskLabels(Context context) {
        List<String> labels = new ArrayList<>();
        for (String[] entry : RISK_PACKAGES) {
            try {
                labels.add(getLabel(context, entry[0]));
            } catch (Exception ignored) {
            }
        }
        return labels;
    }

    public static String getLabel(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    public static void openApp(Context context, String packageName) {
        if ("com.android.settings".equals(packageName)) {
            Intent settingsIntent = new Intent(android.provider.Settings.ACTION_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settingsIntent);
            return;
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return;
        }
        Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        settingsIntent.setData(android.net.Uri.parse("package:" + packageName));
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(settingsIntent);
    }
}
