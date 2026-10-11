<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>

# HyperCanvas

Spotify Canvas backgrounds for the HyperOS 4 lock screen, built as a separate LSPosed extension.

**HyperCanvas only works with [HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced). It is not a standalone lock screen module and does not support the original HyperMusicCover or other forks.** Install a version of HyperMusicCover-Enhanced that includes the Extensions tab and HyperCanvas integration; older releases without that integration are incompatible.

## Features

- Show the current Spotify track’s Canvas behind the lock screen media player.
- Keep normal artwork behavior for tracks without Canvas.
- Blur and dim Canvas while viewing big cover art or lyrics, with animated transitions.
- Toggle lyrics from the player while Canvas is active.
- Pause Canvas with playback and while entering a password. AOD uses a frozen, blurred frame, including while big cover art or lyrics are open.
- Adjust background dimming, extra dimming and blur in HyperMusicCover-Enhanced. Normal dimming defaults to 35%.
- Choose whether Canvas remains visible in pill mode, and restore the previous player/cover/lyrics state when reopening the player.
- Keep a bounded cache of up to three Canvas videos and clear it when the extension is disabled.
- Choose the default TextureView renderer or an experimental OpenGL ES renderer with GPU blur.

## Requirements and setup

1. Use a rooted HyperOS 4 device with LSPosed supporting the modern Xposed API. The APK requires Android 15 or newer.
2. Install both HyperCanvas and a compatible HyperMusicCover-Enhanced build.
3. In the refreshed HMC-Enhanced 1.0.0 APK, tap **Request hook permissions** below HyperCanvas. Enable the module in LSPosed for **Spotify (`com.spotify.music`)** and **SystemUI (`com.android.systemui`)**, and approve scope requests if prompted.
4. Tap **Restart hooks to finish setup**. The host waits for a fresh confirmation from SystemUI before unlocking settings. Spotify stays closed and loads its hook when you next open it. Then open HyperCanvas settings and enable Canvas. This setup flow requires HyperCanvas 0.1.1-beta or newer.
5. Open a track with Canvas in Spotify’s Now Playing screen so Spotify exposes its Canvas data, then lock the screen.

Settings are managed inside HyperMusicCover-Enhanced; HyperCanvas has no separate settings screen. Enabling **Use wallpaper background** in the host temporarily suspends HyperCanvas and disables its switch. Turning that mode off restores your previous HyperCanvas choice; changing the mode restarts Spotify and SystemUI when the extension is installed. Spotify must provide a Canvas for the track. The extension does not generate missing videos or guarantee that an unseen track’s Canvas can be fetched in advance.

## How it works

The **Video renderer** setting applies when the Canvas is next displayed, without restarting scopes. OpenGL ES uses a separate video surface, two reduced-resolution blur passes, and a single small snapshot for AOD. If initialization or rendering fails, it falls back to TextureView for the current session. Switch to TextureView and back to retry OpenGL ES. The default remains TextureView; device compatibility and performance of the experimental renderer still need testing.

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

The OpenGL ES renderer builds on [puhboo's HyperCanvas work](https://github.com/puhboo/HyperCanvas/commit/4c9fa0a0e7aa1883e41c50aece308be11be0f270).

AGPL-3.0, see [LICENSE](LICENSE).
