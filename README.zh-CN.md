<p align="center"><a href="README.md">English</a> · <strong>简体中文</strong></p>

<div align="center">
  <h1>HyperCanvas</h1>
  <p><strong>为 HyperOS 4 锁屏带来 Spotify Canvas 视频背景。</strong></p>
  <p>
    <a href="https://github.com/Lewewe/HyperCanvas/releases"><img alt="下载" src="https://img.shields.io/github/v/release/Lewewe/HyperCanvas?include_prereleases&amp;style=flat-square&amp;label=Download&amp;color=f38caf" /></a>
    <a href="https://t.me/HyperMusicCoverEnhanced"><img alt="官方 Telegram 群组" src="https://img.shields.io/badge/Telegram-Official_group-26A5E4?style=flat-square&amp;logo=telegram" /></a>
    <a href="LICENSE"><img alt="AGPL-3.0 许可证" src="https://img.shields.io/badge/License-AGPL--3.0-ffaaa3?style=flat-square" /></a>
  </p>
</div>

> [!IMPORTANT]
> HyperCanvas 是 **[HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced)** 的专属扩展，不能单独使用，也不支持其他版本的 HyperMusicCover。

## 功能

| | |
| --- | --- |
| 🎬 **Canvas 背景** | 在锁屏播放器后播放当前 Spotify 歌曲的 Canvas |
| 🌫️ **模糊与暗化** | 打开大封面或歌词时平滑模糊并暗化 |
| ⏸️ **智能暂停** | 随音乐和密码界面暂停，AOD 显示冻结画面 |
| 💾 **小缓存** | 最多缓存三个视频 |
| ⚙️ **两种渲染器** | 默认 TextureView，或带 GPU 模糊的实验性 OpenGL ES |

没有 Canvas 的歌曲保持普通封面背景。

## 安装

**要求：** 已 Root 的 HyperOS 4（Android 15+）、LSPosed 和 HyperMusicCover-Enhanced。

1. 在 HyperMusicCover-Enhanced 中进入 **Extensions → HyperCanvas → Download**。
2. 点击 **Request hook permissions**，在 LSPosed 中勾选 Spotify 和 SystemUI。
3. 点击 **Restart hooks to finish setup**，然后开启 Canvas。
4. 在 Spotify 中播放带 Canvas 的歌曲，然后锁屏。

所有设置都在 HyperMusicCover-Enhanced 中。开启 **Use wallpaper background（使用壁纸背景）** 时 Canvas 会暂停。

## 构建

```sh
./gradlew assembleRelease
```

需要 Android SDK 37。必须使用与 HyperMusicCover-Enhanced 相同的签名（`keystore.properties`），否则两个应用无法通信。

## 状态

实验性功能，已在 Xiaomi 17 Pro 上测试。其他设备和 Spotify 版本的表现可能不同。

## 致谢

OpenGL ES 渲染器基于 [puhboo 的 HyperCanvas 实现](https://github.com/puhboo/HyperCanvas/commit/4c9fa0a0e7aa1883e41c50aece308be11be0f270)。

许可证：[AGPL-3.0](LICENSE)
