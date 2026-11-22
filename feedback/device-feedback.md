# 目标环境与 HUD 集成反馈

更新：2026-11-03。非官方兼容性草案；现场采集主体是 CarLight，不能将其结果冒充 zHUD 或 CustomHUD 的运行结果。

## 1. HUD 输出方案

已明确的兼容输出链为：

```text
DisplayManager → 用户确认的 Display
  → createDisplayContext
  → API ≥ 30 时 createWindowContext(TYPE_APPLICATION_OVERLAY, null)
  → 目标 Context 的 WindowManager.addView
  → 自定义 View.onDraw(Canvas)
```

窗口兼容参数：宽高 MATCH_PARENT，type=2038，flags=664，format=-3（TRANSLUCENT），View 背景透明。详细说明及可配置边界见 `../docs/platform-interface.md`。

这条显示链使用 Android 窗口和 Canvas；灯幕的 libpcmframe、画面分包和 JNI 发送路径不是本项目的显示依赖。无需因为其他灯光应用的问题就把这些能力加入 HUD 工程。

## 2. 已观测设备环境

| 项目 | 只读报告观测 |
|---|---|
| Android | 12，SDK_INT=32 |
| ABI | arm64-v8a、armeabi-v7a、armeabi；采集应用为 64 位 |
| display 0 | 内置屏幕，2560×1600，dpi=160 |
| display 1 | HDMI 屏幕，2560×536，dpi=213 |
| display 2 | HDMI 屏幕，1024×576，dpi=213 |
| 采集应用身份 | 普通独立 UID 10128，targetSdk=34 |
| ECARX 框架容器 | `/system/framework/ecarx.adaptapi.impl.jar`，报告列出 918 个 SDK 类名 |

display 2 是需要用户确认的 HUD 候选，不是已经实测的物理屏幕映射。两个副屏显示相同通用名称；不能只靠名称或者固定数字自动判断。最终几何使用 View 实际宽高，不把上述分辨率写死为所有设备常量。

## 3. ECARX 当前访问障碍

Car、IConnectable、IConnectWatcher、ISensor、ISensorListener 均能加载，Car 的定义加载器为 BootClassLoader。然而关键类型只显示类头／内部类型，没有方法、构造器或常量输出；整个 SDK 形状输出的 M/C/K 行数均为零，随后 Car.create(Context) 查找失败。

这与系统非 SDK 成员的反射可见性过滤高度吻合；当前尚未取得实际框架文件成员或明确的限制事件来唯一确认规则。因此：

- 不再把问题描述成 ECARX SDK 类没安装。
- 不把换一个工厂名称当成已经有依据的修复。
- 不承诺普通独立应用能使用该路径。
- 若 SDK 暂不可用，保留 mock、字段不可用状态和清晰日志，继续独立的窗口／渲染开发。

车机定义了 `com.ecarx.sdk.adaptapi.service.permission.READ`，级别为 signature，采集应用未获准。但该名称是否正好控制目标信号，报告没有证明。它是待核对的服务授权条件，不能直接推导全部字段必然可用或必然不可用。

本次交接没有增加 HiddenApiBypass 等访问策略改动，也没有要求修改系统身份。若后续要改变接入方案，应作为独立、明确授权的任务处理，不将其混入默认初始化。

## 4. 标准 Android 车况路径

实车报告确认：

| 权限 | 设备定义 | 采集应用实际结果 |
|---|---|---|
| CAR_POWERTRAIN | normal | granted=true |
| CAR_SPEED | dangerous，归入 LOCATION 权限组 | granted=false |
| CAR_INFO | normal | granted=true |

旧诊断把标准 Car.createCar 放在主线程等待，5 秒后只记超时，尚未读到具体属性。代码将调用移到后台只能说明实现已调整，不能写成已经在车上验证成功。

建议优先实现公开 android.car 数据适配器：使用不阻塞界面线程的连接方式、明确连接状态、实际权限检查、属性支持检查与逐字段读取结果。标准挡位属性与 ECARX 编号／枚举分开管理；标准车速单位按对应公共 API 文档处理，ECARX 的单位仍需目标 profile 确认。

获得 CAR_POWERTRAIN 不保证目标车型暴露每个标准属性；用户授予 CAR_SPEED 后也必须检验真实读取结果。数据失败不应影响独立时钟或其他不依赖该源的 HUD 组件。

## 5. 与 HUD 无关但容易误用的反馈

另一灯光应用出现过 JNI 正常返回而内部 spVehicle 为空。该信息只说明它的原生发送路径没有取得内部依赖，不能用来断言 HUD 窗口权限或 Canvas 输出有问题，更不能通过加入灯光控制方法修复 HUD。

同理，其他应用的 system 共享 UID 或成功车控也不能充当新 HUD 应用的实际授权证据。

## 6. 给 CustomHUD 的实现优先级

1. 新工程、主屏 PREVIEW、固定时钟和模拟基础状态、布局 schema 校验及可见日志。
2. 枚举目标屏，记录实际权限、AppOps、目标描述和异常；由用户选择后明确启动 DEVICE。
3. 将 addView 返回、View attach、onDraw、用户物理可见确认分别记录。
4. 严格落实 start/stop 幂等、失败回滚、generation、目标丢失、前后台／息屏和资源清理。
5. 公开标准车况适配逐项接入；ECARX 保持可替换候选，不阻断离线版本。
6. 媒体、道路提醒、驾驶模式预设和编辑器再按产品范围逐步接入。

初版需要唯一 buildId，避免所有安装包只报 0.1.0 而无法对齐现场日志。日志至少包含 Android/targetSdk、UID、权限、目标 display、会话代次、布局 revision 和窗口阶段。

## 7. 当前仍待现场验证

- CustomHUD 对目标屏创建窗口的实际权限与合成层级。
- HUD 物理映射、可读性、原厂信息叠加效果。
- 标准车况连接及每个属性支持、单位、新鲜度与断连处理。
- ECARX 成员和服务授权的准确边界。
- 休眠、唤醒、目标屏重建、进程重启后的实际生命周期。

本反馈仅引用相关环境摘录，未将用户的全部应用列表或设备属性作为开发输入。
