# Z10 BOX 构建

Z10 是 x86_64 车机版本。应用默认使用 ExoPlayer/Android MediaCodec，MPV 和 ARM 软解库不会进入 APK；QuickJS 和 Chaquopy 仍按 x86_64 打包，便于运行网站配置中的脚本源。

应用基线为 FongMi/TV 5.5.6（`4573249cd6679c6becca9957f6a011bf6c4a5afd`），播放器为 FongMi/media（`3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d`）。当前 TV 最新版依赖尚未公开的播放器接口，因此使用公开源码可构建的基线。

先准备 Android SDK（API 36、37，NDK 28.2.13676358）、JDK 21 和 Python 3.10。播放器 AAR 是 `FongMi/media` 的公开提交，生成文件被 git 忽略，首次构建前运行：

```sh
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
./scripts/prepare_media.sh
./gradlew :app:assembleMobileX86_64Debug \
  -Pz10BuildPython=/path/to/python3.10
```

APK 输出在 `app/build/outputs/apk/mobileX86_64/debug/`。需要遥控器布局时，将任务名中的 `Mobile` 换成 `Leanback`。

发布版使用独立 release 证书，不把 keystore 或密码提交到仓库。可在被 Git 忽略的 `local.properties` 中配置：

```properties
storeFile=/absolute/path/to/z10-box-release.jks
storePassword=<store-password>
keyAlias=z10-box
keyPassword=<key-password>
```

CI 可使用对应的 `Z10_RELEASE_STORE_FILE`、`Z10_RELEASE_STORE_PASSWORD`、`Z10_RELEASE_KEY_ALIAS`、`Z10_RELEASE_KEY_PASSWORD` 环境变量。两个界面使用相同包名 `com.z10.box`，会互相覆盖安装，Z10 中控优先使用 Mobile 触屏版。Debug 版与 release 版证书不同，首次切换签名需要卸载旧 Debug 版，之后可正常覆盖升级。

首页是 HTML 站点，应用读取站点的 `config.json`、`list.txt` 和 `livelist.txt`，再选择其中的 TVBox JSON 和 M3U/TXT 地址。清单和最近一次成功结果会缓存到应用私有目录，网站暂时不可用时仍可启动。

默认清单包含 55 个点播源与 2 个直播源，点播按网站权重优先使用饭太硬。设置页面右上角菜单提供“更新内置数据源”和“使用默认数据源”；点播/直播旁的历史入口可切换已导入线路。缓存清单不包含视频文件，播放仍需要有效网络和可用的第三方来源。

“更新内置数据源”成功后会立即重新加载当前使用的默认点播/直播配置；已选的自定义线路保持原样。“使用默认数据源”可随时切回网站默认入口。

已验证两种界面构建、签名、包名、111 个原生 ELF 文件的 x86_64 架构，以及清单解析/缓存格式。尚未连接 Z10 真机验证启动、触控和解码。MPV、FFmpeg 扩展软解，以及仅有 ARM 库的 JianPian、Thunder、ForceTech 等特殊协议不在本次支持范围内。

检查 APK 架构和内置清单：

```sh
python3 verification/check_apk.py app/build/outputs/apk/mobileX86_64/debug/app-mobile-x86_64-debug.apk
```
