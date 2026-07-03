package com.codex.applockguard.ui;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.util.AppCatalog;
import com.codex.applockguard.util.Ui;

public final class AppRuleActivity extends android.app.Activity {
    public static final String EXTRA_PACKAGE_NAME = "package_name";

    private AppLockStore store;
    private String packageName;
    private EditText dailyLimitInput;
    private EditText startInput;
    private EditText endInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        store = new AppLockStore(this);
        if (packageName == null || !store.isLocked(packageName)) {
            finish();
            return;
        }
        render();
    }

    private void render() {
        LinearLayout root = Ui.root(this);
        root.addView(Ui.title(this, AppCatalog.getLabel(this, packageName) + " 规则"));
        root.addView(Ui.body(this, "每日上限和允许时段会压住临时放行。就算已经批准，如果超时或不在允许时段内，也会重新锁定。"));
        root.addView(Ui.body(this, "当前规则：" + store.getRuleSummary(packageName)));

        dailyLimitInput = new EditText(this);
        dailyLimitInput.setHint("每日上限分钟数，留空表示不开启");
        dailyLimitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (store.hasDailyLimit(packageName)) {
            dailyLimitInput.setText(String.valueOf(store.getDailyLimitMinutes(packageName)));
        }
        root.addView(dailyLimitInput);

        startInput = new EditText(this);
        startInput.setHint("开始时间，例如 19:00");
        startInput.setInputType(InputType.TYPE_CLASS_DATETIME);
        if (store.hasAllowedWindow(packageName)) {
            startInput.setText(AppLockStore.formatMinuteOfDay(store.getWindowStartMinutes(packageName)));
        }
        root.addView(startInput);

        endInput = new EditText(this);
        endInput.setHint("结束时间，例如 21:30");
        endInput.setInputType(InputType.TYPE_CLASS_DATETIME);
        if (store.hasAllowedWindow(packageName)) {
            endInput.setText(AppLockStore.formatMinuteOfDay(store.getWindowEndMinutes(packageName)));
        }
        root.addView(endInput);

        Button save = Ui.button(this, "保存规则");
        save.setOnClickListener(v -> saveRules());
        root.addView(save);

        Button clear = Ui.button(this, "清空规则");
        clear.setOnClickListener(v -> {
            store.clearRules(packageName);
            Toast.makeText(this, "规则已清空。", Toast.LENGTH_SHORT).show();
            render();
        });
        root.addView(clear);

        Button close = Ui.button(this, "返回");
        close.setOnClickListener(v -> finish());
        root.addView(close);

        setContentView(root);
    }

    private void saveRules() {
        String limitText = textOf(dailyLimitInput);
        String startText = textOf(startInput);
        String endText = textOf(endInput);

        try {
            if (limitText.isEmpty()) {
                store.setDailyLimitMinutes(packageName, 0);
            } else {
                store.setDailyLimitMinutes(packageName, Integer.parseInt(limitText));
            }

            if (startText.isEmpty() && endText.isEmpty()) {
                store.clearAllowedWindow(packageName);
            } else {
                if (startText.isEmpty() || endText.isEmpty()) {
                    Toast.makeText(this, "开始和结束时间要一起填写。", Toast.LENGTH_SHORT).show();
                    return;
                }
                store.setAllowedWindow(packageName, parseMinute(startText), parseMinute(endText));
            }

            Toast.makeText(this, "规则已保存。", Toast.LENGTH_SHORT).show();
            render();
        } catch (Exception e) {
            Toast.makeText(this, "格式不正确，请用分钟数和 HH:mm。", Toast.LENGTH_SHORT).show();
        }
    }

    private static String textOf(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private static int parseMinute(String value) {
        String[] parts = value.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("bad_time");
        }
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("bad_time");
        }
        return hour * 60 + minute;
    }
}
