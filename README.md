<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>

<div align="center">
  <h1>HyperCanvas</h1>
  <p><strong>Spotify Canvas video backgrounds on the HyperOS 4 lock screen.</strong></p>
  <p>
    <a href="https://github.com/Lewewe/HyperCanvas/releases"><img alt="Download" src="https://img.shields.io/github/v/release/Lewewe/HyperCanvas?include_prereleases&amp;style=flat-square&amp;label=Download&amp;color=f38caf" /></a>
    <a href="https://t.me/HyperMusicCoverEnhanced"><img alt="Official Telegram group" src="https://img.shields.io/badge/Telegram-Official_group-26A5E4?style=flat-square&amp;logo=telegram" /></a>
    <a href="LICENSE"><img alt="License AGPL-3.0" src="https://img.shields.io/badge/License-AGPL--3.0-ffaaa3?style=flat-square" /></a>
  </p>
</div>

> [!IMPORTANT]
> HyperCanvas is an extension for **[HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced)** only. It does not work on its own or with other HyperMusicCover versions.

## Features

| | |
| --- | --- |
| 🎬 **Canvas background** | The current Spotify track's Canvas plays behind the lock screen player |
| 🌫️ **Blur & dim** | Smooth blur and dimming when big cover art or lyrics are open |
| ⏸️ **Smart pause** | Pauses with the music and on the password screen, frozen frame on AOD |
| 💾 **Small cache** | Keeps at most three videos |
| ⚙️ **Two renderers** | TextureView by default, or experimental OpenGL ES with GPU blur |

Tracks without a Canvas keep the normal artwork background.

## Install

**Requires:** rooted HyperOS 4 (Android 15+), LSPosed and HyperMusicCover-Enhanced.

1. In HyperMusicCover-Enhanced, open **Extensions → HyperCanvas → Download**.
2. Tap **Request hook permissions** and enable Spotify and SystemUI in LSPosed.
3. Tap **Restart hooks to finish setup**, then turn on Canvas.
4. Play a track with a Canvas in Spotify, then lock the screen.

All settings live in HyperMusicCover-Enhanced. Canvas is paused while **Use wallpaper background** is on.

## Build

```sh
./gradlew assembleRelease
```

Needs Android SDK 37. Sign it with the same key as HyperMusicCover-Enhanced (`keystore.properties`), or the two apps cannot talk to each other.

## Status

Experimental, tested on Xiaomi 17 Pro. Other devices and Spotify versions may behave differently.

## Credits

The OpenGL ES renderer builds on [puhboo's HyperCanvas work](https://github.com/puhboo/HyperCanvas/commit/4c9fa0a0e7aa1883e41c50aece308be11be0f270).

License: [AGPL-3.0](LICENSE)
