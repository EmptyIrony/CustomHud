# Kimi 开场任务

请读取当前目录中的交接资料，开发我的独立 Android 车载 HUD 应用，项目名 CustomHUD。

先阅读：

1. `README.md`
2. `feedback/device-feedback.md` 与 `feedback/device-evidence.txt`
3. `docs/platform-interface.md`
4. `docs/lifecycle.md`
5. `docs/layout-and-rendering.md`
6. `docs/data-interfaces.md`
7. `docs/implementation-and-acceptance.md`、`docs/schemas/`、`docs/examples/`

`reference/zhud-4.10-v4010.apk` 为功能与兼容性参考，包名 com.zeekr.zhud，versionCode 4010；其哈希见根目录 manifest.json。它不是新应用的依赖，也没有用户实测成功显示 HUD 的确认。

任务要求：

- 新建独立工程，使用自己的包名、UI、资源和数据模型。保留交接目录，代码放在当前项目中适合构建的位置。
- 默认采用 Kotlin 与原生 View/Canvas。先检查当前环境，固定工具链和依赖，说明最小架构后持续实现，不只回复计划。
- 先完成 PREVIEW、固定时钟、模拟数据、版本化布局、配置导入导出、启动/停止、完整会话状态机与可见日志。
- DEVICE 通过目标副屏 Context/WindowManager 输出透明 View；不要引入灯幕 libpcmframe 或将其他应用成功当作本应用权限验证。
- 执行 docs/lifecycle 的幂等、失败回滚、generation、热插拔、挂起/恢复、取消和部分清理契约。服务运行、addView、attach、onDraw 与用户物理可见确认分别记录。
- 车辆数据只读。优先实现公开 android.car 适配；ECARX 保留为独立候选，方法可见性和授权未知时降级而不是伪造数据或阻断全部离线工作。
- 单位、枚举、时间、权限和能力按字段／profile 管理。SDK 类可加载不代表方法可见，授权通过也不代表属性已支持。
- 完成必要行为测试与环境允许的实际离线构建，交付工程、构建命令、测试结果、APK、哈希与剩余待验证项。给每个安装包加入唯一 buildId。
- 不自动连接车辆、安装或运行参考 APK、启动现场输出或改变现场配置；现场由我确认后手动执行。遇明确安全拦截就停止相关操作并说明。

本资料是非官方兼容性草案，不是厂商正式 SDK。已知接口、项目约定和现场待验证项应保持区分。技术与产品细节可采用合理默认值，确需我选择的问题集中提出，同时推进不依赖答案的工作。

请现在检查环境并开始第一阶段开发。
