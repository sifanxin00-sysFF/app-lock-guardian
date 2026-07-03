package com.codex.applockguard.ui;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.UsageStore;
import com.codex.applockguard.util.AppCatalog;
import com.codex.applockguard.util.SystemUsageReader;
import com.codex.applockguard.util.Ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class UsageStatsActivity extends android.app.Activity {
    private UsageStore usageStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usageStore = new UsageStore(this);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        AppLockStore appLockStore = new AppLockStore(this);
        Set<String> lockedPackages = appLockStore.getLockedPackages();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = Ui.root(this);
        root.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scrollView.addView(root);

        root.addView(Ui.title(this, "使用统计"));
        root.addView(Ui.body(this, "这里同时显示两套数据：系统统计来自手机系统，本地记录来自守门应用锁自己的前台检测。系统统计可能有延迟，本地记录只会在监控服务运行时累加。"));

        long systemToday = 0L;
        long systemWeek = 0L;
        long localToday = 0L;
        long localWeek = 0L;
        for (String packageName : lockedPackages) {
            systemToday += SystemUsageReader.getTodayUsage(this, packageName);
            systemWeek += SystemUsageReader.getRollingUsage(this, packageName, 7);
            localToday += usageStore.getTodayUsage(packageName);
            localWeek += usageStore.getRollingUsage(packageName, 7);
        }
        root.addView(Ui.body(this,
                "今日系统统计：" + UsageStore.formatDuration(systemToday)
                        + "\n今日本地记录：" + UsageStore.formatDuration(localToday)
                        + "\n最近 7 天系统统计：" + UsageStore.formatDuration(systemWeek)
                        + "\n最近 7 天本地记录：" + UsageStore.formatDuration(localWeek)));

        List<String> packages = new ArrayList<>(lockedPackages);
        packages.sort(Comparator.comparingLong((String value) ->
                Math.max(SystemUsageReader.getRollingUsage(this, value, 7), usageStore.getRollingUsage(value, 7))
        ).reversed());

        if (packages.isEmpty()) {
            root.addView(Ui.body(this, "当前还没有被锁应用。"));
        } else {
            for (String packageName : packages) {
                root.addView(row(appLockStore, packageName));
            }
        }

        setContentView(scrollView);
    }

    private LinearLayout row(AppLockStore appLockStore, String packageName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText(AppCatalog.getLabel(this, packageName));
        title.setTextSize(18);
        row.addView(title);

        long systemToday = SystemUsageReader.getTodayUsage(this, packageName);
        long systemWeek = SystemUsageReader.getRollingUsage(this, packageName, 7);
        long localToday = usageStore.getTodayUsage(packageName);
        long localWeek = usageStore.getRollingUsage(packageName, 7);
        TextView detail = new TextView(this);
        detail.setText(String.format(
                Locale.CHINA,
                "%s%n今日系统统计：%s%n今日本地记录：%s%n最近 7 天系统统计：%s%n最近 7 天本地记录：%s%n当前规则：%s%n系统 7 天明细：%s%n本地 7 天明细：%s",
                packageName,
                UsageStore.formatDuration(systemToday),
                UsageStore.formatDuration(localToday),
                UsageStore.formatDuration(systemWeek),
                UsageStore.formatDuration(localWeek),
                appLockStore.getRuleSummary(packageName),
                formatSeries(SystemUsageReader.getDailyUsageSeries(this, packageName, 7)),
                formatSeries(usageStore.getDailyUsageSeries(packageName, 7))
        ));
        detail.setTextSize(13);
        row.addView(detail);
        return row;
    }

    private String formatSeries(Map<String, Long> series) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Long> entry : series.entrySet()) {
            if (!first) {
                out.append(" | ");
            }
            first = false;
            String day = entry.getKey();
            out.append(day, 4, 6)
                    .append("-")
                    .append(day, 6, 8)
                    .append(" ")
                    .append(UsageStore.formatDuration(entry.getValue()));
        }
        return out.toString();
    }
}
