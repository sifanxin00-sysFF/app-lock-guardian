package com.codex.applockguard.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.codex.applockguard.BuildConfig;
import com.codex.applockguard.R;
import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.DeviceIdentityStore;
import com.codex.applockguard.model.InstalledApp;
import com.codex.applockguard.service.AppMonitorService;
import com.codex.applockguard.sync.StudentSyncManager;
import com.codex.applockguard.util.AppCatalog;
import com.codex.applockguard.util.PermissionSetupAccess;
import com.codex.applockguard.util.PermissionUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public final class StudentWebActivity extends Activity {
    private static final String PERF_TAG = "StudentPerf";
    private static final int MAX_INSTALLED_APPS_FOR_WEB = 220;
    private static final int MAX_ICON_REQUESTS = 60;
    private static final int STUDENT_APP_ICON_SIZE_DP = 48;
    private static final String ICON_CACHE_PREFS = "student_app_icon_cache";
    private WebView webView;
    private int safeTopPx;
    private int safeBottomPx;
    private long activityStartMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityStartMs = SystemClock.elapsedRealtime();
        perf("onCreate_start");
        setupWebView();
        perf("loadUrl_start elapsedMs=" + elapsedSinceCreate());
        webView.loadUrl(initialStudentUrl());
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLockStore store = new AppLockStore(this);
        if (store.isMonitorEnabled() && PermissionUtils.hasUsageAccess(this)) {
            AppMonitorService.start(this);
        }
        StudentSyncManager.syncAllAsync(this, "student_web_resume");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (webView != null) {
            webView.loadUrl(initialStudentUrl());
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        long start = SystemClock.elapsedRealtime();
        webView = new WebView(this);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
                updateSafeAreaFromInsets(insets);
                injectSafeArea();
                return insets;
            });
        }
        webView.addJavascriptInterface(new StudentBridge(this), "AppLockBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage != null) {
                    String message = consoleMessage.message();
                    if (message != null && message.contains(PERF_TAG)) {
                        perf("js_console " + message);
                    }
                }
                return super.onConsoleMessage(consoleMessage);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showLocalError("学生端页面加载失败，请重新安装最新 APK。");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                perf("onPageFinished elapsedMs=" + elapsedSinceCreate());
                injectSafeArea();
            }
        });
        setContentView(webView);
        webView.post(() -> {
            safeTopPx = Math.max(safeTopPx, getSystemBarDimension("status_bar_height"));
            safeBottomPx = Math.max(safeBottomPx, getSystemBarDimension("navigation_bar_height"));
            injectSafeArea();
        });
        perf("webView_init_done durationMs=" + (SystemClock.elapsedRealtime() - start) + " elapsedMs=" + elapsedSinceCreate());
    }

    private long elapsedSinceCreate() {
        return activityStartMs > 0 ? SystemClock.elapsedRealtime() - activityStartMs : 0L;
    }

    private static void perf(String message) {
        if (BuildConfig.DEBUG) {
            Log.d(PERF_TAG, message);
        }
    }

    private void updateSafeAreaFromInsets(WindowInsets insets) {
        if (insets == null) {
            return;
        }
        safeTopPx = Math.max(safeTopPx, insets.getSystemWindowInsetTop());
        safeBottomPx = Math.max(safeBottomPx, insets.getSystemWindowInsetBottom());
    }

    private int getSystemBarDimension(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void injectSafeArea() {
        if (webView == null) {
            return;
        }
        int top = safeTopPx > 0 ? safeTopPx : getSystemBarDimension("status_bar_height");
        int bottom = safeBottomPx > 0 ? Math.min(safeBottomPx, dpOuter(18)) : 0;
        int cssTop = toCssPx(top);
        int cssBottom = toCssPx(bottom);
        String js = "document.documentElement.style.setProperty('--safe-top','" + cssTop + "px');"
                + "document.documentElement.style.setProperty('--safe-bottom','" + cssBottom + "px');";
        webView.evaluateJavascript(js, null);
    }

    private int dpOuter(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int toCssPx(int physicalPx) {
        float density = Math.max(1f, getResources().getDisplayMetrics().density);
        return Math.round(physicalPx / density);
    }

    private void showLocalError(String message) {
        String html = "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<body style=\"font-family:sans-serif;padding:24px;background:#f7f3ec;color:#1f2a24\">"
                + "<h2>页面暂时打不开</h2><p>" + escapeHtml(message) + "</p>"
                + "<button onclick=\"location.reload()\" style=\"padding:12px 16px;border:0;border-radius:12px;background:#245d4c;color:white\">重试</button>"
                + "</body>";
        webView.loadDataWithBaseURL("file:///android_asset/student-app/", html, "text/html", "utf-8", null);
    }

    private String initialStudentUrl() {
        String route = "";
        String requestId = "";
        Intent intent = getIntent();
        if (intent != null) {
            route = intent.getStringExtra("route");
            requestId = intent.getStringExtra("requestId");
        }
        String hash = "";
        if (route != null && route.matches("[a-zA-Z0-9_-]+")) {
            hash = "#" + route;
            if (requestId != null && !requestId.trim().isEmpty()) {
                hash += "/" + Uri.encode(requestId.trim());
            }
        }
        return "file:///android_asset/student-app/index.html" + hash;
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static final class StudentBridge {
        private final Activity activity;
        private final Context appContext;
        private final AppLockStore store;

        StudentBridge(Activity activity) {
            this.activity = activity;
            this.appContext = activity.getApplicationContext();
            this.store = new AppLockStore(appContext);
        }

        @JavascriptInterface
        public String startGuardMode(int durationMinutes) {
            try {
                int minutes = clampMinutes(durationMinutes);
                if (!PermissionUtils.hasUsageAccess(appContext)) {
                    return fail("使用情况访问未开启，请先去权限页开启。");
                }
                if (!PermissionUtils.isAccessibilityEnabled(appContext)) {
                    return fail("无障碍权限未开启，请先去权限页开启。");
                }
                store.clearAllTemporaryPasses();
                store.startGuardMode(AppLockStore.GUARD_MODE_WHITELIST, minutes);
                store.setMonitorEnabled(true);
                boolean started = AppMonitorService.start(appContext);
                if (!started) {
                    store.stopGuardMode();
                    store.setMonitorEnabled(false);
                    StudentSyncManager.syncAllAsync(appContext, "student_web_guard_start_failed");
                    return fail("后台保护启动失败，守门没有生效。");
                }
                StudentSyncManager.reportGuardStartedAsync(appContext, minutes, AppLockStore.GUARD_MODE_WHITELIST, "student_web_start_guard");
                return ok("已开始守门模式 " + minutes + " 分钟", guardStatusJson());
            } catch (Exception exception) {
                return fail("开启守门失败：" + exception.getMessage());
            }
        }

        @JavascriptInterface
        public String endGuardMode() {
            try {
                store.stopGuardMode();
                StudentSyncManager.reportGuardStoppedAsync(appContext, "student_web_end_guard");
                return ok("已结束守门模式", guardStatusJson());
            } catch (Exception exception) {
                return fail("结束守门失败：" + exception.getMessage());
            }
        }

        @JavascriptInterface
        public String updateWhitelist(String payloadJson) {
            try {
                Set<String> packageNames = parsePackageNames(payloadJson);
                int count = store.replaceGuardWhitelistPackages(packageNames);
                StudentSyncManager.reportWhitelistChangedAsync(appContext, "student_web_update_whitelist");
                JSONObject data = new JSONObject();
                data.put("customWhitelistCount", count);
                data.put("whitelistCount", store.getGuardWhitelistPackages().size());
                return ok("白名单已保存", data);
            } catch (Exception exception) {
                return fail("保存白名单失败：" + exception.getMessage());
            }
        }

        @JavascriptInterface
        public String getLocalPermissions() {
            long start = SystemClock.elapsedRealtime();
            try {
                PermissionUtils.PermissionReport report = PermissionUtils.getPermissionReport(appContext);
                JSONObject data = new JSONObject();
                data.put("total", report.total);
                data.put("enabledCount", report.enabledCount);
                data.put("recommendedCount", report.recommendedCount);
                JSONArray permissions = new JSONArray();
                for (PermissionUtils.PermissionItem item : report.permissions) {
                    JSONObject json = new JSONObject();
                    json.put("key", item.key);
                    json.put("name", item.name);
                    json.put("description", item.description);
                    json.put("status", item.status);
                    json.put("required", item.required);
                    permissions.put(json);
                }
                data.put("permissions", permissions);
                String result = ok("已读取本机权限", data);
                perf("bridge getLocalPermissions durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=true jsonChars=" + result.length());
                return result;
            } catch (Exception exception) {
                String result = fail("读取权限失败：" + exception.getMessage());
                perf("bridge getLocalPermissions durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=false error=" + exception.getMessage());
                return result;
            }
        }

        @JavascriptInterface
        public String openPermissionSettings(String permissionKey) {
            try {
                activity.runOnUiThread(() -> openPermission(permissionKey));
                return ok("已打开权限设置", null);
            } catch (Exception exception) {
                return fail("打开权限设置失败：" + exception.getMessage());
            }
        }

        @JavascriptInterface
        public String syncNow() {
            long start = SystemClock.elapsedRealtime();
            try {
                StudentSyncManager.syncAllAsync(appContext, "student_web_manual_sync");
                String result = ok("已开始同步", null);
                perf("bridge syncNow durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=true jsonChars=" + result.length());
                return result;
            } catch (Exception exception) {
                String result = fail("同步失败：" + exception.getMessage());
                perf("bridge syncNow durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=false error=" + exception.getMessage());
                return result;
            }
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            long start = SystemClock.elapsedRealtime();
            try {
                DeviceIdentityStore identityStore = new DeviceIdentityStore(appContext);
                JSONObject data = new JSONObject();
                data.put("deviceId", identityStore.getDeviceId());
                data.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
                data.put("deviceModel", Build.MODEL);
                data.put("manufacturer", Build.MANUFACTURER);
                data.put("androidVersion", Build.VERSION.RELEASE);
                data.put("sdkInt", Build.VERSION.SDK_INT);
                data.put("packageName", appContext.getPackageName());
                try {
                    PackageInfo info = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
                    data.put("appVersionName", info.versionName);
                    if (Build.VERSION.SDK_INT >= 28) {
                        data.put("appVersionCode", info.getLongVersionCode());
                    } else {
                        data.put("appVersionCode", info.versionCode);
                    }
                } catch (Exception ignored) {
                    data.put("appVersionName", "");
                    data.put("appVersionCode", 0);
                }
                String result = ok("已读取设备信息", data);
                perf("bridge getDeviceInfo durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=true jsonChars=" + result.length());
                return result;
            } catch (Exception exception) {
                String result = fail("读取设备信息失败：" + exception.getMessage());
                perf("bridge getDeviceInfo durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=false error=" + exception.getMessage());
                return result;
            }
        }

        @JavascriptInterface
        public String getLocalGuardStatus() {
            long start = SystemClock.elapsedRealtime();
            try {
                String result = ok("已读取本机守门状态", guardStatusJson());
                perf("bridge getLocalGuardStatus durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=true jsonChars=" + result.length());
                return result;
            } catch (Exception exception) {
                String result = fail("读取守门状态失败：" + exception.getMessage());
                perf("bridge getLocalGuardStatus durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=false error=" + exception.getMessage());
                return result;
            }
        }

        @JavascriptInterface
        public String getInstalledApps() {
            long start = SystemClock.elapsedRealtime();
            long scanStart = start;
            long iconMs = 0L;
            int iconCount = 0;
            try {
                List<InstalledApp> apps = AppCatalog.loadSelectableApps(appContext);
                long scanMs = SystemClock.elapsedRealtime() - scanStart;
                Set<String> recommendedPackages = AppCatalog.getRecommendedGuardWhitelist(appContext);
                JSONArray array = new JSONArray();
                int count = 0;
                for (InstalledApp app : apps) {
                    if (count >= MAX_INSTALLED_APPS_FOR_WEB) {
                        break;
                    }
                    JSONObject json = new JSONObject();
                    json.put("name", app.label);
                    json.put("packageName", app.packageName);
                    long iconStart = SystemClock.elapsedRealtime();
                    String iconDataUrl = drawableToDataUrl(app.icon, dp(STUDENT_APP_ICON_SIZE_DP));
                    iconMs += SystemClock.elapsedRealtime() - iconStart;
                    if (!iconDataUrl.isEmpty()) {
                        iconCount++;
                    }
                    json.put("iconDataUrl", iconDataUrl);
                    json.put("iconSource", "package_manager");
                    array.put(json);
                    count++;
                }
                JSONObject data = new JSONObject();
                data.put("apps", array);
                data.put("count", array.length());
                data.put("totalAvailable", apps.size());
                data.put("omittedCount", Math.max(0, apps.size() - array.length()));
                data.put("iconSizeDp", STUDENT_APP_ICON_SIZE_DP);
                data.put("hasMore", apps.size() > array.length());
                String result = ok("已读取本机应用", data);
                perf("bridge getInstalledApps durationMs=" + (SystemClock.elapsedRealtime() - start)
                        + " success=true scanMs=" + scanMs
                        + " totalAvailable=" + apps.size()
                        + " returned=" + array.length()
                        + " iconCount=" + iconCount
                        + " iconMs=" + iconMs
                        + " jsonChars=" + result.length());
                return result;
            } catch (Exception exception) {
                String result = fail("读取本机应用失败：" + exception.getMessage());
                perf("bridge getInstalledApps durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=false error=" + exception.getMessage());
                return result;
            }
        }

        @JavascriptInterface
        public String getInstalledAppsMeta() {
            long start = SystemClock.elapsedRealtime();
            long scanStart = start;
            try {
                List<InstalledApp> apps = AppCatalog.loadSelectableApps(appContext);
                long scanMs = SystemClock.elapsedRealtime() - scanStart;
                Set<String> recommendedPackages = AppCatalog.getRecommendedGuardWhitelist(appContext);
                JSONArray array = new JSONArray();
                int count = 0;
                int cacheCount = 0;
                for (InstalledApp app : apps) {
                    if (count >= MAX_INSTALLED_APPS_FOR_WEB) {
                        break;
                    }
                    long lastUpdateTime = lastUpdateTime(app.packageName);
                    boolean hasIconCache = hasIconCache(app.packageName, lastUpdateTime);
                    if (hasIconCache) {
                        cacheCount++;
                    }
                    JSONObject json = new JSONObject();
                    json.put("name", app.label);
                    json.put("appName", app.label);
                    json.put("packageName", app.packageName);
                    json.put("category", "本机应用");
                    json.put("isSystemApp", isSystemPackage(app.packageName));
                    json.put("isRecommended", recommendedPackages.contains(app.packageName));
                    json.put("hasIconCache", hasIconCache);
                    json.put("lastUpdateTime", lastUpdateTime);
                    array.put(json);
                    count++;
                }
                JSONObject data = new JSONObject();
                data.put("apps", array);
                data.put("count", array.length());
                data.put("totalAvailable", apps.size());
                data.put("omittedCount", Math.max(0, apps.size() - array.length()));
                data.put("iconCacheCount", cacheCount);
                data.put("hasMore", apps.size() > array.length());
                String result = ok("已读取本机应用列表", data);
                perf("bridge getInstalledAppsMeta durationMs=" + (SystemClock.elapsedRealtime() - start)
                        + " success=true scanMs=" + scanMs
                        + " totalAvailable=" + apps.size()
                        + " returned=" + array.length()
                        + " iconCacheCount=" + cacheCount
                        + " jsonChars=" + result.length());
                return result;
            } catch (Exception exception) {
                String result = fail("读取本机应用列表失败：" + exception.getMessage());
                perf("bridge getInstalledAppsMeta durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=false error=" + exception.getMessage());
                return result;
            }
        }

        @JavascriptInterface
        public String getAppIcons(String payloadJson) {
            long start = SystemClock.elapsedRealtime();
            long iconMs = 0L;
            int cacheHits = 0;
            int converted = 0;
            try {
                Set<String> requested = parsePackageNames(payloadJson);
                JSONObject icons = new JSONObject();
                JSONArray missing = new JSONArray();
                int handled = 0;
                for (String packageName : requested) {
                    if (handled >= MAX_ICON_REQUESTS) {
                        break;
                    }
                    handled++;
                    try {
                        long lastUpdateTime = lastUpdateTime(packageName);
                        String iconDataUrl = cachedIcon(packageName, lastUpdateTime);
                        if (iconDataUrl != null && !iconDataUrl.isEmpty()) {
                            cacheHits++;
                        } else {
                            long iconStart = SystemClock.elapsedRealtime();
                            Drawable icon = appContext.getPackageManager().getApplicationIcon(packageName);
                            iconDataUrl = drawableToDataUrl(icon, dp(STUDENT_APP_ICON_SIZE_DP));
                            iconMs += SystemClock.elapsedRealtime() - iconStart;
                            if (iconDataUrl != null && !iconDataUrl.isEmpty()) {
                                converted++;
                                cacheIcon(packageName, lastUpdateTime, iconDataUrl);
                            }
                        }
                        if (iconDataUrl != null && !iconDataUrl.isEmpty()) {
                            icons.put(packageName, iconDataUrl);
                        } else {
                            missing.put(packageName);
                        }
                    } catch (Exception ignored) {
                        missing.put(packageName);
                    }
                }
                JSONObject data = new JSONObject();
                data.put("icons", icons);
                data.put("missing", missing);
                data.put("requestedCount", requested.size());
                data.put("handledCount", handled);
                data.put("cacheHits", cacheHits);
                data.put("convertedCount", converted);
                data.put("iconSizeDp", STUDENT_APP_ICON_SIZE_DP);
                String result = ok("已读取应用图标", data);
                perf("bridge getAppIcons durationMs=" + (SystemClock.elapsedRealtime() - start)
                        + " success=true requested=" + requested.size()
                        + " handled=" + handled
                        + " cacheHits=" + cacheHits
                        + " converted=" + converted
                        + " iconMs=" + iconMs
                        + " jsonChars=" + result.length());
                return result;
            } catch (Exception exception) {
                String result = fail("读取应用图标失败：" + exception.getMessage());
                perf("bridge getAppIcons durationMs=" + (SystemClock.elapsedRealtime() - start) + " success=false error=" + exception.getMessage());
                return result;
            }
        }

        private void openPermission(String key) {
            String permissionKey = key == null ? "" : key.trim();
            PermissionSetupAccess.grant(appContext);
            try {
                if ("accessibility".equals(permissionKey)) {
                    activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    return;
                }
                if ("usage_access".equals(permissionKey)) {
                    activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    return;
                }
                if ("overlay".equals(permissionKey)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    intent.setData(Uri.parse("package:" + appContext.getPackageName()));
                    activity.startActivity(intent);
                    return;
                }
                if ("notification".equals(permissionKey)) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 35);
                        return;
                    }
                    openAppDetails();
                    return;
                }
                if ("battery_background".equals(permissionKey)) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + appContext.getPackageName()));
                    activity.startActivity(intent);
                    return;
                }
                if ("auto_start".equals(permissionKey)) {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    ));
                    activity.startActivity(intent);
                    return;
                }
                openAppDetails();
            } catch (Exception exception) {
                openAppDetails();
                Toast.makeText(appContext, "当前系统入口可能变了，已打开本应用详情页。", Toast.LENGTH_LONG).show();
            }
        }

        private void openAppDetails() {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + appContext.getPackageName()));
            activity.startActivity(intent);
        }

        private JSONObject guardStatusJson() throws Exception {
            JSONObject data = new JSONObject();
            data.put("guardStatus", store.isGuardModeActive() ? "active" : "inactive");
            data.put("mode", store.getGuardModeType());
            data.put("modeName", store.getGuardModeLabel());
            data.put("startTimeMs", store.getGuardModeStartAt());
            data.put("endTimeMs", store.getGuardModeUntil());
            data.put("durationMinutes", store.getGuardModeDurationMinutes());
            data.put("remainingSeconds", store.getGuardModeRemainingMs() / 1000L);
            data.put("whitelistCount", store.getGuardWhitelistPackages().size());
            data.put("monitorEnabled", store.isMonitorEnabled());
            return data;
        }

        private int dp(int value) {
            return Math.round(value * appContext.getResources().getDisplayMetrics().density);
        }

        private long lastUpdateTime(String packageName) {
            try {
                PackageInfo info = appContext.getPackageManager().getPackageInfo(packageName, 0);
                return info.lastUpdateTime;
            } catch (Exception ignored) {
                return 0L;
            }
        }

        private boolean isSystemPackage(String packageName) {
            try {
                ApplicationInfo info = appContext.getPackageManager().getApplicationInfo(packageName, 0);
                return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (Exception ignored) {
                return false;
            }
        }

        private boolean hasIconCache(String packageName, long lastUpdateTime) {
            String cached = cachedIcon(packageName, lastUpdateTime);
            return cached != null && !cached.isEmpty();
        }

        private String cachedIcon(String packageName, long lastUpdateTime) {
            SharedPreferences prefs = appContext.getSharedPreferences(ICON_CACHE_PREFS, Context.MODE_PRIVATE);
            String prefix = "icon:" + packageName + ":";
            long cachedLastUpdate = prefs.getLong(prefix + "lastUpdateTime", -1L);
            if (cachedLastUpdate != lastUpdateTime) {
                return "";
            }
            return prefs.getString(prefix + "dataUrl", "");
        }

        private void cacheIcon(String packageName, long lastUpdateTime, String iconDataUrl) {
            if (iconDataUrl == null || iconDataUrl.isEmpty()) {
                return;
            }
            SharedPreferences prefs = appContext.getSharedPreferences(ICON_CACHE_PREFS, Context.MODE_PRIVATE);
            String prefix = "icon:" + packageName + ":";
            prefs.edit()
                    .putLong(prefix + "lastUpdateTime", lastUpdateTime)
                    .putLong(prefix + "cachedAt", System.currentTimeMillis())
                    .putString(prefix + "dataUrl", iconDataUrl)
                    .apply();
        }

        private static String drawableToDataUrl(Drawable drawable, int sizePx) {
            if (drawable == null || sizePx <= 0) {
                return "";
            }
            try {
                Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, sizePx, sizePx);
                drawable.draw(canvas);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 82, out);
                bitmap.recycle();
                return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Exception ignored) {
                return "";
            }
        }

        private static int clampMinutes(int durationMinutes) {
            if (durationMinutes < 1 || durationMinutes > 24 * 60) {
                throw new IllegalArgumentException("守门时间必须是 1 到 1440 分钟");
            }
            return durationMinutes;
        }

        private static Set<String> parsePackageNames(String payloadJson) throws Exception {
            Set<String> packageNames = new HashSet<>();
            if (payloadJson == null || payloadJson.trim().isEmpty()) {
                return packageNames;
            }
            String text = payloadJson.trim();
            if (text.startsWith("[")) {
                JSONArray array = new JSONArray(text);
                for (int i = 0; i < array.length(); i++) {
                    addPackage(packageNames, array.optString(i, ""));
                }
                return packageNames;
            }
            if (text.startsWith("{")) {
                JSONObject json = new JSONObject(text);
                JSONArray array = json.optJSONArray("packageNames");
                if (array == null) {
                    array = json.optJSONArray("packages");
                }
                if (array == null) {
                    array = json.optJSONArray("apps");
                }
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        Object item = array.opt(i);
                        if (item instanceof JSONObject) {
                            JSONObject app = (JSONObject) item;
                            addPackage(packageNames, app.optString("packageName", app.optString("appId", "")));
                        } else {
                            addPackage(packageNames, String.valueOf(item));
                        }
                    }
                }
                return packageNames;
            }
            for (String part : text.split("[,，\\n\\r\\t ]+")) {
                addPackage(packageNames, part);
            }
            return packageNames;
        }

        private static void addPackage(Set<String> out, String raw) {
            String packageName = raw == null ? "" : raw.trim();
            if (packageName.contains(".") && packageName.length() >= 3) {
                out.add(packageName);
            }
        }

        private static String ok(String message, JSONObject data) {
            try {
                JSONObject json = new JSONObject();
                json.put("success", true);
                json.put("message", message == null || message.trim().isEmpty() ? "操作成功" : message);
                json.put("data", data == null ? new JSONObject() : data);
                return json.toString();
            } catch (Exception exception) {
                return "{\"success\":true,\"message\":\"操作成功\",\"data\":{}}";
            }
        }

        private static String fail(String message) {
            try {
                JSONObject json = new JSONObject();
                json.put("success", false);
                json.put("message", message == null || message.trim().isEmpty() ? "操作失败" : message);
                json.put("data", JSONObject.NULL);
                return json.toString();
            } catch (Exception exception) {
                return "{\"success\":false,\"message\":\"操作失败\",\"data\":null}";
            }
        }
    }
}
