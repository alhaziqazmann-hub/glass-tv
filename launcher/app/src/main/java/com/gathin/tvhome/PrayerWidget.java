package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Glass TV v3.2: compact Shah Alam prayer-time preview.
 *
 * Reads the published JAKIM-sourced monthly timetable from api.waktusolat.app.
 * Caches the entire month, never updates from the UI thread and never invents a
 * time if no verified day is available. It does NOT schedule alarms or azan.
 * No location permission is requested: SGR01 is a deliberately fixed zone.
 */
final class PrayerWidget {
    private static final String ZONE = "SGR01";
    private static final String ENDPOINT = "https://api.waktusolat.app/v2/solat/" + ZONE;
    private static final String[] LABELS = {"Subuh", "Zohor", "Asar", "Maghrib", "Isyak"};
    private static final String[] JSON_KEYS = {"fajr", "dhuhr", "asr", "maghrib", "isha"};
    private static final TimeZone MALAYSIA = TimeZone.getTimeZone("Asia/Kuala_Lumpur");
    private static final long REFRESH_MS = 12L * 60 * 60 * 1000;
    private static final long RETRY_MS = 30L * 60 * 1000;

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final FrameLayout card;
    private final TextView name;
    private final TextView time;
    private final TextView countdown;
    private volatile boolean fetching;
    private volatile long retryAfter;
    private JSONObject monthly;
    private String monthKey = "";
    private boolean active;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!active || activity.isFinishing()) return;
            render();
            scheduleFetch();
            ui.postDelayed(this, 60_000L);
        }
    };

    static void attach(Activity owner, FrameLayout root) {
        if (owner == null || root == null) return;
        new PrayerWidget(owner, root);
    }

    private PrayerWidget(Activity owner, FrameLayout root) {
        activity = owner;
        prefs = owner.getSharedPreferences("prayer_widget_v32", Activity.MODE_PRIVATE);
        float density = owner.getResources().getDisplayMetrics().density;
        card = new FrameLayout(owner);
        card.setClickable(false);
        card.setFocusable(false);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xAC1C3149, 0x9A0A172A});
        background.setCornerRadius(dp(21));
        background.setStroke(Math.max(1, dp(0.8f)), 0x5CFFFFFF);
        card.setBackground(background);

        LinearLayout stack = new LinearLayout(owner);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(dp(16), dp(11), dp(16), dp(10));
        card.addView(stack, new FrameLayout.LayoutParams(-1, -2));

        TextView eyebrow = label("☪  WAKTU SOLAT  ·  SHAH ALAM", 10, 0xCCDAE9FF, true);
        eyebrow.setLetterSpacing(0.07f);
        stack.addView(eyebrow);

        LinearLayout middle = new LinearLayout(owner);
        middle.setOrientation(LinearLayout.HORIZONTAL);
        middle.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams middleLp = new LinearLayout.LayoutParams(-1, -2);
        middleLp.topMargin = dp(4);
        stack.addView(middle, middleLp);
        name = label("Memuat jadual...", 17, Color.WHITE, true);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
        middle.addView(name, nameLp);
        time = label("--:--", 17, 0xFFE3EDFF, true);
        middle.addView(time, new LinearLayout.LayoutParams(-2, -2));
        countdown = label("SGR01  ·  Menunggu data JAKIM", 10, 0xBCD7E8FF, false);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(-1, -2);
        bottomLp.topMargin = dp(5);
        stack.addView(countdown, bottomLp);

        // Central glass tile: clear of the clock on the left and controls on the right.
        // Keeps its height modest so it does not overlay the animated hero poster.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(252), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(20);
        card.setElevation(0f);
        root.addView(card, lp);

        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                active = true;
                ui.removeCallbacks(ticker);
                ui.post(ticker);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                active = false;
                ui.removeCallbacks(ticker);
            }
        });
        if (card.isAttachedToWindow()) {
            active = true;
            ui.post(ticker);
        }
    }

    private int dp(float n) {
        return Math.round(n * activity.getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int sp, int color, boolean medium) {
        TextView v = new TextView(activity);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setIncludeFontPadding(false);
        v.setClickable(false);
        v.setFocusable(false);
        return v;
    }

    private Calendar malaysiaNow() {
        return Calendar.getInstance(MALAYSIA);
    }

    private String dateString(Calendar date) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        iso.setTimeZone(MALAYSIA);
        return iso.format(date.getTime());
    }

    private String monthString(Calendar date) {
        return String.format(Locale.US, "%04d-%02d",
                date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1);
    }

    /**
     * API v2 publishes the monthly list as "prayers". V1 and some older client
     * examples use "prayerTime". Accept both without mixing their time formats.
     */
    private static JSONArray entries(JSONObject payload) {
        if (payload == null) return null;
        JSONArray result = payload.optJSONArray("prayers");
        return result != null ? result : payload.optJSONArray("prayerTime");
    }

    /** Monthly payload is valid only for the exact month requested. */
    private void readCache(Calendar today) {
        String key = monthString(today);
        if (key.equals(monthKey) && monthly != null) return;
        monthKey = key;
        monthly = null;
        if (!key.equals(prefs.getString("month", ""))) return;
        try {
            JSONObject saved = new JSONObject(prefs.getString("data", ""));
            JSONArray days = entries(saved);
            if (days != null && days.length() >= 1) monthly = saved;
        } catch (Exception ignored) { }
    }

    private JSONObject findDay(String isoDate) {
        if (monthly == null) return null;
        JSONArray days = entries(monthly);
        if (days == null) return null;
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day != null && day.optString("date", "").startsWith(isoDate)) return day;
        }
        return null;
    }

    private void render() {
        Calendar today = malaysiaNow();
        readCache(today);
        final long nowMs = System.currentTimeMillis();
        String selected = null;
        long instant = 0L;
        for (int offset = 0; offset <= 1 && selected == null; offset++) {
            Calendar day = (Calendar) today.clone();
            day.add(Calendar.DAY_OF_MONTH, offset);
            JSONObject entry = findDay(dateString(day));
            if (entry == null) continue;
            for (int i = 0; i < JSON_KEYS.length; i++) {
                long seconds = entry.optLong(JSON_KEYS[i], 0L);
                if (seconds < 1_000_000_000L) continue; // invalid or unavailable API data
                long timeMs = seconds * 1000L;
                if (timeMs > nowMs) {
                    selected = (offset == 1 ? "Esok " : "") + LABELS[i];
                    instant = timeMs;
                    break;
                }
            }
        }
        if (selected == null) {
            name.setText(monthly == null ? "Jadual belum ada" : "Menunggu jadual");
            time.setText("--:--");
            countdown.setText("SGR01  ·  Menunggu jadual waktu solat");
            return;
        }
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        hm.setTimeZone(MALAYSIA);
        long left = Math.max(0L, (instant - nowMs + 59999L) / 60000L);
        name.setText(selected);
        time.setText(hm.format(new Date(instant)));
        countdown.setText(String.format(Locale.US, "Dalam %dj %02dm  ·  JAKIM SGR01", left / 60, left % 60));
    }

    private void scheduleFetch() {
        Calendar date = malaysiaNow();
        final String key = monthString(date);
        long now = System.currentTimeMillis();
        long last = prefs.getLong("fetched_at", 0L);
        boolean fresh = key.equals(prefs.getString("month", ""))
                && monthly != null && now - last >= 0L && now - last < REFRESH_MS;
        if (fresh || fetching || now < retryAfter) return;
        fetching = true;
        final int year = date.get(Calendar.YEAR);
        final int month = date.get(Calendar.MONTH) + 1;
        new Thread(() -> {
            JSONObject fetched = null;
            HttpURLConnection conn = null;
            try {
                URL url = new URL(ENDPOINT + "?year=" + year + "&month=" + month);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("Accept", "application/json");
                conn.setUseCaches(true);
                if (conn.getResponseCode() == 200) {
                    StringBuilder result = new StringBuilder(14000);
                    try (InputStream in = conn.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null && result.length() < 200000) {
                            result.append(line);
                        }
                    }
                    JSONObject parsed = new JSONObject(result.toString());
                    JSONArray days = entries(parsed);
                    if (days != null && days.length() > 0
                            && days.optJSONObject(0) != null
                            && days.optJSONObject(0).optLong("fajr", 0L) > 1_000_000_000L) {
                        fetched = parsed;
                    }
                }
            } catch (Exception ignored) {
                // Unavailable data is never represented as a guessed prayer time.
            } finally {
                if (conn != null) conn.disconnect();
            }
            final JSONObject ready = fetched;
            ui.post(() -> {
                fetching = false;
                if (ready == null) {
                    retryAfter = System.currentTimeMillis() + RETRY_MS;
                    return;
                }
                prefs.edit().putString("month", key).putString("data", ready.toString())
                        .putLong("fetched_at", System.currentTimeMillis()).apply();
                monthly = ready;
                monthKey = key;
                if (active && !activity.isFinishing()) render();
            });
        }, "glass-tv-prayer-sync").start();
    }
}
