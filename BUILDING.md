# CustomHUD 构建与手动验收

## 固定工具链

- JDK 21（源码/字节码目标 17）
- Gradle Wrapper 8.14.3（SHA-256 已写入 `gradle-wrapper.properties`）
- Android Gradle Plugin 8.13.2，Kotlin 2.0.21
- compileSdk / targetSdk 34，minSdk 29，Build Tools 35.0.0（目标车机为 Android 12 / SDK 32）
- 原生 Activity / View / Canvas，无 AndroidX、厂商 SDK 或参考 APK 依赖

工具链与 CarLight 对齐：Linux arm64 开发容器上 Google 的 aapt2 只有 x86_64 版，改用 Ubuntu 的 aapt2，而它读不了 API 35 的 `android.jar`，所以 compileSdk 定为 34。

本机 SDK 路径只写入被 `.gitignore` 排除的 `local.properties`（目前是 Mac 路径）。`local.properties` 优先于 `ANDROID_HOME`，所以 Linux 容器上不要改它，改用脚本在副本中编译：

```bash
tools/build-linux-arm64.sh            # 默认 testDebugUnitTest lintDebug assembleDebug assembleRelease
# 产物复制到 app/build/outputs/apk/linux/（debug 为普通 debug 签名，release 未签名，平台签名另行处理）
```

Mac / x86 主机直接使用：

```bash
./gradlew clean testDebugUnitTest assembleDebug lintDebug --no-daemon
```

每次 Gradle 配置都会生成新的随机 `buildId`，写入 `BuildConfig.BUILD_ID` 和 APK 文件名。同一次命令中的测试与 APK 使用同一 buildId。Debug applicationId 为 `dev.customhud.debug`；正式 applicationId 为 `dev.customhud`。

```bash
ls app/build/outputs/apk/debug/*.apk
shasum -a 256 app/build/outputs/apk/debug/*.apk
```

## 架构

单 `app` 模块按职责分包：

- `settings`：PREVIEW、目标屏选择、能力、启停、导入导出和可见日志
- `display.session`：可替换平台的状态机、generation、恢复和资源所有权
- `display.android` / `display`：副屏 Context/WindowManager 与前台服务
- `renderer`：不可变帧、Canvas 组件、重绘合并和组件异常隔离
- `layout`：schemaVersion 1、严格完整替换、patch 和原子持久化
- `data` / `adapters`：逐字段数据模型、显式 mock、ECARX AdaptAPI 车况（共享连接 + 轮询）与挡位订阅

## 用户手动现场操作

开发助手不执行安装、车辆连接、参考 APK 运行或现场窗口启动。由用户安装下文平台签名的 Debug APK 后：

1. 打开 CustomHUD，确认 PREVIEW 显示固定 `10:08` 和“模拟数据”标记。
2. 测试 M1/M2、布局导入导出。非法文档应整份拒绝并保留旧布局。
3. 点击“刷新屏幕/能力”，阅读实际屏幕、Overlay、AppOps、通知和车辆权限状态。
4. 手动授予需要的系统权限；权限声明、类可加载和属性可读不是同一阶段。
5. 明确选择目标副屏，核对名称、尺寸、flags，再点击“启动 DEVICE”。
6. 分别观察服务状态、`addViewReturned`、attach、首次 onDraw；这些均不代表物理可见。
7. 仅在真实 HUD 观察后点击“我确认物理可见”或“我确认不可见”。新 generation 会重置旧确认。
8. 使用主屏或通知的停止入口。导出诊断文本，核对 buildId、generation、错误码和资源计数。
9. 手动覆盖离开主屏、息屏/唤醒、目标屏移除/恢复、权限撤销和进程重启。

窗口初始 flags 为 664；取消 KEEP_SCREEN_ON 后为 536。该开关只控制窗口标志，不承诺唤醒或保持整车电源。服务使用 `START_NOT_STICKY`，没有开机自动恢复。

## 数据边界

PREVIEW 是唯一允许模拟车况的模式。DEVICE 从 ECARX AdaptAPI 读取（2026-11-24 起；此前的 `android.car` 适配在极氪车机上只拿到静态占位值，已删除），后台连接/轮询，不阻塞 UI。详见下文“车况改用 ECARX”。

- 只有 CarLight 车主实车确认过的读数（CarLight `docs/vehicle-interfaces.md` §27）作为 KNOWN 显示；未确认的候选只在探测页显示原值。
- getter 只记录 `READ_COMPLETION`，不伪造设备采样时间；失败值不会变成 0 / 关 / P。
- 断开、轮询卡住时全部信号发布为 unavailable，HUD 不会停在旧值上。

## 离线验证与现场限制

单元测试使用假显示屏、窗口、调度器和数据句柄，验证状态、代次、事件和资源数量。Lint/构建通过、PREVIEW 可用或 onDraw 完成都不证明 CustomHUD 已在车机物理显示。

仍需现场确认：目标屏映射与窗口权限、合成层级、可读性、公开属性支持/区域/单位/新鲜度、ECARX 精确授权边界，以及休眠和热插拔的实际系统行为。

## 系统共享 UID 与平台签名（2026-11-03）

`app/src/main/AndroidManifest.xml` 已声明 `android:sharedUserId="android.uid.system"`，Debug / Release 均适用。使用与 CarLight 相同、此前已在目标车机验证的平台测试证书签名；证书 SHA-256：`c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`。Manifest 声明和离线签名验证不等同于本次已经安装为 UID 1000，仍须车机安装后核对。

本次执行 `./gradlew --offline testDebugUnitTest lintDebug assembleDebug assembleRelease`：构建 / lint 通过，16 项单元测试通过。两个成品的包名、共享 UID 声明、v3 签名和 ZIP 对齐均已验证（minSdk 29，默认 v3 签名适用于全部支持版本）：

- **release**：`dev.customhud`，`app/build/outputs/apk/platform/CustomHUD-0.1.0-b95bd686-344-release-aosp-platform-uid1000.apk`；SHA-256 `d214089d866235a3fe48f4f69c2c2e391954bd0809f70dd913f8af18dcf2ddb1`。
- **debug**：`dev.customhud.debug`，`app/build/outputs/apk/platform/CustomHUD-0.1.0-b95bd686-344-debug-aosp-platform-uid1000.apk`；SHA-256 `afd7dff60338ea6c0c090fdcabd9b15664e0e49b9f20091679a430669f3a8377`。

签名记录：`app/build/outputs/apk/platform/platform-signing-report.json`。原始构建 APK 保留；默认 Gradle Debug 仍使用普通 debug 密钥，上车安装请使用这里的平台签名成品。平台密钥没有复制到项目中。

若目标车机已有同包名的普通签名 / 普通 UID 版本，系统可能拒绝覆盖；本次未安装、卸载或清除车机上的任何应用。

## 车机界面、布局编辑、探测与大字歌词（2026-11-03）

主界面改为车机布局：左侧导航「HUD / 布局 / 探测 / 日志」，按钮至少 76dp 高。

- **HUD**：状态卡片、启动/停止、目标屏幕卡片选择（记住上次选择，按 id+名称匹配）、窗口选项、权限与能力、物理可见标记。
- **布局**：按目标屏幕实际比例预览；点选组件、拖动改位置（靠近中线吸附），右侧调整显示/大小/微调/间距。拖动过程中实时推送到运行中的 HUD，松手才写入存储。新增 `LYRIC`（大字歌词）组件与“歌词+时钟”预设；`TRACK` 在 DEVICE 模式显示真实歌名 · 歌手。
- **探测**：只用公开接口，运行 40 秒，结果分页显示供手机拍照，也可导出文件。内容：权限与屏幕、全部媒体会话字段、探测期间字段变化（≥3 次标为疑似歌词）、播放进度采样、媒体通知字段、媒体应用公开声明的对外组件（只列名称）、应用内执行 `dumpsys media_session / window windows / SurfaceFlinger --list` 的结果（允许失败）、网络与 lrclib.net 可达性。
- **实时字段 / 歌词来源**：探测页实时列出媒体会话、会话事件与媒体通知中的所有文字字段及变化次数。点击某个字段即设为歌词来源，`LYRIC` 组件显示该字段的当前值；无需重新安装即可试不同字段。来源所属应用暂停播放时歌词隐藏。

新增权限：`MEDIA_CONTENT_CONTROL`、`DUMP`（依赖系统 UID，未授予时探测会如实记录失败）、`QUERY_ALL_PACKAGES`、`INTERNET`、`ACCESS_NETWORK_STATE`；新增可选通知监听服务 `MediaNotificationListener`，仅在直接读取媒体会话被拒绝时或需要媒体通知字段时由用户手动开启。没有逆向、读取其他应用私有文件或绕过系统限制。

离线验证：Linux arm64 容器 `tools/build-linux-arm64.sh` 构建、lint（0 error）通过，29 项单元测试通过（新增字段追踪、歌词折行、报告分页）。界面未在任何真机或模拟器上运行过。

现场建议顺序：
1. 「探测」→ QQ 音乐播放有歌词的歌 → 开始探测 → 「报告」逐页拍照。
2. 「探测 → 实时字段」观察是否有字段随歌词逐句变化；有则点击设为歌词来源。
3. 「布局」→ 预设“歌词+时钟” → 拖动到合适位置。
4. 「HUD」→ 选择目标屏幕 → 启动 HUD → 在挡风玻璃上确认并标记。

## 车况读取与 HUD 亮度/闪烁（2026-11-04）

现场反馈：几乎所有车况不可用；HUD 时钟比原车暗，并有明暗交替的闪烁。

原因（代码层面已确认）：
- 旧适配器只读车速和挡位，其余组件写死为“—/不可用”；挡位属性 ID 写成了 `0x11600400`（FLOAT 类型），正确的 `GEAR_SELECTION` 是 `0x11400400`，所以挡位也必然读不到。
- 车况每 500 ms、时钟每 1 s 触发一次整屏重绘，即使画面内容完全没变。

改动：
- `adapters/CarProps.kt`：标准 `VehiclePropertyIds`（取自 android-34 `optional/android.car.jar`）与纯函数换算。新增读取电量（EV_BATTERY_LEVEL / 容量）、续航、功率（瞬时充放电功率，驾驶为正）、充电、转向灯/双闪、门锁、四轮胎压、电源状态；挡位改用正确 ID 并以 CURRENT_GEAR 兜底。
- `adapters/CarPropertyAccess.kt`：android.car 只读反射封装，读取 `CarPropertyValue` 的状态与采样时间戳；从不写属性。
- 每个信号都记录来源属性或不可用原因（车机未提供 / 无权限 / 暂不可用）。电池温度、驾驶模式、悬架、电机、踏板、G 值没有 Android 标准属性，明确标为 `NO_STANDARD_PROPERTY`，不猜测厂商属性。
- 「探测」新增“车况 · 实时”页（每秒刷新的信号值和原因），报告新增“A 车况”（HUD 用到的标准属性）和末尾“B 车辆属性全表”（系统列出的全部可访问属性及当前值，只读，跳过只写属性）。
- 渲染：文字改为纯白，新增「HUD → HUD 文字」粗细（常规/加粗/特粗，默认加粗）、亮度（100–40%）；默认只在画面内容变化时重绘（`renderer/RenderKey.kt`，只比较已启用组件显示精度下的值），可关闭以对比闪烁。
- Manifest 新增 CAR_ENERGY、CAR_ENERGY_PORTS、CAR_EXTERIOR_LIGHTS、CAR_TIRES、CONTROL_CAR_DOORS、CAR_EXTERIOR_ENVIRONMENT。

离线验证：`tools/build-linux-arm64.sh` 构建、lint（0 error）通过，37 项单元测试通过（新增属性 ID 编码、单位换算、重绘判定）。未在真车或模拟器运行；目标车型实际暴露哪些标准属性仍待“车况 · 实时”和报告确认。

## 车况改用 ECARX（2026-11-24）

极氪车机上 `android.car` 标准属性拿不到真实数据（静态占位值）。改为与隔壁 CarLight 相同的 ECARX AdaptAPI 读数（CarLight `EcarxVehicle` / `docs/vehicle-interfaces.md` §27，2026-11-07 车主实车确认）。

- `adapters/EcarxCarHub.java`：进程内唯一的 ECARX `Car` 连接（主线程 `Car.create`，工作线程 `registerConnectWatcher` + `connect`，12 秒超时，1～60 秒退避重连），按引用计数开关。SDK 可能每进程只给一个共享 `Car`、连接监听只有一个槽位，所以挡位订阅、车况轮询和探测页都挂在这一个连接上，不各自 `Car.create`。
- `adapters/EcarxGearSource.java`：挡位订阅逻辑不变（先读基准再 `registerListener`），连接改由 hub 提供；订阅失败时请 hub 重连。
- `adapters/EcarxVehicleFeed.kt`：进程内共享的轮询线程，HUD 窗口、仪表相机窗口、探测页共用。每 200 ms 读车速、挡位、转向灯/双闪、踏板、加速度、功率流；每 2 秒读电量、续航、胎压/胎温、中控锁、驾驶模式、悬架和未确认候选。DeadObject 或连续 5 秒全部失败时重连；轮询 3 秒无返回时全部置为不可用。
- `adapters/EcarxSnapshotBuilder.kt` / `EcarxDecode.kt`：纯函数解码（有单元测试）。车速 m/s×3.6；胎压 原值÷100 = bar；加速度 ÷9.8 = g；函数值按 zHUD 顺序回退 zone，并缓存有效的 zone 写法，稳定后每个 id 只需一次 binder 调用。
- 转向灯/双闪：值是否随灯闪烁未知，熄灭 800 ms 内仍算亮，HUD 箭头不会跟着闪。
- 仍未实车确认、只在探测页显示原值（HUD 显示“—”）：电池温度 `0x102A00`、前后电机温度、充电（插口 `0x24130200` / 电池状态 `0x201500`）、功率 `0x103600`、充电电压/电流/剩余时间、电源 `0x200100`。因此“电池温度”“充电”“功率”组件暂时显示“—”。
- 新接通的组件：电机（前/后 待机·驱动·回收）、G 值（纵/横，0.1 g）、踏板（油门 %、制动原值）。
- 探测页“车况 · 实时”和报告“A 车况”改为 ECARX：连接状态、轮询耗时/调用数/错误、每个信号的值与来源 id 及原值。
