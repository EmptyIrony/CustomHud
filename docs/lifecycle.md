# HUD 显示会话生命周期契约

本文是项目接口约定，需要在独立新项目中实现，不是现成可调用的 SDK。
实际车机的窗口权限、目标显示屏访问权及固件兼容性尚未验证。
组件编辑 UI 通过布局接口提交配置，不直接操作显示窗口。

## 1. 责任与不变量

- `DisplaySession` 拥有目标显示上下文、窗口、View、渲染调度、数据订阅及显示监听。
- UI 只提交意图和显示会话状态，不直接操作窗口或后台线程。
- 进程内同一显示会话最多持有一个待附着或已附着窗口。
- 所有窗口、View、显示监听和会话状态迁移在主线程执行。
- 读取数据和解析内容在可取消的后台任务执行，不阻塞主线程。
- 服务存活、`addView()` 返回、View 附着、View 完成绘制与物理 HUD 可见分别记录。
- 软件事件不能自动产生“硬件呈现成功”的结论；该确认保留为独立用户观察。
- 所有资源在取得时立即登记所有者，失败时能够按登记表回收。

## 2. 输入与可观察状态

`SessionConfig` 至少包含以下字段：

| 字段 | 约定 |
|---|---|
| `target` | 用户确认的显示目标描述，不把数字 ID 视为永久身份 |
| `windowSpec` | 窗口类型、flags、像素格式与边界策略 |
| `configRevision` | 整体配置版本，初次为正整数，此后严格递增 |
| `layoutRevision` | 单调递增的布局版本 |
| `backgroundAllowed` | UI 不可见时是否允许保持会话 |
| `screenOffPolicy` | 默认挂起并移除窗口；可由明确需求另定 |
| `recoveryPolicy` | 重试预算、退避和恢复条件 |

`SessionSnapshot` 至少包含以下信息：

- `state`、`desiredRunning`、`generation`、`operationId`、`configRevision`。
- 当前显示描述、软件附着状态、首次绘制时间和最近一次错误。
- 已登记资源计数、待清理资源计数、重试次数及下次重试时间。
- `userObservation = Unknown | VisibleConfirmed | NotVisibleReported`，仅由显式观察更新。
- 应用重新建立窗口后，将物理可见观察重置为 Unknown；旧 generation 的确认不可沿用。

## 3. 状态机

| 状态 | 含义 |
|---|---|
| `STOPPED` | 无窗口、无任务、无订阅；没有待清理资源 |
| `PREPARING` | 校验配置、解析目标、建立必要的会话对象 |
| `WAITING_DISPLAY` | 用户希望运行，但当前找不到已确认的目标屏 |
| `ATTACHING` | 已选目标，正在创建上下文、窗口和 View |
| `ATTACHED` | `addView` 已正常返回且当前 View 已确认附着，仅表示软件层状态 |
| `SUSPENDED` | 保留运行意图和配置，已按策略暂停并清理活动显示资源 |
| `RECOVERING` | 可恢复故障后的受限重建；不隐式扩大权限或更换目标 |
| `STOPPING` | 已停止接受新帧，正在取消任务、移除窗口和解除订阅 |
| `FAILED` | 当前操作失败；错误与未回收资源仍可查询 |

正常迁移：`STOPPED → PREPARING → ATTACHING → ATTACHED`。
缺少目标时进入 `WAITING_DISPLAY`；目标恢复后重新进入 `PREPARING`。
运行中可经 `SUSPENDED` 或 `RECOVERING` 返回准备阶段。
显式停止清除运行意图，进入 `STOPPING`，仅清理完成后进入 `STOPPED`。
任何阶段失败都必须发布结构化错误；不能用一个 `running=true` 覆盖阶段差异。

## 4. 项目接口

接口可以用 Kotlin、Java 或其他适配语言实现，语义保持一致。

| 操作 | 输入 | 完成语义 |
|---|---|---|
| `start` | `StartRequest(config, operationId)` | 返回已受理结果及可观察会话；不承诺物理可见 |
| `stop` | `StopRequest(reason, operationId)` | 完成结果区分清理成功、部分清理与超时 |
| `reconfigure` | `ConfigUpdate(configRevision, changes)` | 明确为原地刷新或窗口重建，返回应用到的软件版本 |
| `refresh` | `RefreshRequest(layoutRevision, reason)` | 合并刷新请求；返回受理或忽略原因，不代表屏幕已呈现 |
| `observe` | 订阅者 | 发布不可变 `SessionSnapshot`，可取消订阅 |
| `reportObservation` | 用户观察及关联 generation | 更新观察字段，不改变权限和会话启动条件 |

`OperationResult` 包含 `accepted`、`operationId`、`generation`、`state` 和可选错误。
需要等待附着或清理的调用通过异步完成事件返回，不在主线程阻塞等待。

## 5. start 的处理顺序

1. 应用层先满足必要前台服务的启动资格并及时建立前台状态；失败返回 `SERVICE_UNAVAILABLE`，不进入现场窗口创建。会话在主线程校验请求、目标配置和 revision；先返回可辨认的错误，不静默修正目标。
2. 同配置且会话正在运行时复用会话，重复 `operationId` 返回同一操作结果。
   非 STOPPED 状态下收到不同配置的 start，返回 `CONFIG_CONFLICT`，由调用方使用 reconfigure；不得直接创建第二个窗口。相同 operationId 携带不同请求内容同样拒绝。
3. 若正在停止，保存新的运行意图，待旧资源清理结束后再准备；不并行创建第二个窗口。
   FAILED 状态下仍有待清理句柄时，即使配置相同也不新建窗口：先完成旧清理，或返回 CLEANUP_FAILED 并等待用户处理。
4. 增加 generation，设置 `desiredRunning=true`，进入 `PREPARING`。
5. 解析用户确认的 Display；不存在时进入 `WAITING_DISPLAY` 并保留显示监听。
6. 检查已知的窗口授权前提；不足时返回 `PERMISSION_DENIED`，会话层不修改系统授权。
7. 用目标 Display 创建 DisplayContext；API 30 及以上按窗口类型创建 WindowContext。
8. 从目标上下文取得 WindowManager；WindowContext 类型与 LayoutParams 类型必须一致。
9. 创建 View、安装附着／分离与绘制观察器，登记资源，再进入 `ATTACHING`。
10. 调用 `addView`，分别记录“调用返回”和“View 已附着”，两者齐备才进入 `ATTACHED`。
11. 按配置启动渲染调度和数据订阅；持续独立记录应用层前台服务状态。
12. 首次绘制只更新软件绘制字段，不更新 `userObservation` 为可见确认。

若附着回调早于 `addView` 返回，先保存事件，等待调用正常结束后再完成迁移。
附着等待默认期限为调用返回后 2000ms，可配置；它不能终止阻塞在框架调用中的主线程。
失败后回滚本次登记资源，不能留下“对象存在所以已成功”的状态。

## 6. stop、幂等与失败回滚

1. 显式停止先清除 `desiredRunning`，增加 generation，使旧数据与重试回调失效。
2. 进入 `STOPPING`，拒绝新帧、新刷新和新的后台工作提交。
3. 取消渲染 ticker、动画回调、延时恢复和数据读取任务；清空待绘制快照。
4. 解除数据订阅和显示监听；可取消的后台任务必须收到取消信号。
5. 在主线程移除拥有的窗口，等待分离回调或确认该 View 已不附着。
6. 清理 View 监听、上下文引用和资源登记，再发布清理结果。
7. 后台任务的有界等待在后台执行；主线程不调用阻塞式 join 或 sleep。
8. 仅资源全部回收时发布 `STOPPED`；否则保留资源句柄并报告 `CLEANUP_FAILED`。

重复 stop 复用正在进行的清理，不重复移除同一窗口或重复解除同一订阅。
移除窗口失败时不能丢弃所有者引用并假装停止成功；下一次清理应能重试该句柄。
generation 失效只屏蔽旧业务回调，旧资源的显式清理不能因此被跳过。
操作完成前再次 start 时，先完成或明确失败旧清理，再决定是否建立新会话。

## 7. reconfigure 与 refresh

- 每次更新携带严格递增的 `configRevision`；较旧更新返回 `STALE_REVISION`。
- 仅内容、样式、位置或数据快照变化时，发布不可变快照并合并一次主线程刷新。
- 目标 Display、窗口类型或依赖上下文的配置变化时，走受控窗口重建。
- 重建先移除旧窗口再创建新窗口；不承诺视觉上的无缝切换。
- 每次重建或挂起都使旧窗口 generation 失效；新窗口和数据任务使用新 generation。
- 对旧 revision 的绘制完成回调只记过期统计，不覆盖当前配置状态。
- 同一帧周期内多个 refresh 合并为最新版本，避免无限堆积 invalidate 或更新消息。
- 刷新可记录 `accepted → drawRequested → onDrawCompleted`，这些均不是硬件呈现确认。
- 配置解析失败保持最后一个有效配置，并报告失败版本及 `CONFIG_INVALID`。

## 8. generation 与线程规则

- 所有后台任务、订阅、延时重试和显示回调捕获创建时的 generation。
- 回到主线程后再次比较 generation；不匹配时放弃业务更新并增加过期回调计数。
- 当前 generation 内仍校验显示描述和配置版本，避免数字 display ID 重用造成错误附着。
- 渲染器只消费不可变快照；读取任务不直接修改 View 或可变布局对象。
- 窗口添加、移除、上下文替换和状态迁移由一个主线程串行入口完成。
- 异步异常回传结构化错误，不在后台线程直接变更状态枚举。

## 9. 显示屏丢失与恢复

- 在运行意图存在期间保持 DisplayListener，不只在首次等屏时监听。
- 目标移除时立即停止提交新画面，回收旧显示资源，进入 `WAITING_DISPLAY`。
- generation 改变时重新绑定等待监听，防止保留的监听因代次检查永久失效；清理旧资源后才能建立新窗口。
- 目标重新出现时重新读取名称、尺寸、旋转、密度与 flags，再校验选择条件。
- 相同数字 ID 不保证相同显示目标；身份选择条件不匹配时等待用户重新确认，不自动选另一块屏。
- 可变的尺寸、旋转和密度不单独作为目标身份；它们变化时按已确认目标的重建策略处理。
- 目标变化事件默认合并 100ms 后处理；边界／密度／旋转改变统一进入重建流程。
- 缺屏等待不做忙轮询；只有显示事件、用户重试或明确配置变化触发重新解析。
- 可恢复附着错误默认最多重试 3 次，退避 250ms、1000ms、3000ms。
- 权限不足、配置无效与不支持的窗口类型不自动重试；条件改变后显式重试。
- 恢复预算按稳定 targetKey 与 recoveryEpisodeId 计算；targetKey 由用户确认的选择规则/profile 标识组成，不含 generation、尺寸、密度或旋转。窗口 generation 的递增不重置该预算。
- `stableAttachWindowMs` 默认 30000ms（项目可配置策略）：只有在同一确认目标连续保持 ATTACHED、没有目标移除、分离或附着错误达到该时间后，才结束当前恢复周期。短暂 attach 不重置预算；用户显式重试可开启新周期，持续失败进入 `FAILED` 并保留原因。

## 10. 前后台、息屏与唤醒

- UI 隐藏且 `backgroundAllowed=true`：由会话继续管理显示，UI 取消观察不停止会话。
- UI 隐藏且不允许后台：进入 `SUSPENDED`，保留配置与运行意图，移除窗口并停止渲染和数据拉取。
- UI 返回时仅在运行意图仍为真且条件满足时恢复，不把界面打开等同于用户要求开始。
- 系统息屏默认挂起并移除窗口；显示移除事件同时发生时，由同一清理流程合并处理。
- 唤醒后重新解析显示和上下文，不复用已失效的 WindowManager／View。
- 会话不主动唤醒屏幕；窗口的焦点、触摸和保亮策略由 windowSpec 明确约定。
- 前台服务状态单独公开；服务存在不能覆盖显示会话的等待、挂起或失败状态。
- 服务正常销毁请求 stop；进程强制终止不保证生命周期回调，不能宣称拥有同步清理保证。

### 宿主前台服务的所有权

应用层 Service 持有 DisplaySession，负责及时进入前台，也负责结束前台状态。新会话启动期间的配置、权限或附着发生不可恢复错误时，清除运行意图并完成本次资源回滚；没有其他运行会话或待清理资源后，调用 stopForeground 并 stopSelf，不留下只有通知的服务。已有会话的无效配置更新则保持上一有效配置和运行意图，按更新拒绝处理，不停止有效会话。
WAITING_DISPLAY／RECOVERING 仅在运行意图仍存在且宿主满足平台资格时保持前台，通知明确显示等待或恢复。SUSPENDED 是否保留服务由 backgroundAllowed 与系统规则决定；释放服务时保留可恢复配置，不保留失效窗口引用。
用户 stop 的正常收尾顺序是窗口及任务清理完成 → DisplaySession.STOPPED → 宿主结束前台状态与服务。若清理部分失败，显示 CLEANUP_FAILED 和待清理资源，保留清理所有者并允许有界重试；不能悄悄 stopSelf 后丢失资源句柄，也不能将持续错误伪报为正常运行。

## 11. 结构化错误与可观察性

| 错误码 | 默认处理 |
|---|---|
| `CONFIG_INVALID` / `STALE_REVISION` / `CONFIG_CONFLICT` | 拒绝更新并保留有效配置 |
| `TARGET_NOT_FOUND` / `TARGET_CHANGED` | 等待或请求重新确认目标 |
| `PERMISSION_DENIED` / `WINDOW_TYPE_UNSUPPORTED` | 回滚并等待条件改变，不自动重复申请 |
| `ATTACH_FAILED` / `ATTACH_TIMEOUT` | 回滚；满足恢复策略时有界重试 |
| `RENDER_FAILED` / `DATA_TASK_FAILED` | 标明组件／任务失败，按策略隔离或停止会话 |
| `CLEANUP_FAILED` / `CLEANUP_TIMEOUT` | 保留待清理资源及错误，不能发布完全停止 |
| `CANCELLED` / `SERVICE_UNAVAILABLE` | 发布原因，保持运行意图和实际状态可区分 |

错误结构包含 code、stage、message、recoverable、operationId、generation、target、causeType。
日志记录状态迁移、操作开始／结束、耗时、目标描述、附着／分离和资源计数。
默认不记录完整内容、凭证或车辆数据值；调试面板显示最近错误与恢复预算。
分别统计 addView 成功、View 附着、onDraw 完成及用户可见确认，不能混为一个成功计数。

## 12. 离线验收

- 使用假的显示注册表、窗口管理器、调度器和数据源，不依赖实际 HUD 即可验证状态机。
- 重复 start 不增加窗口或订阅；重复 stop 不重复释放；start/stop 交错保持单一所有者。
- 缺屏、目标移除、ID 重用、尺寸／密度变化按规定等待、确认或重建。
- addView 抛异常后无 ATTACHED、无遗留渲染任务；显示状态不得被通知状态误报。
- 附着回调超时、移除异常及清理超时均保留真实资源状态和结构化错误。
- 旧 generation 的数据、重试和绘制回调不覆盖新会话；旧资源仍可完成清理。
- refresh 洪峰只保留最新 revision，界面线程无阻塞等待。
- 前后台及息屏／唤醒序列不会重复创建窗口、线程或监听。
- 软件绘制通过时 `userObservation` 仍可保持 Unknown，直到明确的用户确认。
- 每个验收项应验证状态、资源计数和事件顺序，不只断言函数被调用。
