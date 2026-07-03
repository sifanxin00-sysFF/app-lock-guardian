package com.codex.applockguard.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import com.codex.applockguard.data.DiagnosticStore;
import com.codex.applockguard.ui.MainActivity;

public final class LauncherVisibilityManager {
    private LauncherVisibilityManager() {
    }

    public static boolean setLauncherVisible(Context context, boolean visible) {
        repairMainActivity(context);
        PackageManager packageManager = context.getPackageManager();
        ComponentName primary = launcherAlias(context);
        ComponentName alternate = launcherAliasAlt(context);
        String action = visible ? "show_launcher" : "hide_launcher";

        try {
            if (!visible) {
                setComponentState(packageManager, primary, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
                setComponentState(packageManager, alternate, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
                return record(context, action, visible, getLauncherStateSummary(context));
            }

            boolean primaryVisible = isEnabled(context, packageManager, primary);
            boolean alternateVisible = isEnabled(context, packageManager, alternate);

            if (primaryVisible) {
                setComponentState(packageManager, primary, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
                setComponentState(packageManager, alternate, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            } else if (alternateVisible) {
                setComponentState(packageManager, alternate, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
                setComponentState(packageManager, primary, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            } else {
                setComponentState(packageManager, primary, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
                setComponentState(packageManager, alternate, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            }
            return record(context, action, visible, getLauncherStateSummary(context));
        } catch (RuntimeException exception) {
            record(context, action + "_failed", visible, exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return false;
        }
    }

    public static boolean isLauncherVisible(Context context) {
        repairMainActivity(context);
        PackageManager packageManager = context.getPackageManager();
        return isEnabled(context, packageManager, launcherAlias(context))
                || isEnabled(context, packageManager, launcherAliasAlt(context));
    }

    public static void repairMainActivity(Context context) {
        PackageManager packageManager = context.getPackageManager();
        ComponentName mainActivity = new ComponentName(context, MainActivity.class);
        int state = packageManager.getComponentEnabledSetting(mainActivity);
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            setComponentState(packageManager, mainActivity, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        }
    }

    public static String getLauncherStateSummary(Context context) {
        PackageManager packageManager = context.getPackageManager();
        return "主入口 " + stateLabel(context, packageManager, launcherAlias(context))
                + "，备用入口 " + stateLabel(context, packageManager, launcherAliasAlt(context));
    }

    private static boolean isEnabled(Context context, PackageManager packageManager, ComponentName componentName) {
        int state = packageManager.getComponentEnabledSetting(componentName);
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            return isManifestEnabled(context, packageManager, componentName);
        }
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private static void setComponentState(PackageManager packageManager, ComponentName componentName, int state) {
        packageManager.setComponentEnabledSetting(
                componentName,
                state,
                PackageManager.DONT_KILL_APP
        );
    }

    private static String stateLabel(Context context, PackageManager packageManager, ComponentName componentName) {
        int state = packageManager.getComponentEnabledSetting(componentName);
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return "ENABLED";
        }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            return "DISABLED";
        }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            return "DISABLED_USER";
        }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
            return "DISABLED_UNTIL_USED";
        }
        return isManifestEnabled(context, packageManager, componentName) ? "DEFAULT_ENABLED" : "DEFAULT_DISABLED";
    }

    private static boolean isManifestEnabled(Context context, PackageManager packageManager, ComponentName componentName) {
        try {
            return packageManager.getActivityInfo(componentName, PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
        } catch (PackageManager.NameNotFoundException ignored) {
            return componentName.getClassName().equals(context.getPackageName() + ".ui.LauncherActivity");
        }
    }

    private static ComponentName launcherAlias(Context context) {
        return new ComponentName(context.getPackageName(), context.getPackageName() + ".ui.LauncherActivity");
    }

    private static ComponentName launcherAliasAlt(Context context) {
        return new ComponentName(context.getPackageName(), context.getPackageName() + ".ui.LauncherActivityAlt");
    }

    private static boolean record(Context context, String action, boolean desiredVisible, String state) {
        boolean visibleAfter = isLauncherVisible(context);
        boolean success = visibleAfter == desiredVisible;
        new DiagnosticStore(context).recordLauncherAction(action, desiredVisible, state, visibleAfter, success);
        return success;
    }
}
