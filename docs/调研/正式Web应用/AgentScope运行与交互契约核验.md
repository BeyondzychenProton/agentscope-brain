# AgentScope 运行与交互契约核验

> 对应研究议题：[核验 AgentScope 运行与交互契约](https://github.com/BeyondzychenProton/agentscope-brain/issues/15)，归属[正式 Web 应用设计决策地图](https://github.com/BeyondzychenProton/agentscope-brain/issues/14)。核验日期：2026-10-23（上海时间）。本次完成官方发布信息与固定版本源码的静态研究，没有运行本项目的模型、MySQL、会议 MCP 或重启集成验收；没有选定新的产品架构、生产依赖或接口协议。

> 后续决策：Issue #19 已在上述事实基础上确认受控输入工具、挂起就绪和回答接纳／消费契约，见[设计规格](../../正式应用设计/动态表单与交互请求生命周期设计规格.md)。本页研究阶段的“待决定”描述保留其当时含义，不覆盖后续已确认设计，也不表示相关实现已验收。

> 后续更新：Issue #20 已确认可信框架关联、平台持久批次及恢复读取契约，见[消息与投影规格](../../正式应用设计/消息渲染运行事件与恢复投影设计规格.md)。原生事件只作为归一化输入，不直接替代正式消息、持久交互或真实操作结果；SDK 固定与运行验收仍归 #22／#23。

## 1. 结论与版本候选

本次查询官方 GitHub Releases 和 `releases/latest`，最高稳定发布仍为 **AgentScope Java v2.0.4**，发布时间为 2026-10-08 11:40:20（上海时间）。对应源码提交为 [`0b6743b263ba8e39eb417e35048026f1e0196548`][source-commit]。同日发布的 `v2.1.0-BETA1` 明确标记为预发布，不能作为“当前稳定版”引入。建议把 v2.0.4 作为后续接入验证的候选基线；正式锁定仍由后续决策及真实兼容验证完成。[官方稳定发布][release-stable]、[官方发布列表][release-list]、[预发布页][release-beta]

既有[状态、记忆与工作区调研](../状态与持久化/AgentScope状态记忆工作区持久化.md)和[共享记忆接入点调研](../状态与持久化/共享记忆与会话工作区接入点调研.md)已经固定到同一 v2.0.4 提交。因此本次没有发现“由旧稳定版升级到新稳定版”的真实版本差异；本次新增的是运行句柄、事件、HITL 续接和异常恢复边界的细化，不能把这些新增研究误写成新版本功能。[固定提交][source-commit]

核心判断如下：

| 问题 | 核验结论 | 对正式 Web 的意义 |
| --- | --- | --- |
| 是否支持流式正文与工具过程？ | 支持 `streamEvents()`，有文本、模型调用、工具调用/结果、等待确认、外部执行及最终 `Msg` 事件。[流式执行源码][stream-core][事件类型源码][event-types] | 具有接入基础；平台仍需生成面向产品的受控投影与持久状态。 |
| 暂停是否只能存在当前进程？ | **不能一概而论。** `AgentRun`/`RunControl` 是当前进程的执行控制；权限 ASK 与外部执行等待则保存 pending tool 及关联 metadata，结束本次调用，后续新调用可从持久 `AgentState` 续接。[执行句柄源码][run-handle][执行控制源码][run-control][续接入口源码][pending-entry][等待关联源码][pending-correlation] | 不能采用浏览器内存 Promise 作为恢复依据，也不能误称 SDK 具备持久执行引擎。 |
| 一次 SDK 调用结束是否等于业务 Run 成功？ | 不等于。`PERMISSION_ASKING`、`TOOL_SUSPENDED`、`INTERRUPTED` 等都会返回结果；SDK 句柄没有业务“等待确认/待核对”状态。[退出原因枚举][generate-reason][执行句柄源码][run-handle] | 业务 Run 仍由平台持有；确认与取消继续归原业务 Run，可能关联多个 SDK 执行句柄。 |
| 重启后是否可以直接恢复执行？ | SDK 下次调用可能直接执行 pending tools，或从保存上下文再次推理；`shutdownInterrupted` 还会导致该次传入消息被整体丢弃。[续接入口源码][pending-entry][直接恢复工具执行][resume-acting] | 重启时只恢复读取和核对，禁止自动发起模型或会议写操作；恢复入口必须先经过平台检查。 |
| 本地取消/工具错误是否证明会议未发生？ | 不证明。取消不回滚外部副作用；超时、异常可转为框架 `ERROR`，而孤立 pending tool 也可被合成 error result。[官方取消说明][cancel-doc][工具执行源码][tool-execution][未决工具修补源码][patch-pending] | 外部写操作账本继续保留 `UNKNOWN`，按原请求号/资源/人工证据核对。 |
| 框架持久化能否替代业务会话、消息与确认？ | 不能。状态槽由本次可信 `userId/sessionId` 决定；Workspace/Memory 另有命名空间、后台任务与文件存储。[状态加载源码][state-load][AgentState字段][state-fields][远程命名空间源码][remote-namespace][后台记忆提取源码][memory-flush] | 沿用现有平台 UserId、业务 Session/Run、正式消息、操作确认和审计边界。 |

## 2. 依据层次与项目既定规则

本文使用四类证据，避免把研究建议写成用户已确认决定：

- **官方文档契约**：固定提交中的官方说明；不能用会继续更新的文档站替代版本核验。
- **静态源码事实**：同一提交中可定位的方法、字段及分支；说明存在该行为，不等于本项目已经通过运行验收。
- **项目已确认规则**：以下本地规格及术语；本次不重新决定这些边界。
- **候选接入建议/未实测**：满足既有规则的必要控制和可用接入点；精确实现、事件 schema、API 与前端呈现仍由后续讨论确定。

项目已确认：可信登录身份映射为稳定 `UserId`，业务 Session 固定员工与发布版本；同一 Session 最多一个活动 Run，确认、取消仍归当前 Run；浏览器断开不取消服务端 Run；服务重启后未完成 Run 待核对，不自动重放模型或会议写操作；状态不能恢复时暂停 Session。预约/取消必须获得当前用户对具体参数的一次性确认，十分钟过期；已发出写请求不能因本地取消而认定撤销。依据：[一期总体设计](../../一期总体设计.md)、[术语](../../../GLOSSARY.md)、[会话与 Run 生命周期](../../数据库设计/会话运行与消息/业务会话运行消息与跨渠道续传设计规格.md#42-会话与-run-生命周期)、[通用工具操作与确认](../../数据库设计/会话运行与消息/通用素材与工具操作设计.md)。

## 3. 流式消息、工具与最终结果

### 3.1 可以复用的原生事件

`call()` 与 `streamEvents()` 共用 `buildAgentStream` 和 `onAgent` middleware；`prepareRun(messages, context)` 只是创建延迟执行句柄，订阅后才运行。无需为了同时获取正文和工具进度分别调用两次 Agent，否则会产生两次执行。[流式执行源码][stream-core][执行句柄源码][run-handle]

| 事件类别 | 固定源码/文档中的能力 | 产品使用边界 |
| --- | --- | --- |
| 生命周期 | `AgentStartEvent`、`AgentResultEvent`、`AgentEndEvent`。[流式执行源码][stream-core][最终结果事件源码][result-event] | 结束事件不能单独判定业务成功；还需流错误/取消、最终 `Msg.generateReason` 和平台提交结果。 |
| 正文 | `TextBlockStart/Delta/EndEvent`，按 `replyId + blockId` 关联。[官方消息与事件说明][message-event-doc][事件类型源码][event-types] | 可用于生成中的正文；未完成片段保留为 Run 过程，不直接变成正式员工消息。 |
| 模型阶段 | `ModelCallStart/EndEvent`，以及可选的 `ThinkingBlock*`。[事件类型源码][event-types][官方消息与事件说明][message-event-doc] | “阶段进度”可由受控状态与工具摘要表达；存在 Thinking 事件不等于应公开完整内部推理。 |
| 工具提议 | `ToolCallStart/Delta/EndEvent`；delta 为尚未完成的参数片段。[官方消息与事件说明][message-event-doc] | 参数片段不能触发写操作确认或执行；待服务端获得并校验完整提案。 |
| 工具执行 | `ToolResultStart/TextDelta/DataDelta/EndEvent`，携带工具调用关联、框架结果状态与 metadata。[事件类型源码][event-types][工具结果事件源码][tool-events] | 框架 `SUCCESS/ERROR/INTERRUPTED/RUNNING` 与外部业务结果分开；显示平台核验后的摘要。 |
| 交互 | `RequireUserConfirmEvent`、`UserConfirmResultEvent`；`RequireExternalExecutionEvent`、`ExternalExecutionResultEvent`。[确认等待源码][pending-confirm][外部结果校验源码][external-result] | 分别表示权限确认和外部结果，不能直接覆盖本平台的人员选择、动态表单和写操作确认语义。 |
| 扩展 | `CustomEvent(name, Map<String,Object> value)`。[自定义事件源码][custom-event] | 可承载扩展通知；它本身不定义通用表单 schema、持久等待、鉴权或响应去重协议。 |

### 3.2 原生关联键与业务身份分开

`AgentEvent` 默认生成随机事件 ID 与时间；未提供平台顺序号或业务 Run 主键。`AgentResultEvent` 只持有最终 `Msg`，没有公共 `replyId`；不同阶段可以各自产生 reply ID，不能假定每条事件属于一个全局不变的 reply ID。`buildAgentStream` 创建根生命周期时，传给 `AgentStartEvent` 的 sessionId 参数实际为 `null`，不能只从该事件确定业务 Session。父子 Agent 来源另有 `source`。这些都要求平台在已知可信执行上下文中关联自己的 Session、Run 和事件顺序。[事件基类源码][event-base][最终结果事件源码][result-event][流式执行源码][stream-core][工具阶段源码][acting-core]

正常 `doCall` 路径在返回最终结果前保存 AgentState；但用户协作中断的状态保存失败会记录 warning 并仍返回 `INTERRUPTED` 消息。因此“收到 AGENT_RESULT”不能作为所有平台与框架存储已经一致提交的通用凭据，更不能直接据此宣布会议成功。[调用保存源码][call-save][协作中断源码][interrupt-save]

官方文档描述可通过已记录事件还原界面；所核验的 SDK `FluxSink` 及随机事件 ID 只说明事件生产能力，未替应用提供业务事件落库、断线游标、重启补发或用户可访问历史。正式 Web 可以恢复累计正文、关键阶段与最终状态；是否保留全部原始 delta、采用哪种传输/恢复协议留给后续事件投影决策。重连读取或重放**已保存输出**不得重新订阅用于执行的源流。一个 `AgentRun` 本身只允许一次订阅，重试须创建新句柄。[官方消息与事件说明][message-event-doc][流式执行源码][stream-core][执行句柄源码][run-handle]

## 4. 等待与人类响应后的继续

### 4.1 权限 ASK：退出本次调用，保存待确认状态

静态调用链如下：[确认等待源码][pending-confirm][等待关联源码][pending-correlation][调用保存源码][call-save][续接入口源码][pending-entry]

```text
模型形成 ToolUseBlock
  → 权限计算，需确认的 tool state = ASKING
  → 在上下文的 assistant Msg 保存待确认 replyId
  → 发出 RequireUserConfirmEvent + RequestStopEvent
  → 返回 GenerateReason.PERMISSION_ASKING
  → doCall 保存 AgentState，本次 SDK 调用结束

服务端收到人类响应
  → 先检查平台身份、原 Run、提案/确认状态
  → 构造 metadata[Msg.METADATA_CONFIRM_RESULTS] = List<ConfirmResult>
  → 用同一受控 userId/sessionId 发起后续 SDK 调用
  → 加载状态，检查确认所指的是当前 ASKING tool ID
  → 应用确认，发出 UserConfirmResultEvent
  → resumeAgent() 直接进入 acting，而非重新生成同一提案
```

所以，**SDK 执行句柄不会跨重启存活，但待确认状态有持久恢复入口**。在持久状态成功提交且平台记录一致的前提下，新 JVM 可以通过后续调用加载同一状态槽处理响应；本次没有实测这一链路，不宣称已具备可靠的端到端恢复。`AgentRun.status() == COMPLETED` 只可能说明这次调用已经完成，也可能是在业务仍等待确认时正常结束。[执行句柄源码][run-handle][续接入口源码][pending-entry][状态加载源码][state-load]

框架验证确认结果引用当前 ASKING ID、无重复 ID；可以只确认一部分待确认工具。但 `ConfirmResult` 明确允许提供**修改后的 ToolUseBlock**与未来调用使用的 `PermissionRule`；`applyConfirmResults` 会替换工具内容并把规则加入权限引擎。它不是本平台“固定提案、一次性确认、不得长期放行会议写操作”的完整实现。[确认校验源码][confirm-validation][确认应用源码][confirm-apply][确认结果类型][confirm-result]

候选接入约束：Web 仅提交平台交互/确认标识及回答，Java 端从已保存提案重建 SDK 对象；禁止客户端自选操作者、工具、执行目标、参数、长期权限规则。按既定规格重新校验归属、操作摘要、十分钟期限、授权/安全/身份版本，并以条件消费阻止重复响应；参数改变须另建提案，已有 UNKNOWN 写结果先核对。SDK 接受 `ConfirmResult` 后也不能绕过最终会议 Adapter 的发送前检查。依据：[既定操作确认协议](../../数据库设计/会话运行与消息/通用素材与工具操作设计.md#4-独立的一次性操作确认)、[确认应用源码][confirm-apply]。

### 4.2 外部执行等待：由后续输入提供结果

标为外部执行的工具可返回 suspended，框架发出 `RequireExternalExecutionEvent`，把待外部执行 reply ID 保存到上下文，然后返回 `TOOL_SUSPENDED`。调用方在下一次调用提供匹配 pending tool ID 的 `ToolResultBlock`；框架校验、加入上下文并发出关联的 `ExternalExecutionResultEvent`，之后继续处理。某些 `returnDirect` 工具可以直接形成结果，通常仍会继续推理，不能把“提交人类响应”理解成只落库、绝不再调用模型。[外部执行等待源码][external-wait][外部结果校验源码][external-result][续接入口源码][pending-entry]

这提供了通用输入收集的候选接入点：模型提出受控输入请求，由平台校验 schema、持久化交互请求、前端受控渲染、服务端校验用户回答，再将合法结果交给后续调用。这里没有选定使用外部工具、middleware 还是其他具体实现；所核验的原生事件也未定义本平台通用动态表单契约。`CustomEvent` 可以传通知，不能单独完成持久等待。[自定义事件源码][custom-event][官方外部工具说明][tool-doc]

动态表单的数据收集与人员选择不能变成预约/取消授权；会议写操作仍由服务端 Adapter 执行。平台要保存的交互事实至少包括：可信 owner、业务 Session/Run、交互类型、待响应请求标识与修订、固定展示内容/schema 或可核验快照、对应工具调用、创建/有效期/状态、回答及消费结果。以上是待后续 schema 决策落实的责任清单，不在本次新增表或锁定 API。既有术语见[动态表单与操作确认](../../../GLOSSARY.md)。

#### 4.2.1 Issue #19 讨论期间的补充源码核验

2026-10-23 对同一固定提交补核：**普通的自定义工具也可以先校验、再主动挂起**。普通 `ToolBase.callAsync` 抛出 `ToolSuspendException` 会被执行器转换为 `ToolResultBlock.suspended(...)`；框架按结果的 `isSuspended()` 汇总等待，不要求工具设置 `externalTool=true`。官方异常类示例即为未标记 external 的普通 `@Tool`。相反，`externalTool=true` 在执行器中会提前返回挂起结果，跳过工具方法体，不能把平台校验及落库放在该方法体里。[普通工具挂起示例][input-suspend-example]、[执行器分支][input-executor-branches]、[挂起结果汇总][external-wait]。

由此支持 Issue #19 随后确认的“普通受控输入 Tool 校验合法后保存候选并挂起”方向，具体契约见[交互生命周期设计规格](../../正式应用设计/动态表单与交互请求生命周期设计规格.md)，不是已完成集成。非法提案可作为工具错误反馈模型，**最多自动修正一次须由平台控制预算**，不是 SDK 默认保证。若已形成多个 pending，只回填其中一条可能继续执行其余 pending，不能静默截取第一条。[后续输入入口][pending-entry]、[外部结果应用][external-result]。

`onActing` 在核心 Toolkit 调度前取得整批工具调用，提供平台执行前检查入口；不过此前已运行 `firePreActing`，因此平台必须控制 Hook 和工具注册，不能据此宣称任意第三方 Hook 都无副作用。原生串行执行也不会在得到 suspended 结果后自动停止批次后续工具。多输入请求、输入与其他工具混合的批次需要平台显式处理，尚未做运行验证。[批次入口][acting-core]、[串行执行][input-executor-serial]。

等待事件在最终 `saveState` 前发出；`ExternalExecutionResultEvent` 同样早于本次调用最终保存。因此两个事件都不能独立证明“已持久就绪”或“已持久消费”。平台需要分别核实交互事实、框架 pending 和回答消费记录；错开失败时待核对，不能靠再次调用 SDK 探测。[挂起事件][external-wait]、[回答应用][external-result]、[调用保存][call-save]。

等待调用的 SDK 句柄已完成时，终态 `RunControl.interrupt()` 返回 false；会话级中断也只针对 in-flight 调用。回填错误结果后可能继续模型，因此本次没有证明一个可直接调用的“空闲等待 pending 纯清理”高级 API。正式设计要求平台核验 pending 已持久终结且没有后续模型／工具执行，能力未证明时保持待核对并暂停 Session，不能把清缓存或全量 clearContext 冒充局部收敛。[终态中断][waiting-run-control]、[后续输入入口][pending-entry]。

### 4.3 不是长时间占用浏览器等待

原型中基于浏览器 Promise 的等待只可作为交互演示；正式状态应从服务端持久记录读取。切换会话、关闭页面和断线是视图/连接行为，不能直接推断业务 Run 取消，更不能推断外部写请求撤销。反过来，重新打开界面也不能自动调用 SDK 恢复模型或工具。已有业务规则要求确认/取消仍作用于原 Run，因此同一业务 Run 可关联多次 SDK 调用/句柄；具体字段及与传输协议 run 的关联交给后续事件与交互设计，不重新决定业务归属。[既有会话生命周期](../../数据库设计/会话运行与消息/业务会话运行消息与跨渠道续传设计规格.md#42-会话与-run-生命周期)、[执行句柄源码][run-handle]

## 5. 取消、异常与进程重启

### 5.1 取消并不是外部操作回滚

官方文档明确区分：[官方取消说明][cancel-doc][执行控制源码][run-control]

- `AgentRun.cancel()` 立即取消响应式订阅，支持尚未开始/排队的调用；结果为取消信号，不能保证进入协作中断的保存路径。
- `AgentRun.interrupt()` 对运行中调用触发协作中断；未开始则取消。用户协作中断可返回 `GenerateReason.INTERRUPTED`，句柄仍可能正常完成。
- 会话级 `interrupt(userId, sessionId)` 选择该槽当前运行的执行；要精确控制某次排队/运行调用须用该句柄。中断信号不持久化。
- 取消无法回滚外部副作用，无法强行终止不响应取消的阻塞工具。

因此平台的“取消中”与最终业务状态须单独保存；先阻止后续步骤，已经发出的预约/取消请求继续核对。取消结果、`AgentEndEvent`、框架 `ToolResultState.ERROR` 均不足以清除 `UNKNOWN`。这沿用既定业务规则，不新增一套外部撤销语义。[通用操作状态](../../数据库设计/会话运行与消息/通用素材与工具操作设计.md#3-通用工具操作)、[工具执行源码][tool-execution]

### 5.2 会触发再次推理/执行的源码入口

| 固定源码事实 | 必须避免的错误推断 | 满足既有项目规则的处理 |
| --- | --- | --- |
| `shutdownInterrupted` 保存于 AgentState；下次 `doCallInner` 检测并清除后将本次 `msgs` 全部置空，没有按平台提交标识或内容比较是否真的重复。[停机保存源码][shutdown-save][续接入口源码][pending-entry] | “框架已经做了业务请求去重，所以可以直接提交新的用户输入。” | 重启/异常状态先核对；不能把新用户输入直接送进尚未修复的旧槽。业务提交标识及去重由平台掌握。 |
| 无 pending tool 时进入 `coreAgent()`；有 pending 且空输入时执行 `resumeAgent() → acting(0)`。[续接入口源码][pending-entry][直接恢复工具执行][resume-acting] | “只调用空输入恢复不会调用模型或会议工具。” | 启动扫描、页面重连、历史读取不能调用这些执行入口。只有经平台明确允许的后续操作才可触发。 |
| 默认 `enablePendingToolRecovery=false`；开启后，对非 ASKING 的孤立 pending 可合成错误结果再继续推理。[默认恢复配置][recovery-config][未决工具修补源码][patch-pending] | “合成 ERROR 就证明上游写操作失败，模型可以换参数/请求号再试。” | 以持久工具操作账本核对实际效果；不能靠框架合成结果结束 UNKNOWN。 |
| 用户中断也会为 pending tool 合成 error result，系统停机则保存状态并返回停机异常。[协作中断源码][interrupt-save][未决工具修补源码][patch-pending] | “中断消息说明所有工具都未发送。” | 检查发送领取记录及上游证据，已发出/可能发出均进入核对。 |
| SDK 工具默认一次尝试，但执行配置可覆盖重试；异常/超时可转成普通 error result。[默认执行配置][execution-defaults][工具执行源码][tool-execution] | “没有默认自动重试就保证不会重复写；或者配置重试一定安全。” | 写 Adapter 保持稳定操作/请求标识，核对实际 provider 幂等能力；禁止把整轮模型重跑当作结果核对。 |

框架的状态恢复说明“下次调用可以继续”，并不是 durable execution 的 exactly-once 承诺；上述静态入口也不表示 SDK 构建完就主动重放所有会话。风险出现在平台或客户端**再次触发调用**时。真实强杀还可能来不及运行 JVM 优雅停机 hook，不能假设每次崩溃都留下完整 pending/flag；以平台持久事实和受控状态核验为准。[状态加载源码][state-load][调用保存源码][call-save][停机保存源码][shutdown-save]

### 5.3 重启恢复的责任顺序

依据既定规格，后续实现至少应满足下列顺序；具体事务与状态字段仍由正式设计落实：

1. 读取平台未终结 Run、交互、工具操作和已保存输出；将未完成执行置于待核对，恢复可查看性。此步骤不订阅 SDK 执行流、不调用模型。
2. 重新核验登录主体、账号/员工授权、会话固定发布版本、状态槽、Memory 代次及必需文件。已有会话状态异常缺失不能作为首次空状态初始化。
3. `SENT` 或可能发送且无确证结果的写操作按 `UNKNOWN` 处理。按 Adapter 已声明并实际具备的请求号/资源号查询能力核对；没有能力则转人工，不换号重发。
4. 已提交的确认/回答先按持久消费记录去重；不得仅凭旧 SDK pending 仍存在就再次执行。未响应交互是否仍有效须按当前规则判定，不能因进程重启自动放行或变更原参数。
5. 得到用户明确继续/重试，且状态与业务操作已经核对后，才决定如何发起后续 SDK 调用；明确失败 Run 的重试按原规格另记新业务 Run，不能复活未知操作。平台已确认“重启不自动重放”不因 SDK 可续接而改变。
6. 允许独立恢复的有效记忆任务，仅对成功来源和有效代次去重执行；不得顺便重放原模型业务 Run、会议操作或正式消息。

上述项目依据：[Run 恢复与重试](../../数据库设计/会话运行与消息/业务会话运行消息与跨渠道续传设计规格.md#42-会话与-run-生命周期)、[UNKNOWN 与发送前落盘](../../数据库设计/会话运行与消息/通用素材与工具操作设计.md#3-通用工具操作)、[状态/记忆恢复边界](../../数据库设计/状态记忆与工作区/状态记忆与工作区隔离存储设计规格.md#8-权限审计与恢复边界)。

## 6. AgentState、Workspace、Memory 的持久化边界

| 数据面 | v2.0.4 的静态事实 | 仍由平台负责 |
| --- | --- | --- |
| AgentState | 保存上下文、摘要、reply/迭代位置、权限/工具/任务/Plan Mode 状态、停机标志；配置 store 后每次调用按 `(userId, sessionId)` 重新加载，调用结束保存。SDK 允许缺省 ID 回退。[AgentState字段][state-fields][状态加载源码][state-load][调用保存源码][call-save] | 每次传可信稳定 UserId 与服务器生成的状态槽；保持业务 Session/发布版本绑定、识别异常缺失；原始消息、Run、确认和审计独立持有。 |
| 状态版本 | `saveIfVersion` 可选；不支持版本的实现退化为无条件写；默认冲突策略 `OVERWRITE`。[状态存储契约][state-store][状态冲突默认策略][state-conflict] | 同一 Session 活动 Run 控制、状态一致性核对，接入时验证 CAS/冲突策略；不能从版本字段推断业务幂等。 |
| Harness 默认 | 未配置持久后端时创建本地 JSON 状态 store，默认 filesystem scope 为 USER；`disableSessionPersistence()` 在该版本明确为 no-op。[Harness默认装配][harness-defaults][持久化开关源码][disable-persistence] | 生产显式配置已经选定的框架 MySQL 状态后端，不能用这个开关控制业务恢复或停写。 |
| Workspace | filesystem 独立于 AgentState；共享后端按 namespace+path 保存。默认 RemoteFilesystemSpec 的 Memory/Session/任务路由共用同一 scope；USER/SESSION 都是框架桶，不自动表达本项目全部归属维度。[远程命名空间源码][remote-namespace][Workspace路由源码][workspace-routes] | 延续用户×员工共享 Memory、用户×员工×Session 运行文件、不可变发布资产的独立路由；引用文件必须可核验与恢复。 |
| Memory | 原生调用后 flush 在 `onAgent.doOnComplete` 调度，后台执行时读取当时上下文；不是按平台成功 Run 的冻结正式来源取数。[后台记忆提取源码][memory-flush] | 成功来源、generation/document_revision、手工更正优先、持久任务、授权及去重。不能把“本次 SDK 调用完成”当作“业务 Run 成功，允许学习”。 |
| 内容提交 | Workspace 追加/覆盖走 `uploadFiles`；Remote 上传为无条件 put。JDBC store 各操作自行获取连接；不是平台业务事务的原子组成。[Workspace追加源码][workspace-writes][远程上传源码][remote-upload][JDBC存储源码][jdbc-store] | 沿用既有受控提交点、内容 CAS、任务来源与应用记录；框架状态、平台 SQL、KV 和对象文件错开时待核对。 |

Memory 的读取、提取、合并可以继续复用原生组件，但不能默认开启所有写路径就宣称满足“只学成功来源、清空后旧任务不复活”。既有[长期记忆机制调研](../状态与持久化/AgentScope长期记忆机制与自动提取调研.md)与[正式隔离存储规格](../../数据库设计/状态记忆与工作区/状态记忆与工作区隔离存储设计规格.md#9-框架复用与实施约束)仍然适用。本轮没有验证新的默认 hooks 修复，亦没有执行任何记忆写入。

## 7. 与既有笔记的增量与后续决策

| 对比项 | 既有 v2.0.4 研究 | 本次核验的增量 |
| --- | --- | --- |
| 稳定版本 | 既有研究固定 v2.0.4/0b6743b。 | 实时发布列表仍同一稳定版本；v2.1.0-BETA1 不能冒充稳定候选。无稳定版升级差异。 |
| 状态/Workspace/Memory | 已区分数据面、隔离与受控提交限制。 | 本次重新检查关键源码，原限制仍在；等待确认的一次 SDK 完成也可能触发默认 Memory flush，需按平台成功来源过滤。 |
| 暂停恢复 | 既有笔记主要讨论恢复上下文。 | 明确区分进程内句柄与可持久 pending；续接为新 SDK 调用，权限确认仍归原业务 Run。 |
| 事件与界面 | 既有设计要求流式正文和过程摘要。 | 原生事件提供基础，但结果事件、replyId、SDK sessionId 不能替代平台身份/状态/事件顺序。 |
| 异常重启 | 已确认不自动重放与 UNKNOWN 核对。 | 明确列出 shutdown 丢弃输入、空输入执行 pending、合成 error 与配置重试的入口，作为接入阻断与验收点。 |

本研究已足以回答“稳定候选版本、原生能力及其恢复限制是什么”这一纯研究问题。关闭研究议题不代表正式应用已接入或重启验收完成，也不替用户锁定依赖与架构。

后续需要人类参与决定的是：在上述已定业务边界下，如何投影原生事件；通用交互请求与合法响应采用什么具体结构；动态表单如何落到外部执行/中间件等接入点；多个 SDK/协议执行如何关联一个业务 Run；在状态与平台事实错开时采用什么受控修复流程。这些决定不重新讨论可信 UserId、原 Run 归属、一次性确认或重启不自动重放。

## 8. 尚未实测的验收点

| 场景 | 必须观察到的结果 |
| --- | --- |
| 两用户、同用户两 Session、员工发布版本不同 | 状态、文件、Memory 路由与操作者身份正确；SDK 缺省回退不进入正式入口。 |
| ASK 后保存完成，再重建进程读取 | 可查看待确认提案与原 Run；启动和页面重连没有模型或会议写调用。 |
| 用户响应后继续、双击/重发、旧页面响应 | 合法响应只消费一次；仍归原 Run；过期、串用户、参数/绑定变更均拒绝。 |
| SDK 状态保存与平台交互记录错开 | 进入待核对；不凭任一单边记录直接执行写工具。 |
| 提案形成前、确认后发送前、发送后无响应、结果已落库前等断点退出 | 每个断点都能区分确定未发送与可能已发送；后者保留 UNKNOWN，不自动重放。 |
| `shutdownInterrupted=true` 后首次请求 | 不丢失新用户输入，不把保存上下文当成可自动执行许可。 |
| cancel/interrupt、阻塞工具、网络超时 | UI 与持久状态如实反映取消中/待核对；上游副作用经实际核对判定。 |
| 浏览器断线、切换会话、重开历史 | 不取消服务端 Run；可恢复累计输出/关键进度；不再次订阅执行流。 |
| SDK 调用因等待、拒绝或中断而正常完成 | 不误判业务成功，不把中断片段写成正式回复，不启动不合格 Memory 学习。 |
| AgentState、KV、对象资料部分恢复或缺失 | 依赖它们的会话暂停，说明可核验原因；不以空状态或残缺历史悄悄续跑。 |

本次只做文档、源码及链接入口核验。以上没有运行结果，不应进入“已完成验收”清单。

## 9. 可公开同步到研究议题的要点

- 本次核验最高稳定候选仍为 AgentScope Java v2.0.4，固定提交 `0b6743b263ba8e39eb417e35048026f1e0196548`；v2.1.0-BETA1 是预发布。本次无稳定版升级差异，候选不等于项目已经锁定依赖。
- 原生流支持正文、模型/工具过程、确认/外部执行及最终 Msg；事件 ID、replyId、SDK sessionId 与执行句柄不能替代平台可信 UserId、业务 Session/Run、正式消息和持久交互/输出记录。
- AgentRun/RunControl 为进程内执行控制；ASK 和外部执行等待可通过 AgentState 保存 pending 与关联信息，后续新 SDK 调用续接。业务确认仍归原 Run，不能把 SDK 调用结束当成业务成功。
- ConfirmResult 可修改工具内容并附加长期权限规则；平台须按既有一次性操作确认协议重建并校验，不能把客户端 SDK 对象直接透传到会议工具。
- 重启后不自动调用模型或写工具。空输入、shutdown 恢复及 pending 修补均可能触发推理/执行；本地取消和框架 ERROR 不证明外部未生效，UNKNOWN 按原操作核对。
- 研究结论可支持关闭纯研究问题；事件投影、通用交互与动态表单接入方式仍待下游决策，真实状态/Memory/会议 MCP/重启恢复仍待接入验收。

## 官方资料与固定源码入口

以下源码链接均固定到本次稳定候选提交；行号从实际读取的该标签源码核对，未以开发分支代替。

[release-stable]: https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.4
[release-list]: https://github.com/agentscope-ai/agentscope-java/releases
[release-beta]: https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.1.0-BETA1
[source-commit]: https://github.com/agentscope-ai/agentscope-java/commit/0b6743b263ba8e39eb417e35048026f1e0196548
[message-event-doc]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/docs/v2/zh/docs/building-blocks/message-and-event.md#L121-L183
[cancel-doc]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/docs/v2/zh/docs/building-blocks/agent.md#L213-L258
[tool-doc]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/docs/v2/zh/docs/building-blocks/tool.md#L629-L657
[stream-core]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L1038-L1149
[event-types]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/event/AgentEvent.java#L43-L82
[event-base]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/event/AgentEvent.java#L112-L162
[result-event]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/event/AgentResultEvent.java#L22-L57
[custom-event]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/event/CustomEvent.java#L23-L82
[tool-events]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L3260-L3305
[run-handle]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/agent/AgentRun.java#L32-L141
[run-control]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/agent/RunControl.java#L29-L122
[generate-reason]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/message/GenerateReason.java#L38-L89
[pending-entry]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L1821-L1915
[pending-correlation]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L2028-L2055
[pending-confirm]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L3015-L3073
[confirm-validation]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L1944-L2026
[confirm-apply]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L2074-L2129
[confirm-result]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/event/ConfirmResult.java#L25-L93
[external-wait]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L3287-L3305
[external-result]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L2235-L2324
[resume-acting]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L2388-L2394
[acting-core]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L2858-L2933
[patch-pending]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L2144-L2223
[recovery-config]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L4800-L4826
[interrupt-save]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L4338-L4372
[shutdown-save]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/shutdown/ActiveRequestContext.java#L60-L93
[call-save]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L1204-L1234
[state-load]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L647-L706
[state-fields]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/state/AgentState.java#L57-L140
[state-store]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/state/AgentStateStore.java#L63-L137
[state-conflict]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L330-L352
[harness-defaults]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java#L2377-L2438
[disable-persistence]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java#L2269-L2273
[remote-namespace]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/spec/RemoteFilesystemSpec.java#L333-L349
[workspace-routes]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/spec/RemoteFilesystemSpec.java#L217-L285
[memory-flush]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/MemoryFlushMiddleware.java#L153-L273
[workspace-writes]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/main/java/io/agentscope/harness/agent/workspace/WorkspaceManager.java#L365-L399
[remote-upload]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/remote/RemoteFilesystem.java#L498-L534
[jdbc-store]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-extensions/agentscope-extensions-jdbc/src/main/java/io/agentscope/extensions/jdbc/store/JdbcStore.java#L63-L158
[execution-defaults]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/model/ExecutionConfig.java#L129-L164
[tool-execution]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/tool/ToolExecutor.java#L451-L552
[input-suspend-example]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/tool/ToolSuspendException.java#L29-L35
[input-executor-branches]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/tool/ToolExecutor.java#L231-L300
[input-executor-serial]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/tool/ToolExecutor.java#L388-L402
[waiting-run-control]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/agent/RunControl.java#L68-L92
