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
    // Keep the asynchronous cache provider alive while its lock-screen client needs it.
    private static ContentProviderClient cacheClient;
    private static void keepCacheClient(boolean keep) {
        if (keep && cacheClient == null) {
            cacheClient = context.getContentResolver().acquireContentProviderClient(CanvasProvider.URI);
        } else if (!keep && cacheClient != null) {
            cacheClient.close(); cacheClient = null;
        }
    }
    private static WeakReference<View> clock = new WeakReference<>(null);
    private static FrameLayout layer;
    private static View keyguardAbove;
    private static TextureView texture;
    private static CanvasGlVideoView glVideo;
    private static int renderer;
    private static boolean glesFailed, surfaceOwned, thawPending;
    private static long freezeGeneration;
    private static View videoView() { return glVideo != null ? glVideo : texture; }
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
    private static long hostRequest;
    private static String lastRenderState = "";
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
                keepCacheClient(enabled && interactive && locked);
                if (!enabled || !interactive || !locked) {
                    watch(null);
                    boolean temporary = enabled;
                    main.post(() -> {
                        backdropAllowed = false; artworkFallback = false;
                        // Big artwork and lyrics keep the same frozen Canvas backdrop in AOD.
                        if (enabled && !interactive && locked && firstFrame && presentationVisible) {
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
                        int selectedRenderer = config.getInt("renderer", 0) == 1 ? 1 : 0;
                        if (renderer != selectedRenderer) {
                            clear(); renderer = selectedRenderer; glesFailed = false;
                        }
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
                        recordRenderState();
                    });
                }
            } catch (Throwable t) { keepCacheClient(false); android.util.Log.w("MCCanvas", "Canvas background unavailable", t); main.post(() -> { artworkFallback = false; clear(); host(false, true); }); }
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
        keyguardAbove = null;
        int keyguardId = anchor.getResources().getIdentifier("keyguard_root_view", "id", "com.android.systemui");
        // Keep both live video and the AOD snapshot outside the OEM scale animation.
        for (Object current = background; keyguardId != 0 && current instanceof View;
                current = ((View) current).getParent()) {
            View view = (View) current;
            if (view.getId() == keyguardId && view.getParent() instanceof ViewGroup) {
                parent = (ViewGroup) view.getParent();
                keyguardAbove = view;
                break;
            }
        }
        layer = new FrameLayout(parent.getContext()); layer.setTag("HMCSpotifyCanvas");
        layer.setClickable(false); layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        layer.setAlpha(0f);
        if (renderer == 1 && !glesFailed) {
            glVideo = new CanvasGlVideoView(parent.getContext(), new CanvasGlVideoView.Callback() {
                @Override public void ready(CanvasGlVideoView view) {
                    if (glVideo == view) playIfReady();
                }
                @Override public void presented(CanvasGlVideoView view) {
                    if (glVideo != view || layer == null) return;
                    if (!firstFrame) {
                        firstFrame = true; host(true, false); effects();
                        layer.animate().alpha(1f).setDuration(200L).start();
                    }
                    finishThaw();
                }
                @Override public void failed(CanvasGlVideoView view, String reason) {
                    if (glVideo == view) fallbackToTexture(reason);
                }
            });
        } else {
            texture = new TextureView(parent.getContext());
            // Keep puhboo's opaque fix for the compatible renderer.
            texture.setOpaque(true);
            final TextureView created = texture;
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                    if (texture == created) playIfReady();
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {
                    if (texture == created) transform();
                }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                    if (texture == created) release();
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture t) {
                    if (texture == created) finishThaw();
                }
            });
        }
        dim = new View(parent.getContext()); dim.setBackgroundColor(Color.BLACK);
        layer.addView(videoView(), new FrameLayout.LayoutParams(-1, -1));
        layer.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        parent.addView(layer, keyguardAbove == null ? parent.getChildCount() : parent.indexOfChild(keyguardAbove),
                new ViewGroup.LayoutParams(-1, -1));
        orderLayer();
        final CanvasGlVideoView pending = glVideo;
        if (pending != null) main.postDelayed(() -> {
            if (glVideo == pending && !firstFrame) fallbackToTexture("No GLES frame after 8 seconds");
        }, 8000L);
        android.util.Log.i("MCCanvas", "Renderer: " + (glVideo == null ? "TextureView" : "OpenGL ES"));
    }
    private static void fallbackToTexture(String reason) {
        if (glVideo == null) return;
        android.util.Log.w("MCCanvas", "GLES fallback: " + reason);
        String url = desired, track = currentTrack;
        glesFailed = true;
        clear();
        if (!context.getSystemService(PowerManager.class).isInteractive()) {
            host(false, true);
            return;
        }
        currentTrack = track; desired = url;
        ensureLayer(); playIfReady();
    }
    private static void orderLayer() {
        if (layer == null || !(layer.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) layer.getParent();
        // This sibling must stay behind all keyguard controls and big artwork.
        if (keyguardAbove != null && keyguardAbove.getParent() == parent) return;
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
        if (videoView() == null || (glVideo != null ? glVideo.videoSurface() == null
                : !texture.isAvailable()) || desired.isEmpty()
                || (desired.equals(failedUrl) && SystemClock.uptimeMillis() < retryAt)) return;
        long token = ++generation;
        String url = desired;
        final CanvasGlVideoView requestedGl = glVideo;
        boolean owned = requestedGl == null;
        Surface next = owned ? new Surface(texture.getSurfaceTexture()) : requestedGl.videoSurface();
        worker.post(() -> {
            if (token != generation) { if (owned) next.release(); return; }
            dispose();
            if (requestedGl != null) requestedGl.releaseRetiredSurfaces();
            surface = next; surfaceOwned = owned;
            try {
                MediaPlayer created = new MediaPlayer(); player = created;
                created.setSurface(next); created.setVolume(0f, 0f); created.setLooping(true);
                created.setOnPreparedListener(p -> { if (token == generation) p.start(); });
                created.setOnVideoSizeChangedListener((p, w, h) -> main.post(() -> { if (token == generation) { videoW = w; videoH = h; transform(); } }));
                created.setOnInfoListener((p, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        if (token == generation && (!wantedPlaying || !presentationVisible || bouncerVisible || frozenInAod)) p.pause();
                        main.post(() -> {
                        if (token != generation || layer == null) return;
                        if (requestedGl == null) {
                            firstFrame = true; host(true, false); effects();
                            layer.animate().alpha(1f).setDuration(200L).start();
                        }
                    });
                    }
                    return false;
                });
                created.setOnErrorListener((p, what, extra) -> { main.post(() -> { if (token == generation) {
                    if (requestedGl != null && glVideo == requestedGl) fallbackToTexture("Decoder failure");
                    else { failedUrl = url; retryAt = SystemClock.uptimeMillis() + 30000L; clear(); host(false, false); }
                } }); return true; });
                created.setDataSource(context, android.net.Uri.parse(url)); created.prepareAsync();
            } catch (Throwable t) { android.util.Log.w("MCCanvas", "Canvas video could not be prepared", t); dispose(); main.post(() -> { if (token == generation) {
                    if (requestedGl != null && glVideo == requestedGl) fallbackToTexture("Decoder failure");
                    else { failedUrl = url; retryAt = SystemClock.uptimeMillis() + 30000L; clear(); host(false, false); }
                } }); }
        });
    }
    private static void transform() {
        if (glVideo != null) { glVideo.setVideoSize(videoW, videoH); return; }
        if (texture == null || videoW <= 0 || videoH <= 0 || texture.getWidth() <= 0 || texture.getHeight() <= 0) return;
        float w = texture.getWidth(), h = texture.getHeight();
        float scale = Math.max(w / videoW, h / videoH);
        Matrix matrix = new Matrix(); matrix.setScale(videoW * scale / w, videoH * scale / h, w / 2f, h / 2f);
        texture.setTransform(matrix);
    }
    private static void effects() {
        if (videoView() == null || dim == null) return;
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
        if (videoView() == null || dim == null) return;
        dim.setAlpha(shownDim);
        RenderEffect effect = shownBlur < 0.5f ? null
                : RenderEffect.createBlurEffect(shownBlur, shownBlur, Shader.TileMode.CLAMP);
        if (glVideo != null) glVideo.setBlur(shownBlur);
        else texture.setRenderEffect(effect);
        if (frozenImage != null) frozenImage.setRenderEffect(effect);
        if (frozenDim != null) frozenDim.setAlpha(shownDim);
    }
    private static void host(boolean ready, boolean temporary) {
        if (querying || context == null) return;
        querying = true;
        final long request = ++hostRequest, sceneGeneration = generation;
        Intent intent = new Intent("com.os4.musiccover.PROBE").setPackage("com.android.systemui")
            .putExtra("frozenInAod", frozenInAod).putExtra("showInPill", showInPill).putExtra("backdropAllowed", backdropAllowed).putExtra("artworkFallback", artworkFallback).putExtra("op", "canvasframe").putExtra("ready", ready).putExtra("temporary", temporary).putExtra("track", currentTrack);
        BroadcastOptions options = BroadcastOptions.makeBasic(); options.setShareIdentityEnabled(true);
        context.sendOrderedBroadcast(intent, null, options.toBundle(), new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (request != hostRequest) return;
                querying = false;
                // A reply for a retired surface must not hide or pause the current Canvas.
                if (sceneGeneration != generation) { host(firstFrame, false); return; }
                Bundle result = getResultExtras(false);
                if (result != null && result.getBoolean("canvasBlocked")) {
                    presentationVisible = false;
                    clear();
                    return;
                }
                large = result != null && result.getBoolean("canvasLarge");
                bouncerVisible = result != null && result.getBoolean("canvasBouncer");
                presentationVisible = result == null || result.getBoolean("canvasVisible", true);
                if (layer != null) layer.setVisibility(presentationVisible
                        && (!frozenInAod || frozenLayer == null) ? View.VISIBLE : View.INVISIBLE);
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
        main.postDelayed(() -> {
            if (request == hostRequest && querying) {
                ++hostRequest;
                querying = false;
            }
        }, 1500L);
    }
    private static void recordRenderState() {
        String state = currentTrack + " renderer=" + renderer + " ready=" + firstFrame
                + " visible=" + presentationVisible + " large=" + large
                + " frozen=" + frozenInAod + " playing=" + wantedPlaying
                + " layer=" + (layer == null ? "none" : layer.getVisibility() + "/" + layer.getAlpha()
                    + "/shown=" + layer.isShown())
                + " surface=" + (surface != null && surface.isValid());
        if (state.equals(lastRenderState)) return;
        lastRenderState = state;
        worker.post(() -> {
            java.io.File file = new java.io.File(context.getCacheDir(), "hypercanvas-render-state.txt");
            try (java.io.FileWriter out = new java.io.FileWriter(file, file.length() < 16384)) {
                out.write(System.currentTimeMillis() + " " + state + "\n");
            } catch (java.io.IOException ignored) { }
        });
    }
    private static void freezeForAod() {
        if (frozenInAod || videoView() == null || layer == null) return;
        frozenInAod = true;
        thawPending = false;
        long capture = ++freezeGeneration;
        worker.post(() -> {
            try { if (player != null && player.isPlaying()) player.pause(); }
            catch (IllegalStateException ignored) { }
        });
        if (effectAnimator != null) { effectAnimator.cancel(); effectAnimator = null; }
        targetBlur = shownBlur = Math.max(18, blur) * context.getResources().getDisplayMetrics().density;
        targetDim = shownDim = Math.max(normalDim, coverDim) / 100f;
        drawEffects();
        if (glVideo != null) {
            glVideo.snapshot(bitmap -> installFrozenFrame(bitmap, capture));
        } else {
            Bitmap bitmap = null;
            if (texture.isAvailable() && texture.getWidth() > 0 && texture.getHeight() > 0) {
                // getBitmap omits TextureView's display transform. Preserve the decoder aspect
                // here and let CENTER_CROP reproduce the live video's crop on the frozen layer.
                int sourceWidth = videoW > 0 ? videoW : texture.getWidth();
                int sourceHeight = videoH > 0 ? videoH : texture.getHeight();
                float scale = Math.min(1f, 1024f / Math.max(sourceWidth, sourceHeight));
                int width = Math.max(1, Math.round(sourceWidth * scale));
                int height = Math.max(1, Math.round(sourceHeight * scale));
                try { bitmap = texture.getBitmap(width, height); }
                catch (IllegalStateException ignored) { }
            }
            installFrozenFrame(bitmap, capture);
        }
    }
    private static void installFrozenFrame(Bitmap bitmap, long capture) {
        if (!frozenInAod || capture != freezeGeneration || layer == null) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        if (bitmap == null) return; // Retain the paused surface if a capture is unavailable.
        dropFrozenFrame();
        frozenBitmap = bitmap;
        frozenImage = new android.widget.ImageView(layer.getContext());
        frozenImage.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        frozenImage.setImageBitmap(bitmap);
        View root = keyguardAbove != null ? (View) layer.getParent() : layer.getRootView();
        if (root instanceof ViewGroup && (keyguardAbove != null || root != layer.getParent())) {
            // The snapshot stays outside the keyguard's AOD scale and clipping.
            frozenLayer = new FrameLayout(layer.getContext());
            frozenLayer.setTag("HMCSpotifyCanvasAod");
            frozenLayer.setClickable(false);
            frozenLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            frozenDim = new View(layer.getContext()); frozenDim.setBackgroundColor(Color.BLACK);
            frozenLayer.addView(frozenImage, new FrameLayout.LayoutParams(-1, -1));
            frozenLayer.addView(frozenDim, new FrameLayout.LayoutParams(-1, -1));
            ViewGroup parent = (ViewGroup) root;
            int index = keyguardAbove != null ? parent.indexOfChild(keyguardAbove) : 0;
            parent.addView(frozenLayer, index, new ViewGroup.LayoutParams(-1, -1));
            frozenLayer.setVisibility(presentationVisible ? View.VISIBLE : View.INVISIBLE);
            layer.setVisibility(View.INVISIBLE);
        } else {
            layer.addView(frozenImage, 1, new FrameLayout.LayoutParams(-1, -1));
            videoView().setVisibility(View.INVISIBLE);
        }
        drawEffects();
    }
    private static void restoreFromAod() {
        if (!frozenInAod) return;
        frozenInAod = false;
        ++freezeGeneration;
        thawPending = frozenImage != null;
        if (videoView() != null) videoView().setVisibility(View.VISIBLE);
        if (layer != null) layer.setVisibility(presentationVisible ? View.VISIBLE : View.INVISIBLE);
        if (glVideo != null) glVideo.requestPresentation();
        else finishThaw(); // TextureView retains its last buffer on the compatible path.
    }
    private static void finishThaw() {
        if (!thawPending || frozenInAod) return;
        thawPending = false;
        dropFrozenFrame();
    }
    private static void dropFrozenFrame() {
        if (frozenImage != null) {
            frozenImage.setImageDrawable(null);
            if (frozenImage.getParent() instanceof ViewGroup)
                ((ViewGroup) frozenImage.getParent()).removeView(frozenImage);
        }
        frozenImage = null;
        if (frozenLayer != null && frozenLayer.getParent() instanceof ViewGroup)
            ((ViewGroup) frozenLayer.getParent()).removeView(frozenLayer);
        frozenLayer = null; frozenDim = null;
        if (frozenBitmap != null) { frozenBitmap.recycle(); frozenBitmap = null; }
    }
    private static void clear() {
        ++freezeGeneration;
        frozenInAod = thawPending = false;
        dropFrozenFrame();
        if (effectAnimator != null) { effectAnimator.cancel(); effectAnimator = null; }
        targetBlur = targetDim = Float.NaN; shownBlur = shownDim = 0f;
        final FrameLayout oldLayer = layer;
        final CanvasGlVideoView oldGl = glVideo;
        ++generation; firstFrame = false; desired = "";
        layer = null; keyguardAbove = null; texture = null; glVideo = null; dim = null; videoW = videoH = 0;
        // Stop the decoder before releasing its output surface or detaching the GL view.
        worker.post(() -> {
            dispose();
            main.post(() -> {
                if (oldGl != null) oldGl.close();
                if (oldLayer != null) {
                    oldLayer.animate().cancel();
                    if (oldLayer.getParent() instanceof ViewGroup)
                        ((ViewGroup) oldLayer.getParent()).removeView(oldLayer);
                }
            });
        });
    }
    private static void release() { generation++; if (worker != null) worker.post(CanvasBackground::dispose); }
    private static void dispose() {
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        if (surface != null && surfaceOwned) surface.release();
        surface = null; surfaceOwned = false;
    }
}
