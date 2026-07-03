package com.codex.applockguard.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.model.InstalledApp;
import com.codex.applockguard.sync.StudentSyncManager;
import com.codex.applockguard.util.AppCatalog;
import com.codex.applockguard.util.Ui;

import java.util.List;
import java.util.Locale;

public final class AppListActivity extends android.app.Activity {
    private AppLockStore store;
    private List<InstalledApp> apps;
    private LinearLayout listContainer;
    private String searchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new AppLockStore(this);
        apps = AppCatalog.loadSelectableApps(this);
        StudentSyncManager.syncAllAsync(this, "app_list_open");
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = Ui.root(this);
        root.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scrollView.addView(root);

        root.addView(Ui.title(this, "限制应用 / 白名单"));
        root.addView(Ui.body(this, "这里可以配置限制名单、守门白名单和时间规则。守门锁机模式只允许白名单应用；锁应用模式会限制已选应用。"));
        root.addView(Ui.body(this,
                "锁系统设置和卸载入口：" + (store.isRiskShieldEnabled() ? "已开启" : "已关闭")
                        + "\n系统设置、应用管理、小米安全中心这类入口默认不锁，由监管网页远程控制。"));

        root.addView(Ui.body(this, "白名单里的应用在守门锁机期间可以使用；白名单外应用会被挡住。系统必要应用会自动保留，不会因为清空自定义白名单而删除。"));

        Button allowCommonButton = Ui.button(this, "一键允许微信 / 电话 / 短信 / 监管网页浏览器");
        allowCommonButton.setOnClickListener(v -> allowCommonWhitelist());
        root.addView(allowCommonButton);

        Button clearWhitelistButton = Ui.button(this, "清空自定义白名单");
        clearWhitelistButton.setOnClickListener(v -> clearCustomWhitelist());
        root.addView(clearWhitelistButton);

        EditText searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("搜索应用名或包名，例如微信 / tencent");
        searchBox.setText(searchQuery);
        searchBox.setSelection(searchBox.getText().length());
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString();
                renderFilteredApps();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        root.addView(searchBox);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);
        renderFilteredApps();

        setContentView(scrollView);
    }

    private void renderFilteredApps() {
        if (listContainer == null) {
            return;
        }
        listContainer.removeAllViews();
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (InstalledApp app : apps) {
            if (matches(app, query)) {
                listContainer.addView(appRow(app));
                count++;
            }
        }
        if (count == 0) {
            listContainer.addView(Ui.body(this, "没有匹配应用。"));
        }
    }

    private boolean matches(InstalledApp app, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return app.label.toLowerCase(Locale.ROOT).contains(query)
                || app.packageName.toLowerCase(Locale.ROOT).contains(query);
    }

    private LinearLayout appRow(InstalledApp app) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, Ui.dp(this, 10));
        card.setLayoutParams(cardParams);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        top.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);

        TextView label = new TextView(this);
        label.setText(app.label);
        label.setTextSize(16);
        TextView details = new TextView(this);
        StringBuilder detailText = new StringBuilder(app.packageName);
        if (AppCatalog.isRiskPackage(app.packageName)) {
            detailText.append("\n系统设置和卸载入口");
        }
        if (store.isLocked(app.packageName)) {
            detailText.append("\n").append(store.getRuleSummary(app.packageName));
        }
        if (store.isGuardWhitelisted(app.packageName)) {
            detailText.append("\n守门白名单");
        }
        details.setText(detailText.toString());
        details.setTextSize(12);
        textColumn.addView(label);
        textColumn.addView(details);
        top.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button toggleButton = new Button(this);
        toggleButton.setText(store.isLocked(app.packageName) ? "已限制" : "未限制");
        toggleButton.setOnClickListener(v -> toggle(app.packageName, app.label));
        top.addView(toggleButton);

        card.addView(top);

        Button whitelistButton = Ui.button(this, store.isGuardWhitelisted(app.packageName) ? "移出守门白名单" : "加入守门白名单");
        whitelistButton.setOnClickListener(v -> toggleWhitelist(app.packageName, app.label));
        card.addView(whitelistButton);

        Button ruleButton = Ui.button(this, "编辑时间规则");
        ruleButton.setOnClickListener(v -> {
            if (!store.isLocked(app.packageName)) {
                Toast.makeText(this, "先把这个应用加入限制名单。", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, AppRuleActivity.class);
            intent.putExtra(AppRuleActivity.EXTRA_PACKAGE_NAME, app.packageName);
            startActivity(intent);
        });
        card.addView(ruleButton);

        return card;
    }

    private void toggle(String packageName, String label) {
        boolean next = !store.isLocked(packageName);
        store.setLocked(packageName, next);
        StudentSyncManager.syncAllAsync(this, "locked_apps_changed");
        Toast.makeText(this,
                next ? label + " 已加入限制名单，启动保护或锁应用模式后生效。" : label + " 已取消限制。",
                Toast.LENGTH_SHORT).show();
        renderFilteredApps();
    }

    private void toggleWhitelist(String packageName, String label) {
        boolean next = !store.isGuardWhitelisted(packageName);
        store.setGuardWhitelisted(packageName, next);
        StudentSyncManager.reportWhitelistChangedAsync(this, "local_toggle_whitelist");
        Toast.makeText(this,
                next ? label + " 已加入守门白名单。" : label + " 已移出守门白名单。",
                Toast.LENGTH_SHORT).show();
        renderFilteredApps();
    }

    private void allowCommonWhitelist() {
        int added = store.addRecommendedGuardWhitelist();
        StudentSyncManager.reportWhitelistChangedAsync(this, "local_allow_common_whitelist");
        Toast.makeText(this,
                added > 0 ? "已加入常用白名单 " + added + " 个。" : "常用白名单已经配置好。",
                Toast.LENGTH_SHORT).show();
        renderFilteredApps();
    }

    private void clearCustomWhitelist() {
        int cleared = store.clearCustomGuardWhitelist();
        StudentSyncManager.reportWhitelistChangedAsync(this, "local_clear_custom_whitelist");
        Toast.makeText(this,
                "已清空自定义白名单 " + cleared + " 个。电话、短信、守门应用锁和必要系统入口仍会自动保留。",
                Toast.LENGTH_LONG).show();
        renderFilteredApps();
    }
}
