package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Glass TV v3.1: a TV remote-friendly visual entry point for system Settings.
 * This is not a modification of the Panasonic/Android Settings application.
 * Actual configuration screens are launched using official Settings intents.
 * No privileged permissions, accessibility service, root, or persistent overlay.
 */
final class GlassSettings {
    private GlassSettings() { }

    private static int dp(Activity a, float n) {
        return Math.round(a.getResources().getDisplayMetrics().density * n);
    }

    private static GradientDrawable glass(Activity a, boolean selected, float radiusDp) {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                selected
                        ? new int[] {0x805A7DA6, 0x5C293953}
                        : new int[] {0x3BFFFFFF, 0x121B2D47});
        bg.setCornerRadius(dp(a, radiusDp));
        bg.setStroke(dp(a, selected ? 2 : 1),
                selected ? 0xCCF4FAFF : 0x44FFFFFF);
        return bg;
    }

    private static TextView text(Activity a, String value, int sp, int color,
                                 int typefaceStyle) {
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", typefaceStyle));
        t.setIncludeFontPadding(false);
        return t;
    }

    private static void launchSettings(Activity activity, Dialog dialog, String action) {
        dialog.dismiss();
        try {
            activity.startActivity(new Intent(action));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception failure) {
                Toast.makeText(activity, "Settings unavailable on this TV", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static View tile(Activity activity, Dialog dialog, String name,
                             String description, String action, boolean close) {
        LinearLayout entry = new LinearLayout(activity);
        entry.setOrientation(LinearLayout.VERTICAL);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setFocusable(true);
        entry.setClickable(true);
        entry.setPadding(dp(activity, 21), dp(activity, 12),
                         dp(activity, 21), dp(activity, 12));
        entry.setBackground(glass(activity, false, 19));
        entry.setContentDescription(name + ", " + description);
        entry.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(glass(activity, focused, 19));
            v.animate().scaleX(focused ? 1.025f : 1f)
                    .scaleY(focused ? 1.025f : 1f)
                    .setDuration(120L).start();
        });
        TextView title = text(activity, name, 19, Color.WHITE, Typeface.BOLD);
        TextView sub = text(activity, description, 12, 0xC4DEE7F2, Typeface.NORMAL);
        LinearLayout.LayoutParams secondary = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        secondary.topMargin = dp(activity, 8);
        entry.addView(title);
        entry.addView(sub, secondary);
        entry.setOnClickListener(v -> {
            if (close) dialog.dismiss();
            else launchSettings(activity, dialog, action);
        });
        return entry;
    }

    static void show(Activity activity) {
        if (activity.isFinishing()) return;
        final Dialog dialog = new Dialog(activity);
        final DisplayMetrics metrics = activity.getResources().getDisplayMetrics();

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 34), dp(activity, 29),
                         dp(activity, 34), dp(activity, 24));
        GradientDrawable shell = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] {0xF0162539, 0xED09121E});
        shell.setCornerRadius(dp(activity, 29));
        shell.setStroke(dp(activity, 1), 0x8CFFFFFF);
        panel.setBackground(shell);

        TextView eyebrow = text(activity, "PANASONIC  /  GLASS TV", 13,
                0xB8C8D7EA, Typeface.BOLD);
        eyebrow.setLetterSpacing(.16f);
        panel.addView(eyebrow);

        TextView heading = text(activity, "Glass Settings", 35,
                Color.WHITE, Typeface.NORMAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        headingParams.topMargin = dp(activity, 8);
        panel.addView(heading, headingParams);

        TextView hint = text(activity,
                "TV controls, in one quiet place. Select an item to open its system settings.",
                14, 0xC8DCE8F5, Typeface.NORMAL);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(activity, 12);
        hintParams.bottomMargin = dp(activity, 23);
        panel.addView(hint, hintParams);

        String[] titles = {
                "Network & Internet", "Picture & Display",
                "Sound", "Apps",
                "Accessibility", "About TV",
                "System Settings", "Close"
        };
        String[] descriptions = {
                "Wi-Fi and connections", "Picture and screen controls",
                "Audio and output", "Installed applications",
                "Access and interaction", "Device details",
                "All Panasonic TV options", "Return to Glass TV"
        };
        String[] actions = {
                Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS,
                Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS,
                Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS,
                Settings.ACTION_SETTINGS, null
        };
        View first = null;
        for (int i = 0; i < titles.length; i += 2) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) rowParams.topMargin = dp(activity, 13);
            for (int col = 0; col < 2; col++) {
                int index = i + col;
                View option = tile(activity, dialog, titles[index], descriptions[index],
                        actions[index], index == 7);
                if (first == null) first = option;
                LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                        0, dp(activity, 82), 1f);
                if (col == 1) itemParams.leftMargin = dp(activity, 13);
                row.addView(option, itemParams);
            }
            panel.addView(row, rowParams);
        }

        TextView footer = text(activity,
                "System menus retain their original Panasonic design   |   Back to close",
                12, 0x9CD5E0ED, Typeface.NORMAL);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(activity, 24);
        panel.addView(footer, footerParams);
        scroll.addView(panel);
        dialog.setContentView(scroll);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.37f;
            window.setAttributes(attributes);
        }
        dialog.setCanceledOnTouchOutside(true);
        final View firstOption = first;
        dialog.setOnShowListener(ignored -> {
            Window w = dialog.getWindow();
            if (w != null) {
                int width = Math.min(dp(activity, 910), Math.round(metrics.widthPixels * .93f));
                int height = Math.min(dp(activity, 610), Math.round(metrics.heightPixels * .91f));
                w.setLayout(width, height);
            }
            if (firstOption != null) firstOption.requestFocus();
        });
        dialog.show();
    }
}
