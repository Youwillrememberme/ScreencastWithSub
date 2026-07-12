# 字幕投屏 (SubCast)

读取本地视频 + 字幕,把字幕烧录进视频后经 **DLNA** 投屏到智能电视(目标:小米/红米)。原生 Kotlin + Jetpack Compose。

## 已构建 APK

`C:\Users\Administrator\Desktop\SubCast-debug.apk` -- debug 签名,可直接安装(需开启"未知来源")。约 135MB(含 ffmpeg-kit 多架构原生库)。

## 功能

- 视频 / 字幕文件选择(SAF)
- 字幕解析:SRT / VTT / ASS / SSA
- 非 UTF-8 编码自动检测(UTF-8 严格校验 -> GB18030 回退,解决 GBK 中文乱码)
- 字幕调整:时间同步偏移 / 字号 / 位置 / 颜色 / 字体 -> 生成 ASS 中间格式
- **双语字幕**:主+副字幕按时间轴合并(主在上、副在下)
- FFmpeg 视频转码 + 字幕烧录(单次 `subtitles` 滤镜,full-gpl 含 libass);预转码 / 实时流式两种模式
- 本地 HTTP 媒体服务(NanoHTTPD,支持 Range 拖动进度)
- DLNA 投屏与控制(jUPnP):设备发现 / 推送 / 播放暂停 / Seek / 音量 / 状态轮询
- 续播记录(Room)

## 关键依赖与网络注意

- **ffmpeg-kit**:已从 Maven Central 移除;改用**阿里云 Maven 镜像**缓存(`settings.gradle.kts` 已配置 `maven.aliyun.com`)。若阿里云镜像失效,需自行提供 `ffmpeg-kit-full-gpl-6.0-2.aar` 放入 `app/libs/` 并改为本地依赖。
- **DLNA 用 jUPnP**(Cling 的活跃 fork,在 Maven Central)。Cling 不在 Central,已弃用。
- `github.com` 在本机被 Windows schannel 证书吊销检查拦截:`curl` 加 `--ssl-no-revoke` 可绕过;Gradle/Java 不受影响。

## 构建方法

> **重要**:项目路径不能含中文(AGP 会拒绝非 ASCII 路径)。可用的 ASCII 工作副本在 `C:\sc\project\`;便携工具链在 `C:\sc\tools\`(JDK 17 / Android SDK 34 / Gradle 8.9,免安装)。

命令行构建:
```bat
set JAVA_HOME=C:\sc\tools\jdk
set ANDROID_HOME=C:\sc\tools\sdk
cd /d C:\sc\project
C:\sc\tools\gradle\bin\gradle.bat assembleDebug
```
APK 输出:`C:\sc\project\app\build\outputs\apk\debug\app-debug.apk`

或用 Android Studio 打开 `C:\sc\project\`(首次同步自动拉取依赖)。

## 已知限制

- **DLNA(jUPnP)未经真机实测**:jUPnP 在 Android 上的控制点集成(multicast lock、SOAP 控制)按标准写法实现,但需在小米电视上实测;若发现/投屏异常需按设备调试。
- **实时流式**:高分辨率 + 老旧手机可能卡顿;建议默认预转码。
- **软字幕直传快路径**:当前统一走"转码 + 烧录"(最稳,契合"明确需视频转码兜底")。SRT/VTT + 电视支持时跳过转码的优化未做。
- **图标**:占位矢量图标。

## 架构(模块)

| 模块 | 路径 | 说明 |
|---|---|---|
| 字幕 | `subtitle/` | 解析、编码检测、AssWriter 生成中间格式、双语合并 |
| 转码 | `transcode/` | Transcoder(ffmpeg-kit)、MediaProbe |
| 媒体服务 | `media/` | MediaServer(NanoHTTPD + Range)、NetworkUtil |
| 投屏 | `dlna/` | DlnaController(jUPnP)、DidlBuilder |
| 编排 | `cast/` | CastEngine 串联全链路、CastState |
| 数据 | `data/` | Room 续播记录 |
| UI | `ui/` | CastViewModel + CastScreen(Compose) |

链路:`选片 -> 字幕解析+ASS -> FFmpeg 转码+烧录 -> 本地 HTTP -> DLNA 推送 -> 控制+轮询`
