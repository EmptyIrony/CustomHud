# 后视摄像头接入（Zeekr 相机 SDK，zeekr:202）

来源：CarLight 项目 2026-11-06 的“摄像头视频流”页面。`app/src/main/java/dev/customhud/camera/ZeekrStreamCamera.java` 是从 CarLight 原样复制的封装（只改了包名）；`reference/carlight-camera/CameraStreamActivity.java.txt` 是 CarLight 中已实车出图的调用页面，仅作参考，不参与编译。

## 已实车确认的事实

- 车机：ZEEKR 007（内部代号 dc1e），Android 12 / SDK 32。CarLight 与 CustomHUD 相同：`android:sharedUserId="android.uid.system"`，AOSP platform 测试证书（SHA-256 c8a2e9bc…2ab8）。
- CarLight 用下列参数在中控屏 TextureView 出图正常：`zeekr:202`、1600×900、30 fps、水平镜像开、`useGraphicBuffer=false`、原始帧回调关。
- 标准 `CarEvsManager` 路径在这台车上不可用（CarEvsService `UNAVAILABLE / Not connected to HAL service`），不要再尝试。

## 未验证（CustomHUD 必须自己确认）

- 在副屏（仪表）上的 `TYPE_APPLICATION_OVERLAY` 窗口内放 TextureView 并出图。
- CustomHUD 与 CarLight / 原厂 `com.zeekr.stream` / 倒车影像同时使用 202 时的冲突行为（可能报 `onError code=1 CAMERA_IN_USE`，或已打开的一方被断开）。
- 长时间运行的发热、帧率、休眠唤醒后的恢复。
- debug 包（`dev.customhud.debug`，非 platform 签名，非 system uid）大概率无法加载或连不上服务；只用 platform 签名的 release 包实测。

## 封装做了什么（来自反编译 com.zeekr.stream 内 ZeekrCameraSDK v4.2.5）

1. 用 `createPackageContext("com.zeekr.stream", INCLUDE_CODE | IGNORE_SECURITY)` 在本进程加载 SDK 代码与原生库（`libZeekrCameraJni` 及 Nano* / ffmpeg 依赖），耗时较长，在封装自己的后台线程完成。
2. 给 SDK 一个包装 Context：bindService / 包名 / uid 用本应用，assets / resources / classLoader 用目标包。直接传 package context 会让 SDK 的 `getApplicationContext()` 为 null，其工作线程随后在 `native_create(getAssets())` 空指针，整个进程崩溃。
3. SDK 经 `bindService(action com.zeekr.camera.service.CameraService, package com.zeekr.camera.service)` 注册客户端；它在自己的线程上等待 `onServiceConnected` 最多 3 秒，而该回调到达主线程。**主线程任何时候都不能为等待相机而阻塞。**
4. 参数字段真名是 `mirror`（jadx 显示为 `f524mirror`，反射用后者必然失败）。
5. SDK 的 `close()` 是异步的。`close(Runnable)` 排在 SDK 工作队列之后回调，到那时才可以释放 Surface / SurfaceTexture。
6. 每帧回调（渲染线程 `onDrawFrame`、相机线程 `onFrameCallback`）只做计数；统计每 500 ms 回主线程一次。原始帧只在 `callbackFormat != 0` 时存在，HUD 不需要，保持关闭。

## API

```java
ZeekrStreamCamera cam = new ZeekrStreamCamera(context, listener);   // 不加载任何东西
cam.open(surface, surfaceWidth, surfaceHeight, config);             // 立即返回；已开着会先关再开
cam.updateSurface(surface, w, h);                                   // View 尺寸变化
cam.close(() -> { /* 主线程：现在可以释放 Surface */ });
cam.release();                                                      // 最终释放（native destroy、最后一个实例时解绑服务）

new ZeekrStreamCamera.Config("zeekr:202", 1600, 900, 30,
        /*mirror*/ true, /*useGraphicBuffer*/ false, ZeekrStreamCamera.CALLBACK_OFF);
```

`Listener`（均为 Java default 方法，按需覆写；除 `onRawFrame` 外都在主线程）：

| 回调 | 含义 |
|---|---|
| `onState(State, detail)` | `IDLE → LOADING → OPENING → STARTED`；另有 `STOPPED / DISCONNECTED / RECONNECTED / ERROR / RELEASED` |
| `onFirstFrame(index, sinceOpenMs)` | SDK 已渲染第一帧，可隐藏占位 |
| `onCameraError(index, code, name)` | 1 占用、2 同时使用过多、3 禁用、4 设备、5 服务、6 5 秒无纹理（取流卡住） |
| `onStats(Stats)` | 2 Hz：已渲染帧数、渲染 fps、首帧耗时 |
| `onEvent(line)` | 带时间戳的诊断行，写入 HudLog |

加载失败（`ClassNotFoundException` / `UnsatisfiedLinkError`）后同一实例不再重试；同一进程内类初始化失败需要重启进程。

SDK 另有 `setRenderFrameRate(float)`、`setRenderEnable(boolean)`（封装未暴露，未验证），如需降低 HUD 渲染负载可评估。

## 接入要点

- HUD 窗口现在只有一个 `HudCanvasView`。要显示画面，窗口根需要改为容器（例如 FrameLayout），里面放一个 TextureView（相机）和原有 Canvas 视图；TextureView 跟随“后视”组件的几何（16:9）定位，Canvas 层其余组件照常绘制。TextureView 需要硬件加速窗口。
- `onSurfaceTextureAvailable` 后再 `open`；`onSurfaceTextureDestroyed` 返回 `false`，等 `close(...)` 回调后再 `release()` SurfaceTexture。
- 与 DisplaySession 生命周期对齐：会话 suspend / 目标屏丢失 / stop → 先 `close`；resume → 重新 `open`；removeView 前确保相机已关闭；服务销毁 → `release`。
- `onError` / `DISCONNECTED`：退避重试（例如 2 s、5 s、10 s，上限），不要紧密循环重开。
- 相机不可用时组件显示占位，不影响其他 HUD 组件与数据源。

## CustomHUD 实现（2026-11-06）

- 组件 `REAR_CAMERA`（“后视”），schemaVersion 1 增量类型（见 `layout-and-rendering.md`）。默认 `cx .50 cy .50 sc .90`：在 2560×536 的仪表上为 858×482、左边 x≈851，车速区（左侧）不被覆盖。位置 / 大小在「布局」页下方的“仪表”预览条里拖动和调整；PREVIEW 只画占位，不打开相机。
- 画面所在屏幕：默认屏幕 1（用户确认 HDMI 1 = 仪表，HDMI 2 = HUD），「HUD」页“后视摄像头”卡片可切换，下次启动生效。与 HUD 目标屏不同时，`HudService` 开第二个 `DisplaySession`（`CLUSTER_*` 日志）在该屏放一个同样透明、同 WindowSpec 的窗口，只画后视组件，随 HUD 自动启动 / 停止；相同时嵌在 HUD 窗口里。
- 窗口根 `HudWindowRoot`：TextureView（按 `CameraGeometry` 定位，`setOpaque(false)`）+ 原 `HudCanvasView`。隐藏时 TextureView 设为 INVISIBLE（保留 SurfaceTexture、遮住旧帧）；未出图时 Canvas 在框内画不透明占位和原因。
- 显示模式：常显 / 非 P 挡（默认，P 挡不订阅）/ 仅 R 挡 / 关。挡位优先 ECARX `0x200200`（`adapters/EcarxGearSource.java`，复制自 CarLight 换挡音效；先读基准再订阅，1–60 s 重连退避），android.car `GEAR_SELECTION` 在 5 s 内新鲜时兜底。都不可用时需要挡位的模式显示“挡位不可用”并记 `GEAR_UNAVAILABLE`，不猜挡位、不打开相机。
- 生命周期（`camera/RearCameraController.kt`，纯 Kotlin，有单元测试）：会话 ATTACHED + Surface 就绪 + 组件启用 + 模式允许才 open；suspend / 目标屏丢失 / stop / 组件禁用 / 换到 P → close；resume 重新 open。窗口 `remove()` 先 close，close 回调后才 `removeView`（DisplaySession 的 2 s 清理超时会改用 `removeViewImmediate`，SurfaceTexture 仍等 close 回调才释放）。`onSurfaceTextureDestroyed` 返回 false。服务销毁 → 等 close 后 `release()`（3 s 兜底）。
- 失败：SDK ERROR / DISCONNECTED / 15 s 未出图 → close，2 s、5 s、10 s、之后每 30 s 重试；连续出图 30 s 后退避归零。失败只影响该组件。
- 日志 `CAMERA`：SDK 加载耗时与 open 参数（封装原有事件）、状态变化、首帧耗时、`onError` 码与名称（`CAMERA_ERROR_n`）、每 10 s 渲染 fps、挡位与判定变化；均带会话 generation。挡位源日志为 `GEAR`。

### 闪烁修正（2026-11-06 晚，实车视频 IMG_4834）

- 现象：仪表出图正常但闪烁。逐帧看，框内底下的原车导航地图（路名、道路）隔帧透出；框内亮度在 ~187 / ~196 间几乎逐帧交替（手机 30 fps 下约 15 Hz），与相机 30 fps 双缓冲轮换的节奏一致。帧迟到时 TextureView 会保留上一帧，不会出现“没渲染”的空帧。
- 原因（推断）：64795cc0 把 TextureView 设为非不透明，SDK 两个缓冲的 alpha 不一致时，透明窗口在合成时与仪表底层混合。CarLight 的 TextureView 保持默认不透明。
- 修正：TextureView 不透明 + 相机框下垫纯黑 View，框内输出 alpha 恒为 1。「HUD」页后视卡片新增对照开关“画面：不透明 / 透明（旧版）”和“相机帧率 30 / 15”（下次打开生效），日志 `texture opaque=…`、`open request … @fps`。

### 层级修正（2026-11-07 实车确认）

- 240 fps 慢动作（IMG_4835）显示：闪的是叠在相机画面上方、约 30 Hz 淡入淡出的原车导航地图；不透明 / 15 fps 对照均无区别。
- 仪表相机窗口改用 `TYPE_SECURE_SYSTEM_OVERLAY`（2015，需 `INTERNAL_SYSTEM_WINDOW`，system uid）后不再闪：地图是屏幕 1 上的 Android 窗口，层级高于普通悬浮窗。红色 P 等指示灯在 Android 之外叠加，仍显示在最上层；车速不被遮挡。
- 默认层级即安全覆盖层；「HUD」页可切回其他层级。不透明、帧率、静止白块、窗口列表保留为诊断开关。
