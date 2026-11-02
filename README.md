# PocketCam

把闲置 Android 手机变成网络摄像头：打开 App 启动服务后，手机即刻通过局域网（或经 frp 内网穿透）向外提供实时画面与声音。

## 功能

- **MJPEG over HTTP**：浏览器或任意 MJPEG 客户端直接观看，默认端口 8080，HTTP Basic 认证
- **RTSP（H.264）**：标准 RTSP 服务，默认端口 8554，支持 TCP 交织与 UDP 传输，可供 VLC / FFmpeg / Home Assistant 等接入
- **FLV 直播**：`/live.flv` 端点输出 FLV 流
- **实时音频**：麦克风 PCM 以 WAV（44.1 kHz / 单声道 / 16 bit）流式输出，网页端一键开关声音
- **网页播放器**：内置页面聚合视频、音频、快照与状态信息，自动适配浏览器自动播放策略
- **画面叠加文字**：时间、电量、镜头朝向与自定义文字，所有输出渠道（MJPEG / RTSP / FLV / App 内预览）一致生效
- **内网穿透**：内置 frpc（基于 frp v0.71.1，经 gomobile 编译），向导式配置（TCP / HTTP 映射、子域名）或直接粘贴完整 TOML
- **开机自启**：设备重启后自动恢复采集服务，可选同时启动 frpc 映射
- **拉起其他应用**：服务启动后按延时自动跳转到指定应用并可定时切回，配合无障碍服务实现无人值守
- **无人值守保活**：看门狗定时恢复服务、相机被抢占后自动重开、无观看者时可自动释放相机

## 主要端点

| 端点 | 说明 |
| --- | --- |
| `/` | 网页播放器（含视频、音频、快照） |
| `/stream.mjpg`（`/video`） | MJPEG 视频流 |
| `/audio.wav`（`/audio`） | WAV 实时音频流 |
| `/live.flv` | FLV 直播流 |
| `/snapshot.jpg` | 当前帧快照 |
| `rtsp://<设备IP>:8554/live` | RTSP 视频流（需在设置中开启） |

除 RTSP 外，所有 HTTP 端点均要求 Basic 认证（用户名 / 密码在「服务器设置」中配置）。

## 构建

- JDK 17+，Android SDK（compileSdk 37），Gradle Wrapper
- Windows：`gradlew.bat assembleDebug`；类 Unix：`./gradlew assembleDebug`
- `app/libs/frpcbridge.aar` 为预编译的 frp 客户端桥接库，随仓库提供，常规构建无需 Go 环境

### 重新编译 frpc 桥接层（可选）

仅在需要升级 frp 时进行：

1. 将 frp v0.71.1 源码克隆到 `third_party/frp`（该目录已在 .gitignore 中排除）
2. 安装 Go 1.26+ 与 `golang.org/x/mobile/cmd/gobind`
3. 在 `frpc-bridge/` 下用 gomobile 编译 `bridge/bridge.go`，将产物放入 `app/libs/`

## 系统要求与已知限制

- `minSdk 17`（Android 4.2+），`targetSdk 25`；Android 14+ 以前台服务（camera|microphone）保持后台采集
- 已验证设备：vivo Y51t L（Android 5.1.1 / API 22）、vivo 新机型（Android 14）
- 部分 OEM 机型需手动授予「自启动」「后台弹出界面」权限，App 内提供跳转指引

详细需求见 [`docs/requirements.md`](docs/requirements.md)。
