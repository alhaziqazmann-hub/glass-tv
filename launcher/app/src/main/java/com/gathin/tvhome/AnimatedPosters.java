package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;
import java.io.File;

/**
 * Glass TV Custom v2 — floating cinematic poster, aligned above the app dock.
 * Maintains API AnimatedPosters.attach(this, root) used by MainActivity.java.
 * Playback uses local, muted H.264 MP4 files in app external files/posters/slotN.mp4.
 * Does not alter HOME routing, system apps, wallpaper config, or dock navigation.
 */
final class AnimatedPosters {
    private static final String TAG = "GlassTV.Posters";
    private static final long FOCUS_DELAY_MS = 220;
    private final Activity activity;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final FrameLayout card;
    private final TextureView video;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private boolean surfaceReady;
    private boolean foreground = true;
    private int requestedSlot = -1;
    private int playingSlot = -1;
    private int generation;
    private final Runnable delayedPlay = this::openRequested;

    static void attach(Activity activity, FrameLayout root) {
        if (root == null) {
            Log.w(TAG, "Root container unavailable; animation skipped");
            return;
        }
        new AnimatedPosters(activity, root);
    }

    private AnimatedPosters(Activity owner, FrameLayout parent) {
        activity = owner;
        root = parent;
        grid = owner.findViewById(R.id.grid);
        final float density = owner.getResources().getDisplayMetrics().density;

        // A restrained, rounded panel. Android TV's existing dock remains untouched.
        card = new FrameLayout(owner);
        GradientDrawable chrome = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(19, 25, 46), Color.rgb(8, 12, 25)});
        chrome.setCornerRadius(24f * density);
        chrome.setStroke(Math.max(1, Math.round(density)), Color.argb(100, 255, 255, 255));
        card.setBackground(chrome);
        card.setClipToOutline(true);
        card.setElevation(0f);
        card.setVisibility(View.GONE);
        card.setAlpha(0f);

        video = new TextureView(owner) {
            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                foreground = visibility == View.VISIBLE;
                if (!foreground) {
                    handler.removeCallbacks(delayedPlay);
                    if (player != null) {
                        try { if (player.isPlaying()) player.pause(); }
                        catch (RuntimeException ignored) { }
                    }
                } else if (player != null) {
                    try { player.start(); } catch (RuntimeException ignored) { }
                } else {
                    openRequested();
                }
            }
        };
        video.setOpaque(false);
        video.setClipToOutline(true);
        video.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 24f * density);
            }
        });
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                surfaceReady = true;
                openRequested();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                surfaceReady = false;
                releasePlayer();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
        card.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // index 0 remains above the wallpaper inserted later by Glass TV,
        // but below its clock, highlight, settings, and app tiles.
        root.addView(card, 0, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.END));

        // Dock position is measured from actual tile locations, not fixed dp guesses.
        // This prevents the hero preview from covering the apps row on different TVs.
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::positionCard);
        if (grid != null) {
            root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
                int position = adapterPosition(newFocus);
                int slot = position >= 0 && position < MainActivity.COLUMNS ? position + 1 : -1;
                changeSlot(slot);
            });
        } else {
            Log.w(TAG, "Grid unavailable; no dock-driven poster selection");
        }
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private int adapterPosition(View focused) {
        if (grid == null) return -1;
        View child = focused;
        while (child != null && child != grid) {
            if (child.getParent() == grid) return grid.getChildAdapterPosition(child);
            if (!(child.getParent() instanceof View)) break;
            child = (View) child.getParent();
        }
        return -1;
    }

    private int firstDockTileTop() {
        final int screenH = root.getHeight();
        if (grid == null || screenH <= 0) return Math.round(screenH * 0.68f);
        int[] rootLocation = new int[2];
        int[] itemLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        int firstTop = Integer.MAX_VALUE;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            int index = grid.getChildAdapterPosition(child);
            if (index >= 0 && index < MainActivity.COLUMNS) {
                child.getLocationOnScreen(itemLocation);
                firstTop = Math.min(firstTop, itemLocation[1] - rootLocation[1]);
            }
        }
        // When first row is not laid out yet, use a conservative fallback.
        if (firstTop == Integer.MAX_VALUE || firstTop < screenH * 0.42f) {
            return Math.round(screenH * 0.68f);
        }
        return firstTop;
    }

    private void positionCard() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) return;

        int dockTop = firstDockTileTop();
        int topSafe = Math.max(dp(70), Math.round(h * 0.17f));
        int bottomGap = Math.max(dp(18), Math.round(h * 0.035f));
        int available = dockTop - bottomGap - topSafe;
        if (available < dp(90)) return;

        int maxWidth = Math.round(w * 0.44f);
        int maxHeightByWidth = Math.round(maxWidth * 9f / 16f);
        int previewH = Math.min(maxHeightByWidth, available);
        int previewW = Math.round(previewH * 16f / 9f);
        int sideGap = Math.max(dp(20), Math.round(w * 0.037f));
        int top = topSafe + Math.max(0, (available - previewH) / 2);

        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (old.width == previewW && old.height == previewH
                && old.topMargin == top && old.rightMargin == sideGap) return;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                previewW, previewH, Gravity.TOP | Gravity.END);
        params.topMargin = top;
        params.rightMargin = sideGap;
        card.setLayoutParams(params);
    }

    private File fileFor(int slot) {
        File dir = activity.getExternalFilesDir(null);
        return dir == null ? null : new File(new File(dir, "posters"), "slot" + slot + ".mp4");
    }

    private void hideCard() {
        card.animate().cancel();
        card.setAlpha(0f);
        card.setVisibility(View.GONE);
    }

    private void changeSlot(int slot) {
        if (slot == requestedSlot) return;
        requestedSlot = slot;
        handler.removeCallbacks(delayedPlay);
        if (slot < 0) {
            releasePlayer();
            hideCard();
        } else {
            handler.postDelayed(delayedPlay, FOCUS_DELAY_MS);
        }
    }

    private void openRequested() {
        if (requestedSlot < 0 || !foreground) return;
        File file = fileFor(requestedSlot);
        if (file == null || !file.isFile() || !file.canRead()) {
            releasePlayer();
            hideCard();
            return;
        }
        if (player != null && playingSlot == requestedSlot) return;
        releasePlayer();
        positionCard();
        card.animate().cancel();
        card.setAlpha(0f);
        card.setVisibility(View.VISIBLE);
        if (!surfaceReady || video.getSurfaceTexture() == null) return;

        final int token = ++generation;
        try {
            MediaPlayer candidate = new MediaPlayer();
            player = candidate;
            playingSlot = requestedSlot;
            candidate.setDataSource(file.getAbsolutePath());
            Surface surface = new Surface(video.getSurfaceTexture());
            candidate.setSurface(surface);
            surface.release();
            candidate.setVolume(0f, 0f);
            candidate.setLooping(true);
            candidate.setOnPreparedListener(mp -> {
                if (generation != token || mp != player || !foreground) return;
                try {
                    mp.start();
                    card.animate().alpha(1f).setDuration(280).start();
                } catch (RuntimeException ex) {
                    fail(ex);
                }
            });
            candidate.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "MediaPlayer error " + what + "/" + extra);
                releasePlayer();
                hideCard();
                return true;
            });
            candidate.prepareAsync();
        } catch (Exception e) {
            fail(e);
        }
    }

    private void fail(Exception error) {
        Log.w(TAG, "Poster unavailable; normal Glass TV remains", error);
        releasePlayer();
        hideCard();
    }

    private void releasePlayer() {
        generation++;
        playingSlot = -1;
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try { old.reset(); } catch (RuntimeException ignored) { }
            old.release();
        }
    }
}
