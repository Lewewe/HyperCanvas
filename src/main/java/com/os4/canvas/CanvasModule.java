package com.os4.canvas;

import android.app.Application;
import android.content.Context;
import android.os.*;
import android.util.Log;
import java.lang.reflect.*;
import java.util.concurrent.*;
import io.github.libxposed.api.XposedModule;

/** Spotify extraction and SystemUI rendering are isolated from the host module's hooks. */
public final class CanvasModule extends XposedModule {
    private Context context;
    private volatile boolean suspended;
    private String processPackage;
    private int metadataSamples, bridgeRetries;
    private String playingTrack = "", previousTrack = "";
    private final java.util.LinkedHashMap<String, String> candidates = new java.util.LinkedHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "MCCanvasBridge"));
    private final java.util.LinkedHashMap<String, String> sent = new java.util.LinkedHashMap<>();
    @Override public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!pkg.equals("com.spotify.music") && !pkg.equals("com.android.systemui")) return;
        processPackage = pkg;
        trace("Loaded into " + pkg);
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                initialize((Application) chain.getThisObject(), pkg);
                return result;
            });
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Class<?> at = Class.forName("android.app.ActivityThread");
                    Application app = (Application) at.getMethod("currentApplication").invoke(null);
                    if (app != null) initialize(app, pkg);
                } catch (Throwable t) { log(Log.WARN, "MCCanvas", "Application lookup failed", t); }
            });
            if (pkg.equals("com.spotify.music")) spotify(param.getDefaultClassLoader());
            else {
                Class<?> clock = Class.forName("com.android.keyguard.clock.KeyguardClockContainer", false, param.getDefaultClassLoader());
                for (Constructor<?> ctor : clock.getDeclaredConstructors()) hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    Object view = chain.getThisObject();
                    if (view instanceof android.view.View) CanvasBackground.track((android.view.View) view);
                    return result;
                });
            }
        } catch (Throwable t) { trace("Extension hooks unavailable: " + t); }
    }
    private synchronized void initialize(Application app, String pkg) {
        if (context != null) return;
        context = app;
        trace("Application attached in " + pkg);
        long setupGeneration = 0L;
        try {
            Bundle config = context.getContentResolver().call(CanvasProvider.URI, "query", null, null);
            setupGeneration = config == null ? 0L : config.getLong("setupGeneration");
            suspended = config != null && config.getBoolean("hostBlocked");
        } catch (RuntimeException ignored) { }
        requestBridge();
        final long activationGeneration = setupGeneration;
        worker.schedule(() -> {
            try {
                Bundle data = new Bundle();
                data.putLong("setupGeneration", activationGeneration);
                data.putLong("loadedVersion", BuildConfig.VERSION_CODE);
                Bundle response = context.getContentResolver().call(CanvasProvider.URI, "heartbeat", null, data);
                trace("Heartbeat: " + (response == null ? "null" : "received"));
            }
            catch (Throwable t) { trace("Bridge is unavailable: " + t); }
        }, 500, TimeUnit.MILLISECONDS);
        if (suspended) {
            trace("Suspended by host background policy");
            return;
        }
        if (pkg.equals("com.android.systemui")) new Handler(Looper.getMainLooper()).post(() -> CanvasBackground.start(context));
    }
    private void requestBridge() {
        if (context == null) return;
        android.content.Intent intent = new android.content.Intent("com.os4.canvas.REQUEST_BRIDGE")
                .setComponent(new android.content.ComponentName("com.yzc26623.HyperCanvas", "com.os4.canvas.BridgeReceiver"));
        android.app.BroadcastOptions options = android.app.BroadcastOptions.makeBasic();
        options.setShareIdentityEnabled(true);
        context.sendBroadcast(intent, null, options.toBundle());
    }
    private synchronized void trace(String message) {
        log(Log.INFO, "MCCanvas", message);
        try {
            java.io.File file = new java.io.File(context == null
                    ? new java.io.File("/data/data/" + processPackage + "/cache")
                    : context.getCacheDir(), "hypercanvas-diagnostic.txt");
            try (java.io.FileWriter writer = new java.io.FileWriter(file, file.length() < 16384)) {
                writer.write(System.currentTimeMillis() + " " + message + "\n");
            }
        } catch (Exception ignored) { }
    }
    private void spotify(ClassLoader loader) throws Exception {
        hook(android.media.session.MediaSession.class.getDeclaredMethod("setMetadata", android.media.MediaMetadata.class)).intercept(chain -> {
            Object result = chain.proceed();
            if (suspended) return result;
            android.media.MediaMetadata metadata = (android.media.MediaMetadata) chain.getArg(0);
            String track = metadata == null ? "" : CanvasPolicy.track(metadata.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID));
            if (!track.isEmpty()) worker.execute(() -> {
                if (!track.equals(playingTrack)) {
                    previousTrack = playingTrack; playingTrack = track; sent.clear();
                }
                if (context == null) return;
                try {
                    Bundle neighbors = new Bundle(); neighbors.putString("previous", previousTrack);
                    context.getContentResolver().call(CanvasProvider.URI, "window", track, neighbors);
                    for (String item : new String[]{playingTrack, previousTrack}) {
                        String url = candidates.get(item);
                        if (url != null) publish(item, url);
                    }
                } catch (Throwable t) { trace("Playback window unavailable: " + t); }
            });
            return result;
        });
        // Context-track metadata is supplied by Spotify's native player, including cached Canvas.
        Class<?> trackModel = Class.forName("com.spotify.player.model.AutoValue_ContextTrack", false, loader);
        for (Constructor<?> constructor : trackModel.getDeclaredConstructors()) {
            if (constructor.getParameterCount() < 4) continue;
            hook(constructor).intercept(chain -> {
                Object result = chain.proceed();
                if (suspended) return result;
                Object rawTrack = chain.getArg(0), metadata = chain.getArg(2);
                if (rawTrack instanceof String && metadata instanceof java.util.Map) {
                    String track = CanvasPolicy.track((String) rawTrack);
                    Object rawUrl = ((java.util.Map<?, ?>) metadata).get("canvas.url");
                    if (!track.isEmpty() && rawUrl instanceof String && CanvasPolicy.video((String) rawUrl)) {
                        String url = (String) rawUrl;
                        if (context != null) worker.execute(() -> publish(track, url));
                        if (metadataSamples++ < 8) trace("Native track Canvas received for " + track);
                    }
                }
                return result;
            });
        }
        Class<?> canvas = Class.forName("com.spotify.extendedmetadata.extensions.canvasimpl.proto.EntityCanvazResponse$Canvaz", false, loader);
        Field entity = canvas.getDeclaredField("entityUri_"), type = canvas.getDeclaredField("type_");
        entity.setAccessible(true); type.setAccessible(true);
        hook(canvas.getDeclaredMethod("getUrl")).intercept(chain -> {
            Object result = chain.proceed();
            if (suspended) return result;
            try {
                Object owner = chain.getThisObject();
                String track = CanvasPolicy.track((String) entity.get(owner));
                int kind = type.getInt(owner);
                if (metadataSamples++ < 8) trace("Canvas getter: kind=" + kind + ", track=" + track
                        + ", validVideo=" + (result instanceof String && CanvasPolicy.video((String) result))
                        + ", context=" + (context != null));
                if (context != null && kind >= 1 && kind <= 3 && !track.isEmpty() && result instanceof String && CanvasPolicy.video((String) result)) {
                    String url = (String) result;
                    worker.execute(() -> publish(track, url));
                }
            } catch (Throwable t) { Log.w("MCCanvas", "Canvas metadata could not be read", t); }
            return result;
        });
        // This model is also constructed from Spotify's own persisted metadata cache.
        try {
            Class<?> model = Class.forName("p.p7c", false, loader);
            for (Constructor<?> constructor : model.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 12) continue;
                hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    if (suspended) return result;
                    Object rawTrack = chain.getArg(4), rawUrl = chain.getArg(1);
                    if (rawTrack instanceof String && rawUrl instanceof String) {
                        String track = CanvasPolicy.track((String) rawTrack), url = (String) rawUrl;
                        if (context != null && !track.isEmpty() && CanvasPolicy.video(url)) {
                            trace("Canvas model received for " + track);
                            worker.execute(() -> publish(track, url));
                        }
                    }
                    return result;
                });
            }
            Class<?> parser = Class.forName("p.b63", false, loader);
            deoptimize(parser.getDeclaredMethod("a", byte[].class));
        } catch (Throwable t) { trace("Spotify cached-model adapter unavailable: " + t.getClass().getSimpleName()); }
        trace("Spotify Canvas hook installed");
    }
    private void publish(String track, String url) {
        if (suspended) return;
        candidates.put(track, url);
        while (candidates.size() > 64) candidates.remove(candidates.keySet().iterator().next());
        if (!track.equals(playingTrack) && !track.equals(previousTrack)) return;
        try {
            Bundle config = context.getContentResolver().call(CanvasProvider.URI, "query", null, null);
            if (config == null || !config.getBoolean("enabled")) { sent.clear(); return; }
            if (url.equals(sent.get(track))) return;
            Bundle data = new Bundle(); data.putString("track", track); data.putString("url", url);
            context.getContentResolver().call(CanvasProvider.URI, "canvas", null, data);
            bridgeRetries = 0;
            sent.put(track, url);
            while (sent.size() > 64) sent.remove(sent.keySet().iterator().next());
            trace("Canvas matched to a Spotify track");
        } catch (Throwable t) {
            trace("Canvas bridge failed: " + t.getClass().getSimpleName());
            if (bridgeRetries++ < 3) {
                requestBridge();
                worker.schedule(() -> publish(track, url), 1000, TimeUnit.MILLISECONDS);
            }
        }
    }
}
