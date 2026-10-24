package com.gathin.tvhome;

import android.app.Activity;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.io.File;

/**
 * Glass TV 3.2 ambient preview for apps outside the four already served by
 * AnimatedPosters.java. Does not modify v3 poster playback.
 *
 * For optional app-specific video, place a muted H.264 MP4 at:
 *   getExternalFilesDir(null)/posters/<application.package.name>.mp4
 * Only one MediaPlayer exists at a time; no downloads or background services.
 */
final class AppMotionPreview {
    private static final int DOCK_SLOTS = 4;
    private final Activity activity;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final FrameLayout panel;
    private final TextureView video;
    private final View ambient;
    private final TextView title;
    private final TextView caption;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pending = this::showRequested;
    private int selected = -1;
    private boolean foreground = true;
    private boolean surfaceReady;
    private String playingPackage;
    private MediaPlayer player;
    private ObjectAnimator pulse;
    private int token;

    static void attach(Activity activity, FrameLayout root) {
        if (activity != null && root != null) new AppMotionPreview(activity, root);
    }

    private AppMotionPreview(Activity a, FrameLayout r) {
        activity = a;
        root = r;
        grid = a.findViewById(R.id.grid);

        panel = new FrameLayout(a) {
            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                foreground = visibility == View.VISIBLE;
                if (!foreground) {
                    handler.removeCallbacks(pending);
                    stopPulse();
                    if (player != null) {
                        try { player.pause(); } catch (RuntimeException ignored) { }
                    }
                } else if (selected >= DOCK_SLOTS) {
                    handler.postDelayed(pending, 180);
                }
            }
        };
        panel.setFocusable(false);
        panel.setClickable(false);
        panel.setVisibility(View.GONE);
        panel.setAlpha(0f);
        panel.setClipToOutline(true);
        GradientDrawable shell = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { 0xE51A2D46, 0xE5091222 });
        shell.setCornerRadius(dp(22));
        shell.setStroke(Math.max(1, dp(1)), 0x8CFFFFFF);
        panel.setBackground(shell);

        ambient = new View(a);
        ambient.setFocusable(false);
        ambient.setClickable(false);
        panel.addView(ambient, new FrameLayout.LayoutParams(-1, -1));

        video = new TextureView(a);
        video.setFocusable(false);
        video.setClickable(false);
        video.setOpaque(false);
        video.setVisibility(View.GONE);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                surfaceReady = true;
                if (selected >= DOCK_SLOTS) handler.post(pending);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                surfaceReady = false;
                release();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
        panel.addView(video, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout copy = new LinearLayout(a);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.BOTTOM | Gravity.START);
        copy.setPadding(dp(22), dp(16), dp(22), dp(20));
        GradientDrawable fade = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x00121B2B, 0xC40A1220 });
        copy.setBackground(fade);
        caption = new TextView(a);
        caption.setText("GLASS TV   /   AMBIENT PREVIEW");
        caption.setTextColor(0xCCDDEBFF);
        caption.setTextSize(10);
        caption.setLetterSpacing(.09f);
        caption.setSingleLine(true);
        title = new TextView(a);
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(7);
        copy.addView(caption);
        copy.addView(title, titleParams);
        panel.addView(copy, new FrameLayout.LayoutParams(-1, -1));

        // Under the existing clock, controls, and dock. No focus interception.
        root.addView(panel, 2, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.END));
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::position);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            int pos = positionOf(newFocus);
            select(pos);
        });
        root.post(() -> select(positionOf(a.getCurrentFocus())));
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private int positionOf(View focused) {
        if (grid == null || focused == null) return -1;
        View v = focused;
        while (v != null && v != grid) {
            if (v.getParent() == grid) return grid.getChildAdapterPosition(v);
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        return -1;
    }

    private AppAdapter adapter() {
        return grid != null && grid.getAdapter() instanceof AppAdapter
                ? (AppAdapter) grid.getAdapter() : null;
    }

    private int dockTop() {
        int h = root.getHeight();
        if (grid == null) return Math.round(h * .68f);
        int[] base = new int[2];
        int[] childLoc = new int[2];
        root.getLocationOnScreen(base);
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            int index = grid.getChildAdapterPosition(child);
            if (index >= 0 && index < MainActivity.COLUMNS) {
                child.getLocationOnScreen(childLoc);
                best = Math.min(best, childLoc[1] - base[1]);
            }
        }
        return best == Integer.MAX_VALUE || best < h * .42f ? Math.round(h * .68f) : best;
    }

    private void position() {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        int start = Math.max(dp(70), Math.round(h * .17f));
        int end = dockTop() - Math.max(dp(18), Math.round(h * .035f));
        int available = end - start;
        if (available < dp(100)) return;
        int previewH = Math.min(Math.round(w * .44f * 9f / 16f), available);
        int previewW = Math.round(previewH * 16f / 9f);
        int right = Math.max(dp(20), Math.round(w * .037f));
        int top = start + Math.max(0, (available - previewH) / 2);
        FrameLayout.LayoutParams current = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (current.width == previewW && current.height == previewH
                && current.rightMargin == right && current.topMargin == top) return;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                previewW, previewH, Gravity.TOP | Gravity.END);
        params.topMargin = top;
        params.rightMargin = right;
        panel.setLayoutParams(params);
    }

    private void select(int pos) {
        if (pos == selected) return;
        selected = pos;
        handler.removeCallbacks(pending);
        release();
        stopPulse();
        if (pos < DOCK_SLOTS || adapter() == null) {
            panel.animate().cancel();
            panel.setAlpha(0f);
            panel.setVisibility(View.GONE);
        } else {
            handler.postDelayed(pending, 220);
        }
    }

    private String selectedPackage(AppAdapter adapter) {
        String key = adapter.keyAt(selected);
        ComponentName component = key == null ? null : ComponentName.unflattenFromString(key);
        return component == null ? null : component.getPackageName();
    }

    private void showRequested() {
        AppAdapter adapter = adapter();
        if (!foreground || selected < DOCK_SLOTS || adapter == null) return;
        String pkg = selectedPackage(adapter);
        if (pkg == null) return;
        position();
        title.setText(adapter.labelAt(selected));
        int accent = adapter.ambientColorAt(selected);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { withAlpha(accent, 142), 0xAA1D2B49, 0xF20A1020 });
        background.setCornerRadius(dp(22));
        ambient.setBackground(background);
        panel.animate().cancel();
        panel.setVisibility(View.VISIBLE);
        panel.animate().alpha(1f).setDuration(230).start();
        File folder = new File(activity.getExternalFilesDir(null), "posters");
        File mp4 = new File(folder, pkg + ".mp4");
        if (mp4.isFile() && mp4.canRead() && surfaceReady && video.getSurfaceTexture() != null) {
            startVideo(mp4, pkg);
        } else {
            video.setVisibility(View.GONE);
            caption.setText("GLASS TV   /   AMBIENT PREVIEW");
            startPulse();
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private void startPulse() {
        stopPulse();
        ambient.setAlpha(.9f);
        pulse = ObjectAnimator.ofFloat(ambient, View.ALPHA, .7f, 1f);
        pulse.setDuration(2200);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) { pulse.cancel(); pulse = null; }
        ambient.setAlpha(1f);
    }

    private void startVideo(File file, String pkg) {
        release();
        stopPulse();
        video.setVisibility(View.VISIBLE);
        caption.setText("GLASS TV   /   MOTION PREVIEW");
        final int myToken = ++token;
        try {
            final MediaPlayer candidate = new MediaPlayer();
            player = candidate;
            playingPackage = pkg;
            candidate.setDataSource(file.getAbsolutePath());
            Surface surface = new Surface(video.getSurfaceTexture());
            candidate.setSurface(surface);
            surface.release();
            candidate.setVolume(0, 0);
            candidate.setLooping(true);
            candidate.setOnPreparedListener(mp -> {
                if (myToken != token || mp != player || !foreground) return;
                int vw = mp.getVideoWidth(), vh = mp.getVideoHeight();
                if (vw > 0 && vh > 0 && video.getWidth() > 0 && video.getHeight() > 0) {
                    float scale = Math.max((float) video.getWidth() / vw,
                                           (float) video.getHeight() / vh);
                    float sx = (scale * vw) / video.getWidth();
                    float sy = (scale * vh) / video.getHeight();
                    Matrix m = new Matrix();
                    m.setScale(sx, sy, video.getWidth() / 2f, video.getHeight() / 2f);
                    video.setTransform(m);
                }
                try { mp.start(); } catch (RuntimeException ignored) { }
            });
            candidate.setOnErrorListener((mp, what, extra) -> {
                release();
                video.setVisibility(View.GONE);
                startPulse();
                return true;
            });
            candidate.prepareAsync();
        } catch (Exception error) {
            release();
            video.setVisibility(View.GONE);
            startPulse();
        }
    }

    private void release() {
        token++;
        playingPackage = null;
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try { old.reset(); } catch (RuntimeException ignored) { }
            old.release();
        }
    }
}
