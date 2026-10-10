# HyperCanvas

Spotify Canvas backgrounds for the HyperOS 4 lock screen, built as a separate LSPosed extension.

**HyperCanvas only works with [HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced). It is not a standalone lock screen module and does not support the original HyperMusicCover or other forks.** Install a version of HyperMusicCover-Enhanced that includes the Extensions tab and HyperCanvas integration; older releases without that integration are incompatible.

## Features

- Show the current Spotify track’s Canvas behind the lock screen media player.
- Keep normal artwork behavior for tracks without Canvas.
- Blur and dim Canvas while viewing big cover art or lyrics, with animated transitions.
- Toggle lyrics from the player while Canvas is active.
- Pause Canvas with playback and while entering a password. In Canvas mode, AOD uses a frozen, blurred frame instead of playing video.
- Adjust background dimming, extra dimming and blur in HyperMusicCover-Enhanced. Normal dimming defaults to 35%.
- Choose whether Canvas remains visible in pill mode, and restore the previous player/cover/lyrics state when reopening the player.
- Keep a bounded cache of up to three Canvas videos and clear it when the extension is disabled.

## Requirements and setup

1. Use a rooted HyperOS 4 device with LSPosed supporting the modern Xposed API. The APK requires Android 15 or newer.
2. Install both HyperCanvas and a compatible HyperMusicCover-Enhanced build.
3. Enable HyperCanvas in LSPosed for **Spotify (`com.spotify.music`)** and **SystemUI (`com.android.systemui`)**. Approve scope requests if prompted.
4. Restart the affected processes or reboot, then enable HyperCanvas in **HyperMusicCover-Enhanced → Extensions**.
5. Open a track with Canvas in Spotify’s Now Playing screen so Spotify exposes its Canvas data, then lock the screen.

Settings are managed inside HyperMusicCover-Enhanced; HyperCanvas has no separate settings screen. Enabling **Use wallpaper background** in the host temporarily suspends HyperCanvas and disables its switch. Turning that mode off restores your previous HyperCanvas choice; changing the mode restarts Spotify and SystemUI when the extension is installed. Spotify must provide a Canvas for the track. The extension does not generate missing videos or guarantee that an unseen track’s Canvas can be fetched in advance.

## How it works

The Spotify hook reads Canvas metadata and available cached video data. A signature-checked bridge shares the current track and video with the SystemUI hook, which renders a muted, looping background and coordinates with HyperMusicCover-Enhanced. Canvas videos are fetched and cached locally; no microphone capture is used.

Both APKs must be signed with the same certificate for the bridge to work. Independently rebuilt APKs signed with different keys cannot be mixed.

## Build

Use JDK 17 or newer, Android SDK 37 and the included Gradle wrapper. Configure `sdk.dir` in a local `local.properties` file, then run:

```sh
./gradlew assembleRelease testDebugUnitTest
```

The APK is written to `build/outputs/apk/release/`. For a signed release, create a local, untracked `keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, using the same key as your HyperMusicCover-Enhanced build. Without that file, the build uses the local debug key.

HyperMusicCover-Enhanced also includes this repository as a pinned Git submodule for combined development:

```sh
git clone --recurse-submodules https://github.com/Lewewe/HyperMusicCover-Enhanced.git
```

## Status

Experimental. Tested on Xiaomi 17 Pro running HyperOS 4. Spotify internals and HyperOS layouts may differ between versions or devices; behavior on other configurations needs testing.

## License

AGPL-3.0, see [LICENSE](LICENSE).
