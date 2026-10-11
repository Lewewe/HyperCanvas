package com.os4.canvas;
import org.junit.Test;
import static org.junit.Assert.*;
public class CanvasSetupPolicyTest {
    @Test public void ignoresUninitializedOldProcessAndOldApkHeartbeats() {
        assertFalse(CanvasSetupPolicy.accepts(0, 0, 2, 2));
        assertFalse(CanvasSetupPolicy.accepts(2, 1, 2, 2));
        assertFalse(CanvasSetupPolicy.accepts(2, 2, 1, 2));
        assertTrue(CanvasSetupPolicy.accepts(2, 2, 2, 2));
    }
}
