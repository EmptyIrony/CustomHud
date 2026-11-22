# CustomHUD 开发交接

日期：2026-11-03。请让 Kimi 从 `KIMI_START.md` 开始工作。

这是独立 Android HUD 应用的开发资料，包含接口契约、生命周期、布局 schema、设备反馈及参考 APK。接口资料为非官方兼容性草案，实际 CustomHUD 副屏权限、车辆数据读取和现场显示仍待验证。

## 文件

| 路径 | 内容 |
|---|---|
| `KIMI_START.md` | 可直接提交给 Kimi 的开发任务 |
| `docs/` | 窗口、渲染、生命周期、数据接入、布局、构建与验收契约 |
| `docs/schemas/`、`docs/examples/` | 版本化布局 schema、单时钟及完整布局示例 |
| `feedback/device-feedback.md` | 最新环境与集成反馈，包含证据适用范围和下一步优先级 |
| `feedback/device-evidence.txt` | 相关只读设备证据摘录 |
| `reference/zhud-4.10-v4010.apk` | zHUD 4.10 / versionCode 4010 参考文件，不是 CustomHUD 的运行依赖 |
| `manifest.json` | 全部文件大小与 SHA-256 |

## 必须先理解的事实

- 输出方案为目标副屏上的透明应用悬浮窗，内容由自己的 View/Canvas 绘制。
- 参考 APK 与新应用的包名、签名、UID、权限及数据源能力不能互相代替验证。
- 当前没有 zHUD 或 CustomHUD 在用户车机实际出画面的确认。其他应用的灯幕播放成功不构成 HUD 显示证据。
- 已有实车反馈来自同一目标环境上的另一应用，可用于系统版本、显示屏候选和访问限制排查，不应当作 CustomHUD 已通过现场测试。
- 先完成离线预览与可构建工程；现场安装、启动及配置调整由用户确认后手动执行。

`docs/manifest.json` 是原接口文档子包的一致性清单；根目录 `manifest.json` 覆盖本次全部交接材料。
