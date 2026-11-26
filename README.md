# WinDroid

**WinDroid** 是一个在 Android 手机上运行 Windows 程序的原生兼容层应用（Kotlin + Jetpack Compose + Embedded X11 Server），参考 **Winlator**、**Termux-X11**、**Wine**、**Box64** 与 **PRoot** 开源架构设计，并针对**外接物理鼠标与物理键盘（USB OTG / 蓝牙）**做了深度适配。

---

## 核心特性

### 1. 深度外接物理键鼠支持（核心亮点）
- **双模式物理鼠标引擎**（`MouseInputProcessor`）：
  - **绝对坐标桌面模式（Absolute）**：通过等比缩放视口映射算法（Letterbox/Pillarbox）将外接鼠标精准映射到 X11 屏幕坐标系，适合办公软件与桌面操作。
  - **相对捕获模式（Captured Relative）**：基于 Android 8.0+ 原生 `View.requestPointerCapture()` 捕获原始鼠标增量（Δx, Δy），突破屏幕边缘限制，适合 FPS / 3D 应用与游戏，支持灵敏度与鼠标加速曲线调节。
  - **全按键与滚轮支持**：左键、右键（**自动拦截 Android 系统将右键识别为「返回」的默认行为**，转为标准 X11 Button 3 右键菜单）、中键、侧键前进/后退（Button 8/9），以及垂直/水平滚轮平滑累加器。
  - **触控板回落（Touchpad Fallback）**：未连接外接鼠标时，自动支持单指拖动、轻点左键与双指轻点右键。
- **全功能物理键盘引擎**（`KeyboardInputProcessor` + `X11KeyMap`）：
  - 完整映射 100+ Android `KeyEvent.KEYCODE_*` 到 X11 `evdev+8` 键码与标准 Keysym（含字母/数字及 Shift 符号、F1–F12、方向键、修饰键 Shift/Ctrl/Alt/Win(Meta)、小键盘、OEM 标点）。
  - 焦点丢失自动释放所有已按下按键与鼠标按键，防止卡键。
  - 内置会话热键与快捷组合发送：
    - `Ctrl + Alt + G`：切换鼠标捕获模式（相对捕获 / 绝对桌面）
    - `Ctrl + Alt + M`：展开/收起悬浮控制栏（HUD）
    - `Ctrl + Alt + Shift + Q`：安全退出会话
    - 悬浮 HUD 一键发送 `Win`、`Alt + Tab`、`Ctrl + Alt + Del`、`Esc`
- **外设热插拔实时感知**（`ExternalDeviceMonitor`）：
  - 监听 Android `InputManager.InputDeviceListener`，实时显示已连接的 USB OTG / 蓝牙鼠标与键盘设备。

### 2. 嵌入式 X11 显示与协议服务（Winlator 风格）
- `X11Protocol` & `XServerState`：纯 Kotlin 实现 X11 小端协议包编解码、线程安全 ARGB 帧缓冲与输入事件分发。
- `XServerSocketHost`：监听 Android 抽象 Unix Domain Socket（`tmp/.X11-unix/X0`），与 Wine/Xlib `DISPLAY=:0` 无缝对接。
- `XServerSurfaceView`：60fps Surface 渲染器 + Windows 风格软件光标叠加层。

### 3. Wine + Box64 + PRoot 容器管理与内置交互式演示桌面
- **多容器管理**（`ContainerRepository`）：JSON 持久化配置，支持分辨率预设、图形驱动（Turnip / VirGL / Zink / LLVMpipe）、DirectX 转换层（DXVK / VKD3D / WineD3D / CNC DDraw）、Box64 性能预设（Performance / Intermediate / Compatibility / Stability）、盘符映射（`D:` -> `/sdcard/Download`）及独立键鼠参数。
- **开箱即用的内置交互式演示桌面**（`DemoDesktopRenderer`）：
  - 在尚未导入外部 Wine/Box64 `imagefs` 镜像前，启动容器会自动进入内置 Windows 11 风格演示桌面，提供可拖动记事本窗口、实时鼠标坐标/按键/滚轮计数器、物理键盘实时打字测试与右键上下文菜单，安装 APK 后 10 秒内即可验证外接键鼠体验。
- **安全运行包导入**（`ImageFsManager`）：通过系统文件选择器导入 `.zip` 运行时包（含 Zip-Slip 路径穿越防护与可执行权限设置）。

---

## 模块架构

```text
app/src/main/java/com/windroid/app/
├── core/model/        # 共享领域契约 (ContainerConfig, DisplaySpec, InputConfig, RuntimeStatus, InputEventSink)
├── input/             # 外接键鼠输入引擎 (X11KeyMap, MouseInputProcessor, KeyboardInputProcessor, ExternalDeviceMonitor)
├── xserver/           # 嵌入式 X11 服务与显示层 (X11Protocol, XServerState, XServerSocketHost, XServerSurfaceView)
├── data/              # 容器持久化仓库 (ContainerRepository)
├── runtime/           # Wine/Box64/PRoot 启动器、ImageFs 管理与内置演示桌面 (WineCommandBuilder, ImageFsManager, DemoDesktopRenderer, WineSessionController)
└── ui/                # Jetpack Compose Material 3 界面与会话悬浮 HUD
```

---

## 构建与运行

### 环境要求
- **JDK 17**
- **Android SDK**（API 35 / `compileSdk = 35`，`minSdk = 26` Android 8.0+）
- **Android Studio Ladybug (2024.2+)** 或命令行 Gradle 8.10+

### 方式一：Android Studio（推荐）
1. 在 Android Studio 中选择 **Open**，打开本目录 `WinDroid`。
2. 等待 Gradle Sync 完成。
3. 连接 Android 手机（或模拟器），点击 **Run** 安装并启动。

### 方式二：GitHub Actions 自动构建 APK
仓库已内置 [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml)，推送到 GitHub 后会自动使用 JDK 17 + Gradle 8.10.2 运行全部单元测试并产出可下载的 Debug APK。

---

## 参考的开源项目
- **[Winlator](https://github.com/brunodev85/winlator)**：Android 端 Wine + Box64 + PRoot 容器化架构与 Java XServer 思路
- **[Termux-X11](https://github.com/termux/termux-x11)**：Android 物理键鼠事件捕获与 X11 键码映射最佳实践
- **[Moonlight Android](https://github.com/moonlight-stream/moonlight-android)**：`View.requestPointerCapture()` 相对鼠标与右键拦截机制
- **[Wine](https://www.winehq.org/)** / **[Box64](https://github.com/ptitSeb/box64)** / **[PRoot](https://github.com/proot-me/proot)**：ARM64 Android 上运行 x86_64 Windows 程序的核心底层生态
