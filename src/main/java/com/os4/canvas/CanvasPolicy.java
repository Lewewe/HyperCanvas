package com.os4.canvas;

import java.net.URI;

/** Only Spotify track identities and HTTPS media CDN URLs cross the extension bridge. */
final class CanvasPolicy {
    static boolean enabled(boolean requested, boolean blocked) { return requested && !blocked; }
    /** Keep a loaded session through buffering/seeking instead of rebuilding its scene. */
    static boolean acceptPlaybackState(int state, boolean currentSession) {
        return state == 2 || state == 3 || state == 4 || state == 5 || state == 6 || state == 8
                || currentSession && state != 7;
    }
    static java.util.LinkedHashSet<String> window(String current, String previous, String next,
                                                java.util.List<String> recent) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        java.util.ArrayList<String> candidates = new java.util.ArrayList<>();
        java.util.Collections.addAll(candidates, current, previous, next);
        candidates.addAll(recent);
        for (String value : candidates) {
            String canonical = track(value);
            if (!canonical.isEmpty()) result.add(canonical);
            if (result.size() == 3) break;
        }
        return result;
    }
    static String track(String value) {
        if (value == null) return "";
        String id = value;
        if (value.startsWith("spotify:track:")) id = value.substring(14);
        else if (value.startsWith("https://open.spotify.com/track/")) {
            id = value.substring(31).split("[?#]", 2)[0];
        }
        return id.matches("[A-Za-z0-9]{22}") ? "spotify:track:" + id : "";
    }
    static boolean video(String value) {
        if (value == null || value.length() > 4096) return false;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("scdn.co") || host.endsWith(".scdn.co")
                    || host.equals("spotifycdn.com") || host.endsWith(".spotifycdn.com");
        } catch (Exception ignored) { return false; }
    }
}
