# HUD 数据接口契约

状态：非官方技术草案；实车未验证。本文定义接口与开发责任，不表示已经实现或取得车辆访问权限。

## 1. 三类接口必须区分

| 分类 | 含义 |
|---|---|
| 公共平台接口 | Android 平台提供的类型；仍受系统版本、权限和设备能力限制 |
| 平台适配候选 | 厂商类型、反射方法或属性映射；必须在目标车型与固件核对 |
| 项目约定，需要实现 | 数据模型、连接管理、错误策略和组件绑定；不是平台现成能力 |

所有业务层车辆接口保持只读，不提供任意属性写入、整车控制或通用反射调用入口。

## 2. 项目数据模型，需要实现

```text
DataValue<T>
  value: T?
  validity: Known | Unknown | Stale | Unavailable | Error
  observedAtMonotonic: timestamp?
  receivedAtMonotonic: timestamp
  timestampBasis: DeviceSample | CallbackReceipt | ReadCompletion | Unknown
  unit: explicit unit or Unverified
  source: provider identifier
  connectionGeneration: integer
  error: structured error?
```

`observedAtMonotonic` 表示实际采集或可信回调时刻，不得在重绘、读取缓存或转发时重新盖章。
只有接收时间可用时应明确标识，不能冒充设备采集时间。
每个字段独立记录时间、有效性与单位；其他字段更新不能延长它的新鲜度。
未知值保留为未知，不把缺失车速转为零、不把未知挡位转为 P。
本项目 monotonic timestamp 统一为 `SystemClock.elapsedRealtime()` 的毫秒值；外部时间域需转换或独立保留，不能混用 uptime、墙钟和纳秒。
过期预算由对应信号的更新方式与验证结果确定。DEVICE profile 中每个启用字段必须提供正数 `freshnessBudgetMs`；缺少预算、单位或枚举映射时该字段保持不可用。
无可信采样时戳的缓存型 getter，只能记录 ReadCompletion，明确展示这是读取完成时间而非传感器采样时间；反复取同一缓存不能制造新的 DeviceSample 时间。
PREVIEW 可显式配置模拟时钟及预算；其值不自动成为 DEVICE 默认策略。

```text
VehicleDataSource
  open(profile, mode): asynchronous ConnectResult
  connection: Unavailable | Connecting | Connected | Disconnected | Error
  capabilities(): per-signal capability map
  snapshot(): immutable VehicleSnapshot
  subscribe(observer): Subscription
  close(): CloseResult
```

以上类型由项目实现。一个不可变快照可包含不同采集时刻的字段，必须保留各字段时间。
`open` 对同一 profile 幂等；更换 profile 先关闭旧连接并增加 generation。`ConnectResult` 包含结果类别、generation、能力清单和结构化错误。
订阅返回的 `Subscription.cancel()` 幂等；close 取消读取与订阅并返回 Complete / Partial / Timeout 及待清理资源数，不把部分关闭报告为资源全已释放。

## 3. 建议的规范化车辆字段

| 数据族 | 输出字段 | 规范化单位或表示 |
|---|---|---|
| 运动 | speed、gear、powerState | km/h；项目枚举；项目枚举 |
| 能源 | batteryPercent、range、batteryTemperature | %、km、°C |
| 充电 | charging、voltage、current、timeRemaining | Boolean、V、A、分钟 |
| 轮胎 | pressureFL/FR/RL/RR、temperatureFL/FR/RL/RR | 显式压力单位、°C |
| 电机 | temperatureFront/Rear、activity、fault | °C、项目枚举、有效性明确的状态 |
| 踏板 | accelerator、brake | 明确为比例或百分比，不能凭名称猜测 |
| 动态 | accelerationLongitudinal/Lateral、power | m/s² 或明确的 g、kW |
| 车身状态 | leftIndicator、rightIndicator、hazard、locked | 带有效性的 Boolean |
| 驾驶配置 | driveMode、suspensionHeight、suspensionStiffness | 项目枚举；不直接暴露厂商整数 |

尚未验证单位的输入不得直接标成已规范化值；适配器应保留原值与转换证据。
显示默认值、数据库默认值和真实测量值必须有不同的来源标记。

## 4. ECARX 连接与读取：平台适配候选

以下是需核对的反射类型与方法形状，不构成厂商对外兼容性承诺。

| 类型／对象 | 方法形状 | 候选用途 |
|---|---|---|
| `com.ecarx.xui.adaptapi.car.Car` | 静态 `create(Context)` | 创建车况访问对象；可能返回空 |
| Car 对象 | `getSensorManager()`、`getICarFunction()` | 获取传感器与功能状态子接口 |
| `com.ecarx.xui.adaptapi.binder.IConnectable` | `connect()` | 请求建立连接 |
| 同上 | `registerConnectWatcher(IConnectWatcher)` | 接收连接／断开通知；嵌套 watcher 类型按目标 SDK 核对 |
| `com.ecarx.xui.adaptapi.car.sensor.ISensor` | `getSensorLatestValue(int)` | 浮点传感器候选读取 |
| 同上 | `getSensorEvent(int)` | 整数事件候选读取 |
| `com.ecarx.xui.adaptapi.car.base.ICarFunction` | `getCustomizeFunctionValue(int)` | 自定义数值候选读取 |
| 同上 | `getFunctionValue(int,int)` | 含区域参数的状态候选读取 |

连接回调名称候选为 `onConnected`、`onDisConnected`；不能把拼写不同的 SDK 方法静默当作等价接口。
反射匹配必须核对参数类型与返回值；返回值先检查是否为 `Number`／预期对象，再转换。
类可加载、对象可创建、连接已建立、特定信号可读取分别记录，不合并成一个成功标记。
读取异常、权限拒绝、信号不支持与暂时无数据应转换成不同结果。

### 只读属性映射候选

下列编号仅供目标平台映射表核对，不是公共 Android 属性编号，也不是跨车型认证值。

| 候选标识 | 编号 | 读取形状 | 语义／单位核对事项 |
|---|---|---|---|
| Speed | `0x100100` | 传感器浮点 | 输入可能需 m/s→km/h；未认证单位 |
| BatteryPercent | `0x404000` | 浮点或事件 | 候选百分比；范围、精度与可用状态需核对 |
| BatteryLevelAlternative | `0x100a00` | 浮点或事件 | 备选电量解释，不与百分比无条件互换 |
| Range | `0x101900` | 传感器浮点 | 候选 km；续航类别与取整策略需核对 |
| Odometer | `0x100700` | 传感器浮点 | 候选 km；累计值与精度需核对 |
| BatteryTemperature | `0x102a00` | 传感器浮点 | 候选 °C；温度类型需核对 |
| EnergyState | `0x201500` | 整数事件 | 事件枚举映射需要独立定义 |
| Gear | `0x200200` | 整数事件 | 不沿用未经认证的厂商挡位数值 |

项目使用单独的 `VendorPropertyMap` 管理这些候选，明确启用目标 profile 后才使用。
表中编号不构成自动枚举、自动授权或自动探测所有车辆属性的要求。

## 5. android.car：公共平台接口与厂商映射分层

候选公共接口包括 `android.car.Car.createCar(Context)`、`getCarManager("property")`，以及
`CarPropertyManager.getIntProperty(int,int)`、`getFloatProperty(int,int)`、属性变化回调注册。
具体可用签名、回调方式、区域含义和权限随目标 Android／车载平台核对。
使用公共类型不等于某个厂商属性编号可读；编号与枚举解释仍属于平台适配层。
车辆转向灯状态输出为 `None | Left | Right | Both | Unknown`，与导航下一转弯分开。
读取失败不能把 Unknown 降格成 None；注销不完整时须报告本地关闭与 SDK 资源释放的差异。

转向属性映射候选如下，均需在显式启用的 DEVICE profile 中核对；不是通用 Android 枚举保证：

| 信号 | 属性编号 | 候选读取形状 |
|---|---|---|
| 左灯 | `0x21403917` | `getIntProperty(propertyId, areaId)` |
| 右灯 | `0x21403918` | 同上 |
| 转向 active | `0x2140f704` | 同上 |

areaId=0 是候选配置值，实际支持区域须确认；原始数值到开/关或左右枚举的解释需由 profile 提供，不默认把任意整数当 Boolean。

## 6. 连接、过期与重试：项目约定，需要实现

连接重建时递增 generation；忽略旧连接迟到的回调。
断开后将相关字段标成 Unavailable／Stale，不继续把旧缓存当作实时车况。
权限拒绝、不支持的类／方法和未知映射应停止相应自动重试，保留可诊断原因。
临时连接故障可使用有次数上限的退避；次数、时限和恢复条件作为项目配置明确给出。
重试不创建并行连接，close 与 subscribe／read 需要有串行所有权或明确锁规则。
轮询不是渲染线程工作；一个慢读操作不能阻塞 UI 或同时重入同一适配器。
诊断日志记录信号名、结果类别、时间与错误，不默认保存精确位置或用户媒体内容。

## 7. 媒体：公共平台接口

使用 `MediaSessionManager.getActiveSessions(ComponentName)`，ComponentName 必须属于自己获准的通知监听组件。
媒体控制权限或通知访问资格由系统确认；项目不提供修改系统授权设置的替代流程。
读取 `MediaController.getPlaybackState()` 与 `getMetadata()`，候选选择策略由项目明确实现：
优先正在播放且存在 metadata 的会话，否则选择有 metadata 的会话，并保留来源应用身份。
曲名 fallback 可采用 TITLE→DISPLAY_TITLE→DISPLAY_SUBTITLE；作者可采用 ARTIST→ALBUM_ARTIST→AUTHOR。
无活跃会话、metadata 缺失与读取被拒绝应区分，不能都显示为已经停止播放。

封面输入可来自 MediaMetadata 的 ALBUM_ART／ART Bitmap，或 ART_URI／ALBUM_ART_URI。
URI 加载放到后台，校验 scheme、访问资格、字节数与解码尺寸，并提供超时和取消。
每次曲目变化使用代次标记，旧加载结果不得覆盖新曲目；封面缓存生命周期由项目实现。
HTTP(S)、content、file 来源分别处理，不把来自 metadata 的 URI 当作任意可信文件路径。
媒体快照至少包括标题、作者、封面状态、播放状态、来源、观测时间和有效性。

## 8. 位置、道路提醒与路线导航分开

位置源使用 Android `LocationManager` 等获准接口；显式报告 provider、精度、方位、速度及采集时间。
Android Location 速度单位是 m/s；规范化为 km/h 时明确转换。没有速度／方位时保持未知。
provider 选择、更新请求间隔、精度门限与无 fix 超时均是项目策略，不是固定硬件保证。
道路提醒由位置、方向和具备明确来源的对象数据库形成独立 `RoadAlertState`。
该状态可包含限速、摄像头／标志对象、距离、区间平均速度、GPS 有效性和数据库版本。
它不提供路线规划、路名、导航剩余距离或下一转弯指令。
路线导航另定义 `NavigationDataSource`，仅接入有明确授权与格式的导航提供方。
导航投影状态通知、车辆转向灯和道路提醒均不能冒充导航路线数据。

## 9. 初版交付边界

初版先实现离线时钟／固定文字及可注入的基础状态；车辆、媒体和位置适配器按能力逐项接入。
公共 API 与平台候选均有契约测试和错误注入；接口可用、数据新鲜与物理 HUD 显示分别验收。
本文没有承诺安装身份、系统权限、特定属性支持、投影显示或行驶状态下的可用性。
