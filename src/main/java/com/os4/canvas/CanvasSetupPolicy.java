package com.os4.canvas;

/** A heartbeat from a previous process or APK cannot complete a new activation attempt. */
final class CanvasSetupPolicy {
    static boolean accepts(long expectedGeneration, long reportedGeneration, long loadedVersion, long installedVersion) {
        return expectedGeneration > 0 && expectedGeneration == reportedGeneration
                && loadedVersion == installedVersion;
    }
}
