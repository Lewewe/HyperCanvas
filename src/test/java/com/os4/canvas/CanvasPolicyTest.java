package com.os4.canvas;
import org.junit.Test;
import static org.junit.Assert.*;
public class CanvasPolicyTest {
    @Test public void seekingAndBufferingKeepTheActiveSession() {
        for (int state : new int[]{2, 3, 4, 5, 6, 8})
            assertTrue(CanvasPolicy.acceptPlaybackState(state, false));
        assertTrue(CanvasPolicy.acceptPlaybackState(0, true));
        assertTrue(CanvasPolicy.acceptPlaybackState(1, true));
    }
    @Test public void inactiveAndErroredSessionsDoNotClaimANewCanvas() {
        assertFalse(CanvasPolicy.acceptPlaybackState(0, false));
        assertFalse(CanvasPolicy.acceptPlaybackState(1, false));
        assertFalse(CanvasPolicy.acceptPlaybackState(7, false));
        assertFalse(CanvasPolicy.acceptPlaybackState(7, true));
    }
    private static final String A = "spotify:track:0123456789abcdefghijkl";
    private static final String B = "spotify:track:1123456789abcdefghijkl";
    private static final String C = "spotify:track:2123456789abcdefghijkl";
    private static final String D = "spotify:track:3123456789abcdefghijkl";
    @Test public void missingQueueKeepsPreviousCanvas() {
        assertEquals(java.util.Arrays.asList(B, A), new java.util.ArrayList<>(
                CanvasPolicy.window(B, null, null, java.util.Arrays.asList(B, A))));
    }
    @Test public void reverseSkipReusesTheSameThreeVideos() {
        java.util.List<String> forward = new java.util.ArrayList<>(
                CanvasPolicy.window(C, B, null, java.util.Arrays.asList(B, A)));
        assertEquals(java.util.Arrays.asList(B, C, A), new java.util.ArrayList<>(
                CanvasPolicy.window(B, C, null, forward)));
    }
    @Test public void knownNeighborsHavePriorityAndCacheStaysBounded() {
        assertEquals(java.util.Arrays.asList(C, B, D), new java.util.ArrayList<>(
                CanvasPolicy.window(C, B, D, java.util.Arrays.asList(A, B, C))));
    }
    @Test public void identitiesMatchAcrossSpotifyFormats() {
        String id = "0123456789abcdefghijkl";
        assertEquals("spotify:track:" + id, CanvasPolicy.track(id));
        assertEquals(CanvasPolicy.track(id), CanvasPolicy.track("spotify:track:" + id));
        assertEquals(CanvasPolicy.track(id), CanvasPolicy.track("https://open.spotify.com/track/" + id + "?si=test"));
    }
    @Test public void wrongTrackTypesAreRejected() {
        assertEquals("", CanvasPolicy.track("spotify:episode:0123456789abcdefghijkl"));
        assertEquals("", CanvasPolicy.track(null));
        assertEquals("", CanvasPolicy.track("short"));
    }
    @Test public void onlyTrustedHttpsMediaIsAccepted() {
        assertTrue(CanvasPolicy.video("https://canvaz.scdn.co/video.mp4"));
        assertTrue(CanvasPolicy.video("https://canvas.spotifycdn.com/video.mp4?token=test"));
        assertFalse(CanvasPolicy.video("http://canvaz.scdn.co/video.mp4"));
        assertFalse(CanvasPolicy.video("https://canvaz.scdn.co.attacker.test/video.mp4"));
        assertFalse(CanvasPolicy.video("https://user@canvaz.scdn.co/video.mp4"));
        assertFalse(CanvasPolicy.video("https://127.0.0.1/video.mp4"));
        assertFalse(CanvasPolicy.video("file:///data/private.mp4"));
    }
}
