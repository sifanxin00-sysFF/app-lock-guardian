package com.codex.applockguard.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    private Ui() {
    }

    public static LinearLayout root(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 20), dp(context, 24), dp(context, 20), dp(context, 20));
        layout.setBackgroundColor(Color.rgb(246, 247, 249));
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return layout;
    }

    public static TextView title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(26);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(20, 23, 26));
        view.setPadding(0, 0, 0, dp(context, 12));
        return view;
    }

    public static TextView body(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(Color.rgb(75, 84, 94));
        view.setLineSpacing(2, 1.1f);
        view.setPadding(0, 0, 0, dp(context, 12));
        return view;
    }

    public static Button button(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(context, 6), 0, dp(context, 6));
        button.setLayoutParams(params);
        return button;
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
