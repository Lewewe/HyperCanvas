package com.os4.canvas;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;

/** Settings live in the extension; the host only controls them through authenticated calls. */
public final class CanvasProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://com.yzc26623.HyperCanvas.bridge");
    private static final String HOST = "com.yzc26623.HyperMusicCoverEnhanced";
    private SharedPreferences prefs;
    @Override public boolean onCreate() {
        prefs = getContext().getSharedPreferences("canvas", 0);
        if (!prefs.getBoolean("defaultDim35", false)) {
            SharedPreferences.Editor editor = prefs.edit().putBoolean("defaultDim35", true);
            // Upgrade the beta's old default while retaining other selected dimming levels.
            if (prefs.getInt("dim", 20) == 20) editor.putInt("dim", 35);
            editor.apply();
        }
        grantBridgeVisibility(getContext());
        return true;
    }
    static void grantBridgeVisibility(Context context) {
        // A URI grant makes the extension visible to Spotify without changing its manifest.
        for (String target : new String[]{"com.spotify.music", "com.android.systemui"}) {
            try { context.grantUriPermission(target, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (RuntimeException e) { android.util.Log.w("MCCanvas", "Bridge visibility grant failed", e); }
        }
    }
    private boolean caller(String target) {
        String[] names = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (names != null) for (String name : names) if (target.equals(name)) return true;
        return false;
    }
    private boolean host() {
        return caller(HOST) && getContext().getPackageManager().checkSignatures(HOST, getContext().getPackageName()) == android.content.pm.PackageManager.SIGNATURE_MATCH;
    }
    private boolean enabled() {
        return CanvasPolicy.enabled(prefs.getBoolean("enabled", false), prefs.getBoolean("hostBlocked", false));
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle data) {
        boolean own = Binder.getCallingUid() == android.os.Process.myUid();
        boolean host = host(), spotify = caller("com.spotify.music"), system = caller("com.android.systemui");
        if (!own && !host && !spotify && !system) throw new SecurityException("Untrusted Canvas bridge caller");
        Bundle out = new Bundle();
        if ("block".equals(method)) {
            if (!host && !own) throw new SecurityException("Only the host can block Canvas");
            if (data != null) {
                prefs.edit().putBoolean("hostBlocked", data.getBoolean("hostBlocked")).commit();
                getContext().getContentResolver().notifyChange(URI, null);
            }
        } else if ("configure".equals(method)) {
            if (!host && !own) throw new SecurityException("Only the host can configure Canvas");
            if (data != null) prefs.edit().putBoolean("enabled", data.getBoolean("enabled"))
                .putBoolean("showInPill", data.getBoolean("showInPill"))
                .putInt("dim", Math.max(0, Math.min(80, data.getInt("dim", 35))))
                .putInt("blur", Math.max(0, Math.min(40, data.getInt("blur", 18))))
                .putInt("coverDim", Math.max(0, Math.min(80, data.getInt("coverDim", 45)))).apply();
            if (enabled()) {
                grantBridgeVisibility(getContext());
                ScopeRequester.request();
            }
            if (!prefs.getBoolean("enabled", false)) {
                prefs.edit().remove("entries").remove("lastTrack").remove("recentTracks").apply();
                CanvasCache.clear(getContext());
            }
            getContext().getContentResolver().notifyChange(URI, null);
        } else if ("window".equals(method)) {
            if (!system && !spotify) throw new SecurityException("Only hooked playback processes can select the playback window");
            java.util.ArrayList<String> recent = new java.util.ArrayList<>();
            try {
                org.json.JSONArray stored = new org.json.JSONArray(prefs.getString("recentTracks", "[]"));
                for (int i = 0; i < stored.length(); i++) recent.add(stored.optString(i));
            } catch (Exception ignored) { }
            // A session without queue neighbors must not evict the previous track's video.
            java.util.LinkedHashSet<String> tracks = CanvasPolicy.window(arg,
                    data == null ? null : data.getString("previous"),
                    data == null ? null : data.getString("next"), recent);
            if (!tracks.isEmpty() && enabled()) {
                prefs.edit().putString("recentTracks", new org.json.JSONArray(tracks).toString()).apply();
                CanvasCache.window(getContext(), tracks);
                try {
                    JSONObject entries = new JSONObject(prefs.getString("entries", "{}"));
                    java.util.ArrayList<String> remove = new java.util.ArrayList<>();
                    java.util.Iterator<String> keys = entries.keys();
                    while (keys.hasNext()) { String key = keys.next(); if (!tracks.contains(key)) remove.add(key); }
                    for (String key : remove) entries.remove(key);
                    prefs.edit().putString("entries", entries.toString()).apply();
                    for (String track : tracks) { String url = entries.optString(track); if (CanvasPolicy.video(url)) CanvasCache.fetch(getContext(), track, url); }
                } catch (Exception ignored) { }
            }
        } else if ("canvas".equals(method)) {
            if (!spotify) throw new SecurityException("Only Spotify can publish Canvas");
            String track = CanvasPolicy.track(data == null ? null : data.getString("track"));
            String url = data == null ? null : data.getString("url");
            if (enabled() && !track.isEmpty() && CanvasCache.allowed(track) && CanvasPolicy.video(url)) {
                try {
                    JSONObject entries = new JSONObject(prefs.getString("entries", "{}"));
                    entries.remove(track);
                    while (entries.length() >= 3) entries.remove(entries.keys().next());
                    entries.put(track, url);
                    prefs.edit().putString("entries", entries.toString()).putString("lastTrack", track).apply();
                    CanvasCache.fetch(getContext(), track, url);
                    getContext().getContentResolver().notifyChange(URI, null);
                } catch (Exception ignored) { }
            }
        } else if ("heartbeat".equals(method)) {
            if (!spotify && !system) throw new SecurityException("Only hooked processes can report status");
            prefs.edit().putLong(spotify ? "spotifySeen" : "systemSeen", System.currentTimeMillis()).apply();
        } else if (!"query".equals(method)) throw new IllegalArgumentException("Unknown Canvas operation");
        out.putBoolean("installed", true);
        out.putBoolean("hostBlocked", prefs.getBoolean("hostBlocked", false));
        out.putBoolean("requestedEnabled", prefs.getBoolean("enabled", false));
        out.putBoolean("enabled", enabled());
        out.putBoolean("showInPill", prefs.getBoolean("showInPill", false));
        out.putInt("dim", prefs.getInt("dim", 35));
        out.putInt("blur", prefs.getInt("blur", 18));
        out.putInt("coverDim", prefs.getInt("coverDim", 45));
        out.putString("scopeState", ScopeRequester.state());
        out.putLong("spotifySeen", prefs.getLong("spotifySeen", 0));
        out.putLong("systemSeen", prefs.getLong("systemSeen", 0));
        out.putString("lastTrack", prefs.getString("lastTrack", ""));
        try {
            JSONObject entries = new JSONObject(prefs.getString("entries", "{}"));
            File[] cached = new File(getContext().getCacheDir(), "canvas-videos").listFiles((dir, name) -> name.endsWith(".mp4"));
            out.putInt("count", cached == null ? 0 : cached.length);
            String track = CanvasPolicy.track(arg);
            if (!track.isEmpty() && enabled()) {
                File file = CanvasCache.file(getContext(), track);
                String url = entries.optString(track);
                if (file != null && file.isFile()) {
                    out.putString("url", URI + "/video/" + track.substring(14));
                } else if (CanvasPolicy.video(url)) CanvasCache.fetch(getContext(), track, url);
            }
        } catch (Exception ignored) { }
        return out;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ((!caller("com.android.systemui") && Binder.getCallingUid() != android.os.Process.myUid())
                || !"r".equals(mode) || !enabled()) throw new FileNotFoundException("Canvas unavailable");
        java.util.List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !"video".equals(path.get(0))) throw new FileNotFoundException("Invalid Canvas path");
        File file = CanvasCache.file(getContext(), path.get(1));
        if (file == null || !file.isFile()) throw new FileNotFoundException("Canvas is not cached");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
