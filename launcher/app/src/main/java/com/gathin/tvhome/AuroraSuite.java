package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Glass TV v3.2 Aurora Pack.
 * Adds time-aware liquid glass, a lighter app dock and a remote-operated
 * Personalize section, leaving PrayerWidget, AnimatedPosters and MP4 playback alone.
 * Never changes Android system settings, default HOME or accessibility services.
 */
final class AuroraSuite {
    private static final String PREF_FILE = "glass_aurora_v32";
    private static final String THEME = "theme";
    private static final String GLOW = "glow";
    private static final String COMPACT = "compact_dock";
    private static final String PRAYER = "show_prayer";
    private static final String[] MODES = {"Auto", "Day", "Night"};
    private static final String[] GLOWS = {"Soft", "Balanced", "Vivid"};

    private final Activity activity;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View tint;
    private final View accent;
    private int lastFocusColor = 0xFF6CA9FF;
    private boolean attached;
    private boolean dockCompactLast;
    private final Runnable timeRefresh = new Runnable() {
        @Override public void run() {
            if (!attached || activity.isFinishing()) return;
            applyTheme();
            main.postDelayed(this, 5 * 60_000L);
        }
    };

    static void attach(Activity activity, FrameLayout root) {
        if (activity != null && root != null) new AuroraSuite(activity, root);
    }

    private AuroraSuite(Activity owner, FrameLayout container) {
        activity = owner;
        root = container;
        grid = owner.findViewById(R.id.grid);
        prefs = owner.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        dockCompactLast = prefs.getBoolean(COMPACT, true);

        // Placed behind all interactive TV controls, video previews and prayer widget.
        // The existing wallpaper is later inserted at index zero, below these layers.
        tint = passiveLayer();
        accent = passiveLayer();
        root.addView(tint, 0, new FrameLayout.LayoutParams(-1, -1));
        root.addView(accent, 1, new FrameLayout.LayoutParams(-1, -1));
        applyTheme();
        applyGlow();

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            int position = positionOf(newFocus);
            if (position < 0 || grid == null || !(grid.getAdapter() instanceof AppAdapter)) return;
            int color = ((AppAdapter) grid.getAdapter()).ambientColorAt(position);
            if (color == lastFocusColor) return;
            lastFocusColor = color;
            applyGlow();
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::applyDock);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                attached = true;
                main.removeCallbacks(timeRefresh);
                main.post(timeRefresh);
            }
            @Override public void onViewDetachedFromWindow(View view) {
                attached = false;
                main.removeCallbacks(timeRefresh);
            }
        });
        if (root.isAttachedToWindow()) {
            attached = true;
            main.post(timeRefresh);
        }

        // MainActivity sets the stock GlassSettings click later in onCreate.
        // Posting here safely replaces only the normal click after setup finishes.
        // Long-press and all system HOME/remote behaviour remain unchanged.
        root.post(() -> {
            View settings = activity.findViewById(R.id.btnSettings);
            if (settings != null) settings.setOnClickListener(view -> showSettings());
            applyPrayerVisibility();
            applyDock();
        });
    }

    private View passiveLayer() {
        View layer = new View(activity);
        layer.setFocusable(false);
        layer.setClickable(false);
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return layer;
    }

    private int dp(float value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private int positionOf(View focused) {
        if (grid == null) return -1;
        View v = focused;
        while (v != null && v != grid) {
            if (v.getParent() == grid) return grid.getChildAdapterPosition(v);
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        return -1;
    }

    /** Auto is 7:00 AM to 7:00 PM Malaysia time; manual themes override it. */
    private void applyTheme() {
        int mode = prefs.getInt(THEME, 0);
        Calendar malaysia = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
        int hour = malaysia.get(Calendar.HOUR_OF_DAY);
        boolean day = mode == 1 || (mode == 0 && hour >= 7 && hour < 19);
        GradientDrawable glass = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                day ? new int[] {0x1858B8F9, 0x0A8BBFFF, 0x26335278}
                    : new int[] {0xA0040A1A, 0x850B1636, 0xC2040712});
        tint.setBackground(glass);
    }

    private static int alphaColor(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private void applyGlow() {
        int level = prefs.getInt(GLOW, 1);
        int strength = level == 0 ? 17 : (level == 1 ? 35 : 65);
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TR_BL,
                new int[] {alphaColor(lastFocusColor, strength),
                        alphaColor(lastFocusColor, strength / 3), Color.TRANSPARENT});
        accent.setBackground(gradient);
    }

    /** Dock tiles stay evenly spaced in their own grid cells. No extra media players. */
    private void applyDock() {
        if (grid == null || grid.getWidth() < 1 || grid.getAdapter() == null) return;
        final boolean compact = prefs.getBoolean(COMPACT, true);
        final float density = activity.getResources().getDisplayMetrics().density;
        final int inner = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        final int normalWidth = AppAdapter.tileWidthPx(inner, MainActivity.COLUMNS, density);
        final int tileWidth = compact ? Math.round(normalWidth * .89f) : normalWidth;
        final int tileHeight = AppAdapter.tileHeightPx(tileWidth);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            int position = grid.getChildAdapterPosition(child);
            if (position < 0 || position >= MainActivity.COLUMNS) continue;
            View card = child.findViewById(R.id.card);
            TextView name = child.findViewById(R.id.name);
            if (card == null || name == null) continue;
            ViewGroup.LayoutParams clp = card.getLayoutParams();
            if (clp.width != tileWidth || clp.height != tileHeight) {
                clp.width = tileWidth;
                clp.height = tileHeight;
                card.setLayoutParams(clp);
            }
            ViewGroup.MarginLayoutParams nlp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
            int top = tileHeight + dp(7);
            if (nlp.width != tileWidth || nlp.topMargin != top) {
                nlp.width = tileWidth;
                nlp.topMargin = top;
                name.setLayoutParams(nlp);
            }
            // Original adapter hides dock names; make them visible on focus only.
            int desired = compact ? View.VISIBLE : View.INVISIBLE;
            if (name.getVisibility() != desired) name.setVisibility(desired);
            if (compact && dockCompactLast != compact) name.setAlpha(card.isFocused() ? 1f : 0f);
        }
        dockCompactLast = compact;
    }

    private static boolean hasPrayerLabel(View node) {
        if (node instanceof TextView) {
            CharSequence text = ((TextView) node).getText();
            return text != null && text.toString().contains("WAKTU SOLAT");
        }
        if (node instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) node;
            for (int i = 0; i < parent.getChildCount(); i++) {
                if (hasPrayerLabel(parent.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private void applyPrayerVisibility() {
        boolean show = prefs.getBoolean(PRAYER, true);
        for (int i = 0; i < root.getChildCount(); i++) {
            View layer = root.getChildAt(i);
            if (layer instanceof FrameLayout && hasPrayerLabel(layer)) {
                layer.setVisibility(show ? View.VISIBLE : View.GONE);
                return;
            }
        }
    }

    private GradientDrawable tileBackground(boolean focused) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                focused ? new int[] {0xCF365C88, 0xBF263C67}
                        : new int[] {0xAC1D314D, 0xAD131C32});
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(focused ? 2 : 1), focused ? 0xFFBFE9FF : 0x6BB5D9FF);
        return drawable;
    }

    private TextView row(String title, String detail) {
        TextView view = new TextView(activity);
        view.setText(title + "\n" + detail);
        view.setTextColor(Color.WHITE);
        view.setTextSize(15);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(dp(17), dp(11), dp(12), dp(11));
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(tileBackground(false));
        view.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(tileBackground(focused));
        });
        return view;
    }

    private void showSettings() {
        if (activity.isFinishing()) return;
        Dialog dialog = new Dialog(activity);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        GradientDrawable shell = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] {0xF11D2B46, 0xF10A1323});
        shell.setCornerRadius(dp(22));
        shell.setStroke(dp(1), 0xA5D4EEFF);
        scroll.setBackground(shell);
        scroll.setClipToOutline(true);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(20), dp(24), dp(20));
        TextView heading = new TextView(activity);
        heading.setText("GLASS SETTINGS  /  AURORA");
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextSize(23);
        heading.setTextColor(Color.WHITE);
        content.addView(heading);
        TextView sub = new TextView(activity);
        sub.setText("Personalize Home  ·  Panasonic Android TV");
        sub.setTextSize(12);
        sub.setTextColor(0xBDCEE5FA);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(5);
        subLp.bottomMargin = dp(12);
        content.addView(sub, subLp);

        TextView theme = row("Day & Night", "");
        TextView glow = row("Adaptive Glow", "");
        TextView dock = row("Minimal Dock", "");
        TextView prayer = row("Prayer Widget", "");
        Runnable refresh = () -> {
            theme.setText("Day & Night  /  " + MODES[Math.max(0, Math.min(2, prefs.getInt(THEME, 0)))]
                    + "\nAuto follows Malaysia time (7 AM to 7 PM)");
            glow.setText("Adaptive Glow  /  " + GLOWS[Math.max(0, Math.min(2, prefs.getInt(GLOW, 1)))]
                    + "\nExtra color glow follows focused apps");
            dock.setText("Minimal Dock  /  " + (prefs.getBoolean(COMPACT, true) ? "On" : "Off")
                    + "\nSmaller tiles, labels appear on focus");
            prayer.setText("Prayer Widget  /  " + (prefs.getBoolean(PRAYER, true) ? "Shown" : "Hidden")
                    + "\nShow or hide the Shah Alam prayer card");
        };
        refresh.run();
        theme.setOnClickListener(v -> {
            prefs.edit().putInt(THEME, (prefs.getInt(THEME, 0) + 1) % 3).apply();
            applyTheme(); refresh.run();
        });
        glow.setOnClickListener(v -> {
            prefs.edit().putInt(GLOW, (prefs.getInt(GLOW, 1) + 1) % 3).apply();
            applyGlow(); refresh.run();
        });
        dock.setOnClickListener(v -> {
            prefs.edit().putBoolean(COMPACT, !prefs.getBoolean(COMPACT, true)).apply();
            applyDock(); refresh.run();
        });
        prayer.setOnClickListener(v -> {
            prefs.edit().putBoolean(PRAYER, !prefs.getBoolean(PRAYER, true)).apply();
            applyPrayerVisibility(); refresh.run();
        });

        View first = null;
        TextView[] rows = {theme, glow, dock, prayer};
        for (TextView item : rows) {
            if (first == null) first = item;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(62));
            p.topMargin = dp(7);
            content.addView(item, p);
        }
        TextView androidSettings = row("Panasonic & System Settings", "Wi-Fi, picture, sound and TV options");
        androidSettings.setOnClickListener(v -> {
            dialog.dismiss();
            GlassSettings.show(activity);
        });
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(-1, dp(62));
        moreLp.topMargin = dp(12);
        content.addView(androidSettings, moreLp);
        TextView close = row("Close", "Return to Glass TV");
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(62));
        closeLp.topMargin = dp(7);
        content.addView(close, closeLp);

        scroll.addView(content);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = .48f;
            window.setAttributes(attrs);
        }
        final View initialFocus = first;
        dialog.setOnShowListener(ignored -> {
            Window popup = dialog.getWindow();
            if (popup != null) {
                int w = activity.getResources().getDisplayMetrics().widthPixels;
                int h = activity.getResources().getDisplayMetrics().heightPixels;
                popup.setGravity(Gravity.CENTER);
                popup.setLayout(Math.min(dp(700), Math.round(w * .82f)),
                        Math.min(dp(515), Math.round(h * .86f)));
            }
            if (initialFocus != null) initialFocus.requestFocus();
        });
        dialog.show();
    }
}
