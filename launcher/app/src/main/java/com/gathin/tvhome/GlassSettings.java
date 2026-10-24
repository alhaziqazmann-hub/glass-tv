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
 * Glass TV v3.1.1: compact, remote-friendly Settings hub.
 * Android/Panasonic system setting screens are not altered.
 * No accessibility changes, system modifications or background services.
 */
final class GlassSettings {
    private GlassSettings() { }

    private static int dp(Activity activity, float value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private static GradientDrawable rounded(Activity activity, boolean focused, float radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                focused ? new int[] {0xB04C729C, 0x87314968}
                        : new int[] {0x44D7E9FF, 0x246C92B5});
        drawable.setCornerRadius(dp(activity, radius));
        drawable.setStroke(dp(activity, focused ? 2 : 1),
                focused ? 0xEDF4FAFF : 0x55C5E3FF);
        return drawable;
    }

    private static TextView label(Activity activity, String content, int size,
                                   int color, int style) {
        TextView view = new TextView(activity);
        view.setText(content);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private static void launch(Activity activity, Dialog dialog, String action) {
        dialog.dismiss();
        try {
            activity.startActivity(new Intent(action));
        } catch (ActivityNotFoundException | SecurityException failure) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception unavailable) {
                Toast.makeText(activity, "Settings unavailable on this TV", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static View option(Activity activity, Dialog dialog, String title,
                               String subtitle, String action, boolean close) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 12), dp(activity, 8));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(rounded(activity, false, 16));
        card.setContentDescription(title + ", " + subtitle);
        card.setOnFocusChangeListener((view, focus) -> {
            view.setBackground(rounded(activity, focus, 16));
            view.animate().scaleX(focus ? 1.015f : 1f)
                    .scaleY(focus ? 1.015f : 1f).setDuration(110).start();
        });

        TextView heading = label(activity, title, 17, Color.WHITE, Typeface.BOLD);
        TextView caption = label(activity, subtitle, 11, 0xC6DCEAF8, Typeface.NORMAL);
        LinearLayout.LayoutParams captionLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        captionLayout.topMargin = dp(activity, 4);
        card.addView(heading);
        card.addView(caption, captionLayout);
        card.setOnClickListener(view -> {
            if (close) dialog.dismiss();
            else launch(activity, dialog, action);
        });
        return card;
    }

    static void show(Activity activity) {
        if (activity.isFinishing()) return;
        Dialog dialog = new Dialog(activity);
        DisplayMetrics screen = activity.getResources().getDisplayMetrics();

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        GradientDrawable shell = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] {0xE51B293B, 0xDE0B1421});
        shell.setCornerRadius(dp(activity, 24));
        shell.setStroke(dp(activity, 1), 0x99E9F7FF);
        scroll.setBackground(shell);
        scroll.setClipToOutline(true);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 24), dp(activity, 20),
                dp(activity, 24), dp(activity, 18));

        TextView eyebrow = label(activity, "PANASONIC  /  GLASS TV", 11,
                0xBED7E7F7, Typeface.BOLD);
        eyebrow.setLetterSpacing(.12f);
        content.addView(eyebrow);

        TextView heading = label(activity, "Glass Settings", 29,
                Color.WHITE, Typeface.NORMAL);
        LinearLayout.LayoutParams hParams = new LinearLayout.LayoutParams(-1, -2);
        hParams.topMargin = dp(activity, 6);
        content.addView(heading, hParams);

        TextView hint = label(activity, "TV controls, beautifully organized.", 12,
                0xCAD9E7F5, Typeface.NORMAL);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(activity, 7);
        hintParams.bottomMargin = dp(activity, 16);
        content.addView(hint, hintParams);

        String[] names = {
                "Network & Internet", "Picture & Display", "Sound", "Apps",
                "Accessibility", "About TV", "System Settings", "Close"
        };
        String[] captions = {
                "Wi-Fi and connections", "Picture and screen controls", "Audio and output",
                "Installed applications", "Access and interaction", "Device details",
                "All Panasonic TV options", "Return to Glass TV"
        };
        String[] actions = {
                Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS,
                Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS,
                Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS,
                Settings.ACTION_SETTINGS, null
        };
        View firstCard = null;
        for (int i = 0; i < names.length; i += 2) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rowParams.topMargin = dp(activity, 9);
            for (int col = 0; col < 2; col++) {
                int index = i + col;
                View card = option(activity, dialog, names[index], captions[index],
                        actions[index], index == 7);
                if (firstCard == null) firstCard = card;
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        0, dp(activity, 65), 1f);
                if (col == 1) cardParams.leftMargin = dp(activity, 10);
                row.addView(card, cardParams);
            }
            content.addView(row, rowParams);
        }

        TextView footer = label(activity,
                "Press Back to close  •  System screens use Panasonic's original UI",
                10, 0xAFC8DDED, Typeface.NORMAL);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = dp(activity, 14);
        content.addView(footer, footerParams);
        scroll.addView(content);
        dialog.setContentView(scroll);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.40f;
            window.setAttributes(attrs);
        }
        dialog.setCanceledOnTouchOutside(true);
        View focusTarget = firstCard;
        dialog.setOnShowListener(ignored -> {
            Window popup = dialog.getWindow();
            if (popup != null) {
                // Sized by the actual TV display. Content scrolls if the display is unusually small.
                int width = Math.min(dp(activity, 820), Math.round(screen.widthPixels * .85f));
                int height = Math.min(dp(activity, 485), Math.round(screen.heightPixels * .84f));
                popup.setLayout(width, height);
                popup.setGravity(Gravity.CENTER);
            }
            if (focusTarget != null) focusTarget.requestFocus();
        });
        dialog.show();
    }
}
