package com.os4.canvas;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** A bounded video cache; disabling the extension invalidates every pending download. */
final class CanvasCache {
    private static final long MAX_VIDEO = 16L * 1024 * 1024, MAX_TOTAL = 48L * 1024 * 1024;
    // At most three cached tracks can download; a slow neighbor cannot block the live track.
    private static final ExecutorService worker = Executors.newFixedThreadPool(3);
    private static final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private static final Set<String> pending = new HashSet<>();
    private static volatile long generation;
    private static volatile Set<String> window = java.util.Collections.emptySet();
    static synchronized void window(Context context, Set<String> tracks) {
        window = java.util.Collections.unmodifiableSet(new HashSet<>(tracks));
        File[] files = new File(context.getCacheDir(), "canvas-videos").listFiles();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".mp4") && !tracks.contains("spotify:track:" + name.substring(0, name.length() - 4))) file.delete();
        }
    }
    static boolean allowed(String track) { return window.isEmpty() || window.contains(track); }
    static File file(Context context, String track) {
        String canonical = CanvasPolicy.track(track);
        if (canonical.isEmpty()) return null;
        return new File(new File(context.getCacheDir(), "canvas-videos"), canonical.substring(14) + ".mp4");
    }
    static synchronized void clear(Context context) {
        generation++;
        pending.clear();
        window = java.util.Collections.emptySet();
        File[] files = new File(context.getCacheDir(), "canvas-videos").listFiles();
        if (files != null) for (File file : files) file.delete();
    }
    static synchronized void fetch(Context context, String track, String url) {
        File target = file(context, track);
        if (target == null || !allowed(track) || target.isFile() || !pending.add(track)) return;
        long token = generation;
        worker.execute(() -> download(context, track, url, target, token));
    }
    private static void download(Context context, String track, String url, File target, long token) {
        long started = android.os.SystemClock.elapsedRealtime();
        HttpURLConnection connection = null;
        java.util.concurrent.atomic.AtomicReference<HttpURLConnection> active = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        // Bound the entire attempt, including redirects and a server that dribbles bytes.
        ScheduledFuture<?> deadline = deadlines.schedule(() -> {
            expired.set(true);
            HttpURLConnection running = active.get();
            if (running != null) running.disconnect();
        }, 6, TimeUnit.SECONDS);
        File temporary = new File(target.getParentFile(), target.getName() + "." + token + ".part");
        try {
            if (token != generation || !allowed(track)) return;
            target.getParentFile().mkdirs();
            for (int redirects = 0; redirects <= 4; redirects++) {
                if (expired.get()) throw new IOException("Canvas deadline exceeded");
                if (!CanvasPolicy.video(url)) throw new IOException("Unsupported Canvas host");
                connection = (HttpURLConnection) new URL(url).openConnection();
                active.set(connection);
                if (expired.get()) throw new IOException("Canvas deadline exceeded");
                connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
                // A fresh request must not inherit a pooled socket suspended on screen-off.
                connection.setRequestProperty("Connection", "close");
                connection.setInstanceFollowRedirects(false);
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect target");
                    url = new URL(new URL(url), location).toString();
                    connection.disconnect(); connection = null;
                    continue;
                }
                if (status != 200 || connection.getContentLengthLong() > MAX_VIDEO) throw new IOException("Canvas download rejected");
                long size = 0;
                try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (expired.get() || token != generation || !allowed(track) || (size += count) > MAX_VIDEO) throw new IOException("Canvas download canceled");
                        output.write(buffer, 0, count);
                    }
                }
                if (size == 0) throw new IOException("Empty Canvas");
                synchronized (CanvasCache.class) {
                    if (token != generation || !allowed(track)) return;
                    if (!temporary.renameTo(target)) throw new IOException("Canvas cache could not be committed");
                    File[] files = target.getParentFile().listFiles((dir, name) -> name.endsWith(".mp4"));
                    if (files != null) {
                        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                        long total = 0; for (File file : files) total += file.length();
                        for (int i = 0; i < files.length && (total > MAX_TOTAL || files.length - i > 3); i++) {
                            if (!files[i].equals(target) && files[i].delete()) total -= files[i].length();
                        }
                    }
                }
                context.getContentResolver().notifyChange(CanvasProvider.URI, null);
                diagnostic(context, track + " download " + (android.os.SystemClock.elapsedRealtime() - started) + "ms " + size + " bytes");
                return;
            }
            throw new IOException("Too many Canvas redirects");
        } catch (IOException error) {
            diagnostic(context, track + " download failed after " + (android.os.SystemClock.elapsedRealtime() - started) + "ms: " + error.getClass().getSimpleName());
            // A failed download leaves the existing lock-screen cover unchanged.
        } finally {
            deadline.cancel(false);
            active.set(null);
            if (connection != null) connection.disconnect();
            temporary.delete();
            synchronized (CanvasCache.class) { if (token == generation) pending.remove(track); }
        }
    }
    private static synchronized void diagnostic(Context context, String message) {
        File file = new File(context.getCacheDir(), "canvas-download-times.txt");
        try (FileWriter writer = new FileWriter(file, file.length() < 16384)) {
            writer.write(System.currentTimeMillis() + " " + message + "\n");
        } catch (IOException ignored) { }
    }
}
