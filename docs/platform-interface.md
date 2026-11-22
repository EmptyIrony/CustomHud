# 平台接口与显示会话边界

本文件区分公共平台 API 与需要设备确认的适配参数。应用内状态机和方法约定见 `lifecycle.md`。

状态：非官方兼容性草案；目标应用的实际 HUD 输出与权限尚未验证。

## 1. 输出模型

显示链为：获取目标 `Display` → 创建该屏的 Context → 获取该 Context 的 `WindowManager` → 添加透明 `View` → 在 `onDraw(Canvas)` 中绘制 → 停止时移除 View。

无需向业务层提供硬件帧编码或本地二进制传输协议。输出内容是 Android View 的绘制结果；`addView` 返回、View 附着、完成一次绘制、用户确认实际显示是四个不同阶段。

最小 Android API 基线为 29。若开发环境选择更高 minSdk，必须在交付前说明与目标车机的差异。compileSdk、targetSdk、Gradle、AGP、JDK 和 Kotlin 版本需要在新工程中明确固定，不能从本包推定设备当前版本。

## 2. 屏幕发现和选择

| API | 用途 |
|---|---|
| `Context.getSystemService(DisplayManager::class.java)` | 获取显示屏管理器 |
| `DisplayManager.getDisplays()` | 枚举应用当前可见的逻辑屏 |
| `DisplayManager.getDisplay(int)` | 按当前逻辑 ID 查找；不存在时为 null |
| `Display.getDisplayId()`、`getName()`、`getFlags()`、`isValid()` | 记录当前目标的身份描述与状态 |
| `Display.getRealSize(Point)` 或对应版本适用的尺寸 API | 记录屏幕描述；最终绘制尺寸仍使用 View 实际宽高 |
| `registerDisplayListener(listener, handler)` / `unregisterDisplayListener(listener)` | 跟踪目标出现、变化和移除 |

`DisplayDescriptor` 是项目模型，至少包含当前 id、name、flags、尺寸、valid、state、最近发现时间和 session generation。屏幕 ID 不能当作跨重启永久身份。

候选 ID 2、5 只能作为可配置的诊断候选；没有任何约定保证它们始终对应 HUD。默认由用户选择明确目标，记录其描述，并在重建窗口前重新验证。不要因为目标不存在而静默改为主屏或另一块副屏。

`FLAG_PRIVATE`、显示屏的所属用户和系统访问策略会影响窗口创建。枚举成功不代表有输出权限。`DISPLAY_CATEGORY_PRESENTATION` 只是一种展示屏分类，不应作为所有 HUD 的完整清单。

## 3. 上下文和窗口

| 条件 | 调用链 |
|---|---|
| API 29 | `outer.createDisplayContext(display)` → 用返回 Context 创建 View 和获取 WindowManager |
| API ≥ 30 | `outer.createDisplayContext(display).createWindowContext(TYPE_APPLICATION_OVERLAY, null)` → 用返回 Context 创建 View 和获取 WindowManager |

同一会话的 View、资源、WindowManager、窗口类型必须保持一致，不能把主屏 Activity 的 WindowManager 混入目标副屏会话。View 的创建、addView、布局修改和 removeView 均在主线程串行执行。

初始兼容窗口参数：

| 字段 | 值 |
|---|---|
| width / height | `MATCH_PARENT / MATCH_PARENT`，数值均为 -1 |
| type | `TYPE_APPLICATION_OVERLAY`，2038 / 0x7f6 |
| format | `PixelFormat.TRANSLUCENT`，-3 |
| flags | `FLAG_NOT_FOCUSABLE` 8，`FLAG_NOT_TOUCHABLE` 16，`FLAG_KEEP_SCREEN_ON` 128，`FLAG_LAYOUT_NO_LIMITS` 512；按位或为 664 / 0x298 |
| View 背景 | 透明；绘制时只覆盖自己的内容 |

这组参数属于兼容配置，不表示四项 flags 都应不可配置。`KEEP_SCREEN_ON` 必须有明确产品开关与用户可见状态；不要把它视为强制开启 HUD 电源的 API。编辑交互放在主屏预览页，现场 HUD 窗口默认不接受触摸。

窗口操作为 `WindowManager.addView(view, params)`、必要时 `updateViewLayout(view, params)`、停止时 `removeView(view)`。记录操作结果和异常。移除必须使用创建会话时保存的 WindowManager 和确切 View 实例。

透明输出不保证原厂内容的图层顺序。应用不应自动申请或切换其他显示模式来掩盖窗口创建失败。

## 4. 权限与能力

`CapabilityReport` 是项目模型，每项能力必须分别返回 `AVAILABLE / DENIED / MISSING_API / NOT_READY / UNSUPPORTED / UNKNOWN`，并附原因、检查时间和设备 profile。

| 能力 | 条件或接口 | 拒绝时的行为 |
|---|---|---|
| 副屏可见 | DisplayManager 能取得已选择屏 | 保持等待目标状态，不转投其他屏 |
| Overlay | SYSTEM_ALERT_WINDOW 及实际 AppOps/窗口策略；检查 `Settings.canDrawOverlays(context)` | 引导用户到标准设置或展示拒绝原因，不重试轰炸 |
| 前台服务 | FOREGROUND_SERVICE，按实际 Android/targetSdk 声明适用类型 | 现场会话不伪报运行；预览仍可使用 |
| 通知 | 按 Android 版本处理通知渠道和通知权限 | 明确展示实际状态，不能把通知权限等同服务类型权限 |
| 车辆信息 | SDK 类、连接、车辆权限和属性支持 | 相应字段 unavailable，其他组件可继续 |
| 媒体会话 | 已启用的自身通知监听组件或确已获准的 MEDIA_CONTENT_CONTROL | 媒体不可用，时钟等继续 |
| 定位 | 定位开关、精确/粗略位置权限及适用后台条件 | 道路提醒关闭，独立 HUD 渲染继续 |

普通应用不因声明权限或共享 UID 就自动获得系统身份。本包不要求新工程声明 `android.uid.system`。厂商库可用性、系统安装条件、签名要求和特定属性授权均须按现场环境另行确认。

## 5. 前台服务与启动入口

MVP 从用户可见 Activity 的明确启动操作进入 DEVICE 会话。Service 不向其他应用导出；使用明确的应用内命令和可见停止入口。通知反映会话请求和实际窗口状态，不单独作为显示成功证据。

按实际用途与系统版本选前台服务类型。长时间 HUD 显示可评估 `specialUse`：在适用版本声明 FOREGROUND_SERVICE_SPECIAL_USE、service 的 specialUse 类型及 PROPERTY_SPECIAL_USE_FGS_SUBTYPE，用途应如实说明。该选择仍需核对目标系统的支持和应用分发要求。

道路提醒使用定位时应单独满足 location 类型、权限和启动条件。不要为了显示时钟就默认启动定位服务。Android 12 及以上的后台启动限制，以及新版本对 while-in-use 权限、开机接收器的限制，都要按运行版本和 targetSdk 处理。

开机恢复是可选阶段：保存用户期望运行状态，检查恢复开关、当前用户、权限和启动资格；不满足条件时提示用户手动恢复。不能承诺 BOOT_COMPLETED 或 START_STICKY 一定恢复窗口。进程恢复必须新建 generation，不能复用上次 WindowManager/View 引用。

## 6. 不属于最小输出链的候选接口

平台可能提供 `com.ecarx.xui.adaptapi.diminteraction.DimInteraction.create(Context)` 与 `dimProjectionScreenRequest(int,int,int,int,int)`。当前未形成可用、跨设备的投影模式控制契约，因此不纳入 MVP 启动或停止依赖。

相关 INaviInteraction 的导航状态通知也不是路线数据源。应用默认不调用这些模式/导航状态接口。若后续确有产品需求，需要独立的设备文档、权限、状态配对和现场验收契约。

## 7. 公共接口资料

以下链接用于公共 API 说明；开发时仍以目标版本为准：

- [DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager)
- [Context/createWindowContext](https://developer.android.com/reference/android/content/Context#createWindowContext(int,%20android.os.Bundle))
- [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)
- [Display/FLAG_PRIVATE](https://developer.android.com/reference/android/view/Display#FLAG_PRIVATE)
- [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [后台启动前台服务的限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [MediaSessionManager/getActiveSessions](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName))
