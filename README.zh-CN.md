<p align="center"><a href="README.md">English</a> · <strong>简体中文</strong></p>

# HyperCanvas

为 HyperOS 4 锁屏带来 Spotify Canvas 动态背景，以独立 LSPosed 扩展 APK 的形式运行。

**HyperCanvas 仅支持 [HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced)。它不能作为独立锁屏模块使用，也不支持原始 HyperMusicCover 或其他分支。** 请安装包含 Extensions 页面及 HyperCanvas 集成功能的 HyperMusicCover-Enhanced 版本；不具备这些功能的旧版本不兼容。

## 功能

- 在锁屏播放器后显示当前 Spotify 歌曲的 Canvas。
- 没有 Canvas 的歌曲保持普通封面的行为。
- 显示大封面或歌词时，Canvas 平滑地模糊并暗化。
- Canvas 开启时，可在播放器上切换歌词显示。
- 暂停音乐或进入密码界面时暂停 Canvas。AOD 使用冻结并模糊的画面，打开大封面或歌词时也同样适用。
- 在 HyperMusicCover-Enhanced 中调整背景暗化、额外暗化及模糊程度，普通暗化默认值为 35%。
- 选择是否在胶囊模式下保留 Canvas，并在重新展开播放器时恢复之前的播放器／封面／歌词状态。
- 最多缓存三个 Canvas 视频，关闭扩展时清空缓存。
- 可选择默认的 TextureView 渲染器，或带 GPU 模糊的实验性 OpenGL ES 渲染器。

## 要求与设置

1. 使用已 Root 的 HyperOS 4 设备，以及支持现代 Xposed API 的 LSPosed。APK 要求 Android 15 或更新版本。
2. 安装 HyperCanvas 及兼容的 HyperMusicCover-Enhanced。
3. 在更新后的 HMC-Enhanced 1.0.0 APK 中，点击 HyperCanvas 条目下方的 **Request hook permissions**。在 LSPosed 中启用模块，并为 **Spotify（`com.spotify.music`）** 和 **SystemUI（`com.android.systemui`）** 开启作用域；若出现授权请求，请确认。
4. 点击 **Restart hooks to finish setup**。主模块收到 SystemUI 重启后的确认后才会解锁设置。Spotify 不会自动打开，会在下次启动时加载 Hook。随后进入 HyperCanvas 设置并启用 Canvas。这套设置流程要求 HyperCanvas 0.1.1-beta 或更新版本。
5. 在 Spotify 的正在播放页面打开一首带 Canvas 的歌曲，让 Spotify 提供 Canvas 数据，然后锁屏。

所有设置均在 HyperMusicCover-Enhanced 中管理，HyperCanvas 没有独立的设置界面。主模块开启 **Use wallpaper background（使用壁纸背景）** 时，会暂时停用 HyperCanvas 并禁用其开关。关闭该模式后恢复之前的 HyperCanvas 选择；已安装扩展时，切换该模式会重启 Spotify 和 SystemUI。歌曲必须由 Spotify 提供 Canvas。扩展不会为没有 Canvas 的歌曲生成视频，也不能保证提前获取尚未播放歌曲的 Canvas。

## 工作原理

**Video renderer（视频渲染器）** 设置在下一次显示 Canvas 时生效，无需重启作用域。OpenGL ES 使用独立视频表面、两次低分辨率模糊处理，以及一张用于 AOD 的小尺寸快照。初始化或渲染失败时，本次会话回退到 TextureView。切换到 TextureView 后再切回，可重新尝试 OpenGL ES。默认仍为 TextureView；实验性渲染器在其他设备上的兼容性和性能还需要测试。

Spotify Hook 读取 Canvas 元数据及可用的视频缓存。经过签名校验的通信桥将当前歌曲和视频传给 SystemUI Hook，后者渲染静音循环视频背景，并与 HyperMusicCover-Enhanced 协调。Canvas 视频下载并缓存在本地，不使用麦克风采集。

两个 APK 必须使用相同签名证书，通信桥才能工作。使用不同密钥独立构建的 APK 无法混用。

## 构建

使用 JDK 17 或更新版本、Android SDK 37 和项目附带的 Gradle wrapper。在本地 `local.properties` 中配置 `sdk.dir`，然后运行：

```sh
./gradlew assembleRelease testDebugUnitTest
```

APK 输出到 `build/outputs/apk/release/`。需要签名 release 时，创建本地、不纳入版本控制的 `keystore.properties`，填写 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`，并使用与 HyperMusicCover-Enhanced 构建相同的密钥。未提供该文件时，构建使用本地 debug 密钥。

HyperMusicCover-Enhanced 也将本仓库作为固定版本的 Git 子模块，用于联合开发：

```sh
git clone --recurse-submodules https://github.com/Lewewe/HyperMusicCover-Enhanced.git
```

## 状态

实验性功能。已在运行 HyperOS 4 的 Xiaomi 17 Pro 上测试。Spotify 内部实现和 HyperOS 布局可能因版本或设备而异，其他环境仍需测试。

## 许可证

OpenGL ES 渲染器基于 [puhboo 的 HyperCanvas 实现](https://github.com/puhboo/HyperCanvas/commit/4c9fa0a0e7aa1883e41c50aece308be11be0f270)。

AGPL-3.0，见 [LICENSE](LICENSE)。
