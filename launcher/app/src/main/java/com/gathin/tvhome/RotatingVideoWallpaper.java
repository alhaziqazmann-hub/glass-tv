package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Glass TV v3.2 Aurora: fullscreen muted MP4 wallpaper carousel.
 *
 * Put video files in:
 *   /sdcard/Android/data/com.gathin.tvposter.v32/files/wallpapers-animated/
 *
 * Each clip is shown for 10 seconds, in filename order. A single MediaPlayer
 * is reused per clip; no network connections or extra background services.
 * The ImageView wallpaper from MainActivity remains available underneath.
 * Does not touch the four /posters/slotN.mp4 videos, prayer times or HOME.
 *
 * For a low-power Android TV prefer 1280x720 H.264 MP4, muted, 24-30 fps.
 */
final class RotatingVideoWallpaper {
    private static final String TAG = "GlassTV.Wallpapers";
    private static final String FOLDER = "wallpapers-animated";
    private static final long CHANGE_EVERY_MS = 10_000L;
    private static final long RETRY_MS = 20_000L;
    private static final long ERROR_RETRY_MS = 3_000L;

    private final Activity owner;
    private final TextureView video;
    private final View shade;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable next = this::playNext;
    private MediaPlayer player;
    private boolean surfaceReady;
    private boolean foreground = true;
    private boolean attached;
    private boolean preparing;
    private int generation;
    private int lastIndex = -1;
    private String lastFileName;
    private int consecutiveErrors;

    static void attach(Activity activity, FrameLayout root) {
        if (activity == null || root == null) return;
        new RotatingVideoWallpaper(activity, root);
    }

    private RotatingVideoWallpaper(Activity activity, FrameLayout root) {
        owner = activity;
        // Placed at index zero. MainActivity later inserts its still wallpaper at
        // index zero, so this texture automatically sits immediately ABOVE it,
        // but BELOW Aurora's tint, ambient glow, clocks, tiles and video previews.
        video = new TextureView(activity) {
            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                foreground = visibility == View.VISIBLE;
                if (foreground) {
                    resumeOrLoad();
                } else {
                    main.removeCallbacks(next);
                    pausePlayer();
                }
            }
            @Override protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                attached = false;
                main.removeCallbacks(next);
                releasePlayer();
            }
            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                attached = true;
                resumeOrLoad();
            }
        };
        video.setFocusable(false);
        video.setClickable(false);
        video.setOpaque(false);
        video.setAlpha(0f);
        video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                surfaceReady = true;
                resumeOrLoad();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {
                updateTransform();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                surfaceReady = false;
                main.removeCallbacks(next);
                releasePlayer();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
        root.addView(video, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // A cheap dark scrim, shown only when a clip is playing, preserves legibility.
        shade = new View(activity);
        shade.setBackgroundColor(Color.argb(54, 0, 0, 0));
        shade.setFocusable(false);
        shade.setClickable(false);
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shade.setVisibility(View.GONE);
        root.addView(shade, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // No polling when the screen is detached or the launcher is not visible.
        root.post(this::resumeOrLoad);
    }

    private File[] availableClips() {
        File external = owner.getExternalFilesDir(null);
        if (external == null) return new File[0];
        File dir = new File(external, FOLDER);
        File[] files = dir.listFiles(file -> file.isFile()
                && file.length() > 0
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".mp4"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private void resumeOrLoad() {
        if (!attached || !foreground || !surfaceReady || owner.isFinishing()) return;
        if (player != null) {
            if (preparing) return;
            try {
                updateTransform();
                player.start();
                shade.setVisibility(View.VISIBLE);
                video.animate().cancel();
                video.animate().alpha(1f).setDuration(200L).start();
                // A fresh 10-second period after returning from another app.
                main.removeCallbacks(next);
                main.postDelayed(next, CHANGE_EVERY_MS);
                return;
            } catch (IllegalStateException ignored) {
                releasePlayer();
            }
        }
        main.removeCallbacks(next);
        main.post(this::playNext);
    }

    private void playNext() {
        if (!attached || !foreground || !surfaceReady || owner.isFinishing()) return;
        main.removeCallbacks(next);
        File[] clips = availableClips();
        if (clips.length == 0) {
            lastFileName = null;
            lastIndex = -1;
            releasePlayer();
            video.animate().cancel();
            video.setAlpha(0f);
            shade.setVisibility(View.GONE);
            main.postDelayed(next, RETRY_MS);  // Pick up files copied after installation.
            return;
        }

        int index = 0;
        if (lastFileName != null) {
            int found = -1;
            for (int i = 0; i < clips.length; i++) {
                if (clips[i].getName().equals(lastFileName)) { found = i; break; }
            }
            if (found >= 0) index = (found + 1) % clips.length;
            else if (lastIndex >= 0) index = Math.min(lastIndex, clips.length - 1);
        }
        lastIndex = index;
        File clip = clips[index];
        lastFileName = clip.getName();
        releasePlayer();
        final int currentToken = generation;
        preparing = true;
        video.animate().cancel();
        video.setAlpha(0f);
        shade.setVisibility(View.GONE);

        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setDataSource(clip.getAbsolutePath());
            SurfaceTexture texture = video.getSurfaceTexture();
            if (texture == null) throw new IllegalStateException("No texture");
            Surface surface = new Surface(texture);
            mp.setSurface(surface);
            surface.release();
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            mp.setOnPreparedListener(ready -> {
                if (currentToken != generation || ready != player) return;
                preparing = false;
                if (!foreground) return;  // resumeOrLoad() starts it when Home returns.
                updateTransform();
                try {
                    ready.start();
                    consecutiveErrors = 0;
                    shade.setVisibility(View.VISIBLE);
                    video.animate().alpha(1f).setDuration(280L).start();
                    main.removeCallbacks(next);
                    main.postDelayed(next, CHANGE_EVERY_MS);
                } catch (IllegalStateException e) {
                    failPlayback(e);
                }
            });
            mp.setOnErrorListener((failed, what, extra) -> {
                if (currentToken != generation || failed != player) return true;
                Log.w(TAG, "Video error " + what + "/" + extra);
                failPlayback(null);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception error) {
            failPlayback(error);
        }
    }

    private void updateTransform() {
        MediaPlayer mp = player;
        if (mp == null || video.getWidth() <= 0 || video.getHeight() <= 0) return;
        int width, height;
        try {
            width = mp.getVideoWidth();
            height = mp.getVideoHeight();
        } catch (IllegalStateException ignored) {
            return;
        }
        if (width <= 0 || height <= 0) return;
        float screenW = video.getWidth(), screenH = video.getHeight();
        float cover = Math.max(screenW / width, screenH / height);
        Matrix matrix = new Matrix();
        matrix.setScale((width * cover) / screenW, (height * cover) / screenH,
                screenW / 2f, screenH / 2f);
        video.setTransform(matrix);
    }

    private void failPlayback(Exception error) {
        if (error != null) Log.w(TAG, "Cannot play animated wallpaper", error);
        main.removeCallbacks(next);
        releasePlayer();
        video.animate().cancel();
        video.setAlpha(0f);
        shade.setVisibility(View.GONE);
        consecutiveErrors++;
        // Skip an unsupported clip; back off if ALL files appear unsuitable.
        main.postDelayed(next, consecutiveErrors > 5 ? RETRY_MS : ERROR_RETRY_MS);
    }

    private void pausePlayer() {
        if (player != null && !preparing) {
            try { player.pause(); } catch (IllegalStateException ignored) { }
        }
    }

    private void releasePlayer() {
        generation++;
        preparing = false;
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try { old.reset(); } catch (IllegalStateException ignored) { }
            try { old.release(); } catch (RuntimeException ignored) { }
        }
    }
}
