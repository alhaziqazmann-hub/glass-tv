package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
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
 * Optional local MP4 hero previews for Glass TV. No internet, audio, or background playback.
 * /sdcard/Android/data/com.gathin.tvhome/files/posters/slot1.mp4 = first dock app, etc.
 * If an MP4 does not exist, the ordinary Glass TV wallpaper remains untouched.
 */
final class AnimatedPosters {
    private static final String TAG = "GlassTV.Posters";
    private final Activity activity;
    private final RecyclerView grid;
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
        new AnimatedPosters(activity, root);
    }

    private AnimatedPosters(Activity owner, FrameLayout root) {
        activity = owner;
        grid = owner.findViewById(R.id.grid);
        final float density = owner.getResources().getDisplayMetrics().density;
        final int screenWidth = owner.getResources().getDisplayMetrics().widthPixels;
        final int width = Math.round(screenWidth * 0.58f);
        final int height = Math.round(width * 9f / 16f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.END);
        lp.topMargin = Math.round(125f * density);
        lp.rightMargin = Math.round(52f * density);

        video = new TextureView(owner) {
            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                foreground = visibility == View.VISIBLE;
                if (!foreground) {
                    handler.removeCallbacks(delayedPlay);
                    if (player != null) {
                        try { if (player.isPlaying()) player.pause(); } catch (RuntimeException ignored) { }
                    }
                } else if (player != null) {
                    try { player.start(); } catch (RuntimeException ignored) { }
                } else {
                    openRequested();
                }
            }
        };
        video.setAlpha(0f);
        video.setOpaque(false);
        video.setVisibility(View.INVISIBLE);
        video.setClipToOutline(true);
        video.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 22f * density);
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

        // Attach before setWallpaper(): Glass TV inserts the actual wallpaper at index 0.
        // That leaves the video above the wallpaper but BELOW the dock, clock and glass highlights.
        root.addView(video, 0, lp);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            int position = adapterPosition(newFocus);
            int slot = position >= 0 && position < MainActivity.COLUMNS ? position + 1 : -1;
            changeSlot(slot);
        });
    }

    private int adapterPosition(View focused) {
        View child = focused;
        while (child != null && child != grid) {
            if (child.getParent() == grid) return grid.getChildAdapterPosition(child);
            if (!(child.getParent() instanceof View)) break;
            child = (View) child.getParent();
        }
        return -1;
    }

    private File fileFor(int slot) {
        File dir = activity.getExternalFilesDir(null);
        return dir == null ? null : new File(new File(dir, "posters"), "slot" + slot + ".mp4");
    }

    private void changeSlot(int slot) {
        if (slot == requestedSlot) return;
        requestedSlot = slot;
        handler.removeCallbacks(delayedPlay);
        if (slot < 0) {
            releasePlayer();
            video.animate().cancel();
            video.setAlpha(0f);
            video.setVisibility(View.INVISIBLE);
        } else {
            handler.postDelayed(delayedPlay, 240);
        }
    }

    private void openRequested() {
        if (requestedSlot < 0 || !foreground) return;
        File file = fileFor(requestedSlot);
        if (file == null || !file.isFile() || !file.canRead()) {
            releasePlayer();
            video.setAlpha(0f);
            video.setVisibility(View.INVISIBLE);
            return;
        }
        if (player != null && playingSlot == requestedSlot) return;
        releasePlayer();
        video.animate().cancel();
        video.setAlpha(0f);
        video.setVisibility(View.VISIBLE);
        if (!surfaceReady || video.getSurfaceTexture() == null) return;

        final int token = ++generation;
        final int slot = requestedSlot;
        try {
            MediaPlayer candidate = new MediaPlayer();
            player = candidate;
            playingSlot = slot;
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
                    video.animate().alpha(0.92f).setDuration(360).start();
                } catch (RuntimeException e) { fail(e); }
            });
            candidate.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "MediaPlayer error " + what + "/" + extra);
                releasePlayer();
                video.setAlpha(0f);
                return true;
            });
            candidate.prepareAsync();
        } catch (Exception e) { fail(e); }
    }

    private void fail(Exception e) {
        Log.w(TAG, "Poster unavailable; using wallpaper", e);
        releasePlayer();
        video.setAlpha(0f);
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
