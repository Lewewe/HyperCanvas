package com.os4.canvas;

import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.graphics.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.util.*;

/** Owns one muted video surface, and never changes the host's wallpaper or view visibility. */
final class CanvasBackground {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final HandlerThread thread = new HandlerThread("MCCanvasPlayback");
    private static Handler worker;
    private static Context context;
    private static WeakReference<View> clock = new WeakReference<>(null);
    private static FrameLayout layer;
    private static TextureView texture;
    private static android.widget.ImageView frozenImage;
    private static FrameLayout frozenLayer;
    private static View frozenDim;
    private static android.graphics.Bitmap frozenBitmap;
    private static volatile boolean frozenInAod;
    private static View dim;
    private static MediaPlayer player;
    private static Surface surface;
    private static String desired = "", currentTrack = "", failedUrl = "";
    private static long retryAt;
    private static volatile long generation;
    private static volatile boolean firstFrame, wantedPlaying = true, presentationVisible = true, bouncerVisible;
    private static boolean querying;
    private static MediaController watched;
    private static final MediaController.Callback sessionCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { wake(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { wake(); }
        @Override public void onSessionDestroyed() { wake(); }
    };
    private static void wake() { worker.removeCallbacks(tick); worker.post(tick); }
    private static void watch(MediaController selected) {
        if (watched != null && selected != null
                && watched.getSessionToken().equals(selected.getSessionToken())) return;
        if (watched != null) watched.unregisterCallback(sessionCallback);
        watched = selected;
        if (watched != null) watched.registerCallback(sessionCallback, worker);
    }
    private static int normalDim = 35, coverDim = 45, blur = 18, videoW, videoH;
    private static boolean large;
    private static android.animation.ValueAnimator effectAnimator;
    private static float shownBlur, shownDim, targetBlur = Float.NaN, targetDim = Float.NaN;
    private static String diagnosticTrack;
    private static boolean artworkFallback;
    private static boolean backdropAllowed;
    private static volatile boolean showInPill;
    static void track(View value) { main.post(() -> clock = new WeakReference<>(value)); }
    static void start(Context value) {
        if (context != null) return;
        context = value;
        thread.start(); worker = new Handler(thread.getLooper());
        context.getContentResolver().registerContentObserver(CanvasProvider.URI, true, new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) { worker.removeCallbacks(tick); worker.post(tick); }
        });
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if ("com.os4.canvas.SCENE_CHANGED".equals(i.getAction())) {
                    main.post(() -> host(firstFrame, false));
                } else { worker.removeCallbacks(tick); worker.post(tick); }
            }
        }, new IntentFilter() {{ addAction("com.os4.canvas.SCENE_CHANGED"); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); }}, Context.RECEIVER_NOT_EXPORTED);
        worker.post(tick);
    }
    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            long delay = 5000L;
            try {
                boolean interactive = context.getSystemService(PowerManager.class).isInteractive();
                boolean locked = context.getSystemService(KeyguardManager.class).isKeyguardLocked();
                Bundle config = context.getContentResolver().call(CanvasProvider.URI, "query", null, null);
                boolean enabled = config != null && config.getBoolean("enabled");
                showInPill = config != null && config.getBoolean("showInPill");
                if (!enabled || !interactive || !locked) {
                    watch(null);
                    boolean temporary = enabled;
                    main.post(() -> {
                        backdropAllowed = false; artworkFallback = false;
                        if (enabled && !interactive && locked && firstFrame && !large && presentationVisible) {
                            freezeForAod();
                            host(true, true);
                        } else { clear(); host(false, temporary); }
                    });
                } else {
                    delay = 700L;
                    MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
                    MediaController selected = null;
                    for (MediaController item : sessions.getActiveSessions(null)) {
                        PlaybackState state = item.getPlaybackState();
                        if ("com.spotify.music".equals(item.getPackageName()) && state != null
                                && CanvasPolicy.acceptPlaybackState(state.getState(), firstFrame && watched != null
                                    && watched.getSessionToken().equals(item.getSessionToken()))) { selected = item; break; }
                    }
                    wantedPlaying = selected != null && selected.getPlaybackState() != null
                            && selected.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
                    watch(selected);
                    if (player != null && firstFrame) {
                        if (wantedPlaying && presentationVisible && !bouncerVisible && !frozenInAod && !player.isPlaying()) player.start();
                        else if ((!wantedPlaying || !presentationVisible || bouncerVisible || frozenInAod) && player.isPlaying()) player.pause();
                    }
                    MediaMetadata metadata = selected == null ? null : selected.getMetadata();
                    String identity = identity(metadata);
                    // A seek can briefly omit metadata without changing the playing track.
                    final String track = identity.isEmpty() && selected != null && firstFrame
                            ? currentTrack : identity;
                    String marker = selected == null ? "no-session"
                            : track + ":" + (metadata == null ? "no-metadata" : metadata.keySet());
                    if (!marker.equals(diagnosticTrack)) {
                        diagnosticTrack = marker;
                        try (java.io.FileWriter file = new java.io.FileWriter(new java.io.File(context.getCacheDir(), "hypercanvas-session.txt"))) {
                            file.write(marker);
                        } catch (Exception ignored) { }
                    }
                    Bundle neighbors = new Bundle();
                    if (selected != null && !track.isEmpty()) {
                        java.util.List<MediaSession.QueueItem> queue = selected.getQueue();
                        if (queue != null) for (int index = 0; index < queue.size(); index++) {
                            String id = CanvasPolicy.track(queue.get(index).getDescription().getMediaId());
                            if (track.equals(id)) {
                                if (index > 0) neighbors.putString("previous", queue.get(index - 1).getDescription().getMediaId());
                                if (index + 1 < queue.size()) neighbors.putString("next", queue.get(index + 1).getDescription().getMediaId());
                                break;
                            }
                        }
                        context.getContentResolver().call(CanvasProvider.URI, "window", track, neighbors);
                    }
                    Bundle result = context.getContentResolver().call(CanvasProvider.URI, "query", track, null);
                    String url = result == null ? "" : result.getString("url", "");
                    boolean temporary = selected == null;
                    if (!url.equals(CanvasProvider.URI + "/video/" + (track.isEmpty() ? "" : track.substring(14)))) main.post(() -> { backdropAllowed = true; artworkFallback = !temporary && !track.isEmpty(); clear(); host(false, temporary); });
                    else main.post(() -> {
                        restoreFromAod();
                        backdropAllowed = true;
                        artworkFallback = false;
                        normalDim = result.getInt("dim", 35); coverDim = result.getInt("coverDim", 45); blur = result.getInt("blur", 18);
                        if (!track.equals(currentTrack) || !url.equals(desired)) {
                            clear(); currentTrack = track; desired = url; ensureLayer(); playIfReady();
                        } else if (layer == null || !layer.isAttachedToWindow()) { clear(); currentTrack = track; desired = url; ensureLayer(); playIfReady(); }
                        if (firstFrame) host(true, false);
                        else if (desired.equals(failedUrl) && SystemClock.uptimeMillis() >= retryAt) playIfReady();
                        orderLayer();
                        effects();
                    });
                }
            } catch (Throwable t) { android.util.Log.w("MCCanvas", "Canvas background unavailable", t); main.post(() -> { artworkFallback = false; clear(); host(false, true); }); }
            worker.removeCallbacks(this);
            worker.postDelayed(this, delay);
        }
    };
    static String identity(MediaMetadata metadata) {
        if (metadata == null) return "";
        for (String key : new String[]{MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_MEDIA_URI, "com.spotify.music.metadata.TRACK_URI"}) {
            String track = CanvasPolicy.track(metadata.getString(key));
            if (!track.isEmpty()) return track;
        }
        return "";
    }
    private static void ensureLayer() {
        View anchor = clock.get();
        if (anchor == null || !anchor.isAttachedToWindow()) return;
        View root = anchor.getRootView();
        int id = anchor.getResources().getIdentifier("keyguard_background_layer", "id", "com.android.systemui");
        View background = id == 0 ? null : root.findViewById(id);
        if (!(background instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) background;
        layer = new FrameLayout(parent.getContext()); layer.setTag("HMCSpotifyCanvas");
        layer.setClickable(false); layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        layer.setAlpha(0f);
        texture = new TextureView(parent.getContext());
        // Spotify Canvas MP4s have no alpha.  Keeping this surface opaque avoids the
        // translucent TextureView composition path, which can repeatedly replay the
        // decoder's opening frames when SystemUI is rendered with Vulkan.
        texture.setOpaque(true);
        dim = new View(parent.getContext()); dim.setBackgroundColor(Color.BLACK);
        layer.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        layer.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        // Draw above the wallpaper inside its background layer, still below the clock and cards.
        parent.addView(layer, new ViewGroup.LayoutParams(-1, -1));
        orderLayer();
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) { playIfReady(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) { transform(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) { release(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture t) { }
        });
    }
    private static void orderLayer() {
        if (layer == null || !(layer.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) layer.getParent();
        View artwork = null;
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if ("HMCBigCoverArtwork".equals(child.getTag())) {
                artwork = child;
                break;
            }
        }
        int target = parent.getChildCount() - (artwork == null ? 1 : 2);
        if (parent.indexOfChild(layer) == target
                && (artwork == null || parent.indexOfChild(artwork) == parent.getChildCount() - 1)) return;
        // Keep the Canvas over the wallpaper, but the host's artwork over the Canvas.
        layer.bringToFront();
        if (artwork != null) artwork.bringToFront();
    }
    private static void playIfReady() {
        if (texture == null || !texture.isAvailable() || desired.isEmpty()
                || (desired.equals(failedUrl) && SystemClock.uptimeMillis() < retryAt)) return;
        long token = ++generation;
        String url = desired;
        Surface next = new Surface(texture.getSurfaceTexture());
        worker.post(() -> {
            dispose();
            if (token != generation) { next.release(); return; }
            surface = next;
            try {
                MediaPlayer created = new MediaPlayer(); player = created;
                created.setSurface(next); created.setVolume(0f, 0f); created.setLooping(true);
                created.setOnPreparedListener(p -> { if (token == generation) p.start(); });
                created.setOnVideoSizeChangedListener((p, w, h) -> main.post(() -> { if (token == generation) { videoW = w; videoH = h; transform(); } }));
                created.setOnInfoListener((p, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        if (token == generation && !wantedPlaying) p.pause();
                        main.post(() -> {
                        if (token != generation || layer == null) return;
                        firstFrame = true; host(true, false); effects();
                        layer.animate().alpha(1f).setDuration(200L).start();
                    });
                    }
                    return false;
                });
                created.setOnErrorListener((p, what, extra) -> { main.post(() -> { if (token == generation) { failedUrl = url; retryAt = SystemClock.uptimeMillis() + 30000L; clear(); host(false, false); } }); return true; });
                created.setDataSource(context, android.net.Uri.parse(url)); created.prepareAsync();
            } catch (Throwable t) { android.util.Log.w("MCCanvas", "Canvas video could not be prepared", t); dispose(); main.post(() -> { if (token == generation) { failedUrl = url; retryAt = SystemClock.uptimeMillis() + 30000L; clear(); host(false, false); } }); }
        });
    }
    private static void transform() {
        if (texture == null || videoW <= 0 || videoH <= 0 || texture.getWidth() <= 0 || texture.getHeight() <= 0) return;
        float w = texture.getWidth(), h = texture.getHeight();
        float scale = Math.max(w / videoW, h / videoH);
        Matrix matrix = new Matrix(); matrix.setScale(videoW * scale / w, videoH * scale / h, w / 2f, h / 2f);
        texture.setTransform(matrix);
    }
    private static void effects() {
        if (texture == null || dim == null) return;
        float nextDim = ((large || bouncerVisible || frozenInAod) ? Math.max(normalDim, coverDim) : normalDim) / 100f;
        float nextBlur = (large || bouncerVisible || frozenInAod) ? Math.max(bouncerVisible || frozenInAod ? 18 : 0, blur) * context.getResources().getDisplayMetrics().density : 0f;
        if (Float.isNaN(targetDim)) {
            shownDim = normalDim / 100f; shownBlur = 0f;
            drawEffects();
        }
        if (nextDim == targetDim && nextBlur == targetBlur) return;
        if (effectAnimator != null) effectAnimator.cancel();
        targetDim = nextDim; targetBlur = nextBlur;
        final float fromDim = shownDim, fromBlur = shownBlur;
        if (fromDim == nextDim && fromBlur == nextBlur) { drawEffects(); return; }
        effectAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        effectAnimator.setDuration(320L);
        effectAnimator.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0.2f, 1f));
        effectAnimator.addUpdateListener(value -> {
            float progress = (float) value.getAnimatedValue();
            shownDim = fromDim + (nextDim - fromDim) * progress;
            shownBlur = fromBlur + (nextBlur - fromBlur) * progress;
            drawEffects();
        });
        effectAnimator.start();
    }
    private static void drawEffects() {
        if (texture == null || dim == null) return;
        dim.setAlpha(shownDim);
        RenderEffect effect = shownBlur < 0.5f ? null
                : RenderEffect.createBlurEffect(shownBlur, shownBlur, Shader.TileMode.CLAMP);
        texture.setRenderEffect(effect);
        if (frozenImage != null) frozenImage.setRenderEffect(effect);
        if (frozenDim != null) frozenDim.setAlpha(shownDim);
    }
    private static void host(boolean ready, boolean temporary) {
        if (querying || context == null) return;
        querying = true;
        Intent intent = new Intent("com.os4.musiccover.PROBE").setPackage("com.android.systemui")
            .putExtra("frozenInAod", frozenInAod).putExtra("showInPill", showInPill).putExtra("backdropAllowed", backdropAllowed).putExtra("artworkFallback", artworkFallback).putExtra("op", "canvasframe").putExtra("ready", ready).putExtra("temporary", temporary).putExtra("track", currentTrack);
        BroadcastOptions options = BroadcastOptions.makeBasic(); options.setShareIdentityEnabled(true);
        context.sendOrderedBroadcast(intent, null, options.toBundle(), new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                querying = false;
                Bundle result = getResultExtras(false);
                if (result != null && result.getBoolean("canvasBlocked")) {
                    presentationVisible = false;
                    clear();
                    return;
                }
                large = result != null && result.getBoolean("canvasLarge");
                bouncerVisible = result != null && result.getBoolean("canvasBouncer");
                presentationVisible = result == null || result.getBoolean("canvasVisible", true);
                if (layer != null) layer.setVisibility(presentationVisible && !frozenInAod ? View.VISIBLE : View.INVISIBLE);
                if (frozenLayer != null) frozenLayer.setVisibility(presentationVisible ? View.VISIBLE : View.INVISIBLE);
                worker.post(() -> {
                    try {
                        if (player == null || !firstFrame) return;
                        if (wantedPlaying && presentationVisible && !bouncerVisible && !frozenInAod && !player.isPlaying()) player.start();
                        else if ((!wantedPlaying || !presentationVisible || bouncerVisible || frozenInAod) && player.isPlaying()) player.pause();
                    } catch (IllegalStateException ignored) { }
                });
                effects();
            }
        }, main, 0, null, null);
        main.postDelayed(() -> querying = false, 1500L);
    }
    private static void freezeForAod() {
        if (frozenInAod || texture == null || layer == null) return;
        frozenInAod = true;
        // Capture only once; AOD displays this small static copy without decoding video frames.
        if (texture.isAvailable() && texture.getWidth() > 0 && texture.getHeight() > 0) {
            int width = Math.min(512, texture.getWidth());
            int height = Math.max(1, Math.round(width * texture.getHeight() / (float) texture.getWidth()));
            try { frozenBitmap = texture.getBitmap(width, height); }
            catch (IllegalStateException ignored) { frozenBitmap = null; }
            if (frozenBitmap != null) {
                frozenImage = new android.widget.ImageView(layer.getContext());
                frozenImage.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                frozenImage.setImageBitmap(frozenBitmap);
                View root = layer.getRootView();
                if (root instanceof ViewGroup && root != layer.getParent()) {
                    // The keyguard content shrinks in AOD. Its static background must not inherit that scale.
                    frozenLayer = new FrameLayout(layer.getContext());
                    frozenLayer.setTag("HMCSpotifyCanvasAod");
                    frozenLayer.setClickable(false);
                    frozenLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    frozenDim = new View(layer.getContext());
                    frozenDim.setBackgroundColor(Color.BLACK);
                    frozenLayer.addView(frozenImage, new FrameLayout.LayoutParams(-1, -1));
                    frozenLayer.addView(frozenDim, new FrameLayout.LayoutParams(-1, -1));
                    ((ViewGroup) root).addView(frozenLayer, 0, new ViewGroup.LayoutParams(-1, -1));
                    layer.setVisibility(View.INVISIBLE);
                } else {
                    layer.addView(frozenImage, 1, new FrameLayout.LayoutParams(-1, -1));
                    texture.setVisibility(View.INVISIBLE);
                }
            }
        }
        worker.post(() -> {
            try { if (player != null && player.isPlaying()) player.pause(); }
            catch (IllegalStateException ignored) { }
        });
        // No effect animator in AOD: apply the frosted still frame once.
        if (effectAnimator != null) { effectAnimator.cancel(); effectAnimator = null; }
        targetBlur = shownBlur = Math.max(18, blur) * context.getResources().getDisplayMetrics().density;
        targetDim = shownDim = Math.max(normalDim, coverDim) / 100f;
        drawEffects();
    }
    private static void restoreFromAod() {
        if (!frozenInAod) return;
        frozenInAod = false;
        if (frozenImage != null) {
            frozenImage.setImageDrawable(null);
            if (frozenImage.getParent() instanceof ViewGroup)
                ((ViewGroup) frozenImage.getParent()).removeView(frozenImage);
            frozenImage = null;
        }
        if (frozenLayer != null && frozenLayer.getParent() instanceof ViewGroup)
            ((ViewGroup) frozenLayer.getParent()).removeView(frozenLayer);
        frozenLayer = null; frozenDim = null;
        if (frozenBitmap != null) { frozenBitmap.recycle(); frozenBitmap = null; }
        if (texture != null) texture.setVisibility(View.VISIBLE);
        if (layer != null) layer.setVisibility(presentationVisible ? View.VISIBLE : View.INVISIBLE);
    }
    private static void clear() {
        restoreFromAod();
        if (effectAnimator != null) { effectAnimator.cancel(); effectAnimator = null; }
        targetBlur = targetDim = Float.NaN;
        shownBlur = shownDim = 0f;
        release(); firstFrame = false; desired = "";
        if (layer != null) { layer.animate().cancel(); if (layer.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) layer.getParent()).removeView(layer); }
        layer = null; texture = null; dim = null; videoW = videoH = 0;
    }
    private static void release() { generation++; if (worker != null) worker.post(CanvasBackground::dispose); }
    private static void dispose() {
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        if (surface != null) { surface.release(); surface = null; }
    }
}
