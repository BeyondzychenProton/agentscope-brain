# SDK 挂起终结与检查点接缝补充核验

> 日期：2026-10-24，Asia/Shanghai。  
> 服务于 [#22](https://github.com/BeyondzychenProton/agentscope-brain/issues/22) 对 [#19](https://github.com/BeyondzychenProton/agentscope-brain/issues/19) 的接缝落实；输入为[动态表单规格](../../正式应用设计/动态表单与交互请求生命周期设计规格.md)和[运行与交互契约核验](AgentScope运行与交互契约核验.md)。  
> 固定边界：AgentScope Java **v2.0.4**，提交 **`0b6743b263ba8e39eb417e35048026f1e0196548`**。本文只核查该提交的官方源码，不升级依赖，不编写运行适配代码，不修改 DDL 或既定设计，不访问真实模型／MCP。下文的候选接法需要用户后续选择及隔离验收。

> 后续决定更新：用户已在 #22 Q15 采用 v2.0.4 作为正式设计基线，并于 2026-10-25 采用 Q17 的专用状态适配器及隔离验证后启用机制。下文候选措辞保留研究经过；版本和机制采用均不等于已实现或已验证能力，未证明时继续 RECONCILING。

## 1. 影响设计的结论

1. **持久 pending 可以只读核验，但原生等待事件不是保存凭据。** 框架把等待关联写进上下文并发布事件后，才在调用收尾保存整个 AgentState；项目还须把可信调用键与这一次保存关联起来。[等待及保存顺序][react-wait][react-save]
2. **固定版本提供可用的底层状态接缝，并非只能永远停在待核对。** `AgentState` 公开可修改上下文，`AgentStateStore` 公开版本读取和 CAS 保存；`ReActAgent` 也公开 get → mutate → save 入口。可研究一个只操作状态、不调用 SDK 执行入口的项目适配器。[状态结构][state][版本接口][store][公开状态入口][react-public]
3. **没有在所核公开入口中找到专用的“终结指定 pending、保留历史且不继续执行”高级 API。** 利用上述底层接缝完成这一语义，是项目需要承担并验证的适配；不是 SDK 原生承诺，更不是本次已经验收通过。[公开状态入口][react-public][执行续接入口][react-resume]
4. **单个状态版本不等于 SDK 消费证明。** 官方 Store 不携带平台 RunId、SDK 调用键、响应 ID 或消费回执；需由项目补充写入归属与前后状态证据。证据不完整仍按既定 `RECONCILING` 处理。[Store 接口][store][JDBC 版本保存][jdbc]

## 2. 本次源码身份与核验方法

读取本地既有源码副本 `C:\Users\shanH\AppData\Local\Temp\agentscope-runtime-contract-20261023\agentscope-java-2.0.4`，并从官方 `raw.githubusercontent.com/agentscope-ai/agentscope-java/<固定提交>/...` 读取下列 8 个文件逐字节比对 SHA-256，**8 项均一致**。因此本文行号来自与固定官方提交一致的本地原始文件，未用开发分支替代。

| 关键入口 | 作用与已比对 SHA-256 |
| --- | --- |
| [ReActAgent.java][react-save] | 调用保存、等待／回答、公开状态 API；`14cfa9f70f771fd0cdf7ee19b51cd89acca3a3a1a260f492e870a496e83e41ae` |
| [AgentState.java][state] | 完整状态与可修改 context；`b459c820be4dde4530071fd22ec75f86d572640f9c6d0ccd669bb60bbd4fe9b3` |
| [AgentStateStore.java][store] | 版本读取／条件保存；`c8341f120665c6643f243b7f28e035ea5478a82807f8287136b59b95b090d617` |
| [JdbcAgentStateStore.java][jdbc] | JSON、版本及 SQL 提交；`e9974a69d5d4cb6ab95ab6356b9d63f9727838fa678ebfd9cec151becd1f5009` |
| [MessageUtils.java][pending] | pending 的实际判定；`5f741ce2bbad51a065992248e986ef605330e5e40f87e69e593ca1bcee80b849` |
| [Msg.java][msg] | 等待 metadata 及保留原内容的 metadata 副本；`e36a1aacc8cb5b0d36220a66afee0d23987b64689b9452482abc6861e9dc9ef2` |
| [ToolResultBlock.java][result] | 匹配工具的 ERROR 结果与 suspended 标记；`79d90f39f4eab6b2e01750c7518079bc4a4537899d91732e19fb733e1d18866c` |
| [RunControl.java][control] | 已终结执行句柄的 cancel／interrupt；`468657f8dac2238c33a3bebf4d2825415454e172b125038bf852fe9ba6950f9c` |

这是静态源码及来源身份核验；本次没有创建 Agent、执行 SDK 调用或运行下文探针。

## 3. 已证实的框架事实

### 3.1 pending 是上下文关系，没有独立可消费记录

`MessageUtils.pendingToolUseIds` 读取最后一条 ASSISTANT 消息里的 ToolUse ID，排除整个 context 中已有 ToolResult ID。其判定并不验证结果是不是业务成功，也不核对本平台响应归属；仅通过新添一条 ASSISTANT 消息遮住旧 ToolUse 就可能让扫描看不到原等待。因此**pending 集合变空不能单独证明消费或安全终结**。[pending 判定源码][pending]

挂起时 `ReActAgent` 把外部等待 reply ID 放在承载 ToolUse 的 ASSISTANT 消息 metadata，发布 `RequireExternalExecutionEvent`；挂起结果本身不会作为已执行结果写入 context。最后返回的 `TOOL_SUSPENDED` Msg 与已落盘上下文是不同观察对象。[等待关联][react-metadata][挂起结果处理][react-acting][等待事件][react-wait]

### 3.2 普通完成路径的保存可观察，缺少平台调用归属

普通 `doCall` 在 `doCallInner` 产出结果后执行 `saveStateToSession`，保存完成才返回结果。保存使用 `scope.state`、该槽位和加载版本；无 Store 时直接完成空操作。`getAgentState(userId,sessionId)` 则可能返回本地缓存，且状态缺失时会创建新状态；它不能作为“持久状态确实存在”的读回证据。[调用收尾][react-save][保存与 CAS][react-cas][公开读取][react-public]

`AgentStateStore.getVersioned` 提供状态与版本；接口默认实现不保证版本化，`UNVERSIONED=-1` 同时用于不支持版本和 CAS 冲突。`JdbcAgentStateStore` 的实现会读取同一行的 JSON 与版本，`saveIfVersion` 在写事务内取得本次写入版本，事务完成后返回；冲突返回 `-1`，提交异常抛错。它每次从 DataSource 取连接并自行 commit，不能据“都用 MySQL”推定与平台 MyBatis-Plus 事务原子提交。[接口契约][store][JDBC 读取与条件写][jdbc][JDBC 提交][jdbc-tx]

**推论：** 项目可以在自己控制的 Store 接入层关联“可信 SDK 调用键 → 状态槽／代次 → 此次实际保存版本与状态摘要”，然后通过独立读回核验。官方 Store 只负责槽位、状态键和版本，没有现成平台 checkpoint／消费回执。不能把 SDK replyId 或一次无归属的版本增加直接当作该调用的 checkpoint。[Store 参数][store][保存调用参数][react-cas]

### 3.3 回答被接纳后，仍可能继续模型或其他工具

匹配 ToolResult 的输入会先进行 ID／重复检查，再发布 `ExternalExecutionResultEvent`、清除外部等待 metadata 并加入上下文。上述事件发生在调用最后保存之前。仍有 pending 则进入 acting；全部解决后通常进入 coreAgent／reasoning。[结果接纳][react-answer][续接分派][react-resume]

存在严格条件下的 `returnDirect` 快捷返回分支，但要求原批次、当前 pending 及本次全部结果一致，且所有对应工具都满足 returnDirect。不能把“随便回填 ERROR 再调用 SDK”当作通用无执行终结 API。[续接条件][react-resume]

### 3.4 底层状态 API 不需要启动模型执行

`AgentState.contextMutable()` 返回可修改列表；状态支持 JSON 往返。`Msg.withMetadata` 创建保留消息 ID、内容、时间与 usage 的副本。`ToolResultBlock.error(toolId,...)`、`withIdAndName` 可以构造带匹配身份的非 suspended 错误结果。[状态结构][state][消息副本][msg-copy][结果类型][result][结果身份副本][result-id]

`ReActAgent.getAgentState`／`saveAgentState` 公开支持 get → mutate → save，但读取可能缓存、缺失可能造新状态，保存返回 void 且无 Store／未加载时会静默不做事。底层 `getVersioned`／`saveIfVersion` 更直接暴露存在性与本次写入版本。**在所核调用图中，这些数据操作及官方 JDBC 保存不经过 `call`、`stream`、reasoning 或 Toolkit 分派**；这支持无执行状态适配的可行性判断，不代替对项目扩展与运行竞争的验收。[公开 API][react-public][Store][store][JDBC][jdbc]

## 4. 可供后续选择的项目适配候选

以下是由源码推导的候选，**本次未选择、未实现、未实测**。最小先覆盖 #19 普通平台输入工具的唯一 pending；不能据此自动宣称任意 ASK 批次、未知外部写操作也已安全收敛。

1. 取得平台对该 Session／原 Run／状态槽的排他控制，阻止新调用及领取；确认当前及排队 SDK 调用均已结束，旧执行者不能继续写回。CAS 不能代替执行隔离；使用 `OVERWRITE` 的陈旧写入仍可能覆盖新状态。[保存冲突策略][react-cas][缓存与在途引用说明][react-public]
2. 直接版本化读取**已经存在**的目标 `agent_state`，核实可信 owner、发布版本／代次映射、原调用键、唯一 pending ID／名称／冻结参数摘要及等待 metadata。缺失、错配、多 pending 或未决外部结果均拒绝自动修复。[状态读取][store][pending 判定][pending]
3. 在独立状态副本中保留原 ToolUse 和历史，只为已核实的目标追加明确的终结 ERROR ToolResult；清除已结束等待对应的关联 metadata，保留其他 metadata。终结原因来自平台取消／失效事实，不能伪造为用户提交回答、会议成功或外部撤销。框架内部也通过匹配错误结果补齐未决 ToolUse，但其自动恢复会继续执行，不能直接启用该恢复来代替本候选。[错误结果构造][result][metadata 清理规则][react-metadata][框架补齐方式][react-patch]
4. 对读取版本执行 CAS，要求取得确定的新版本；持久化本次适配身份及前后证据，再独立读回：原 ToolUse／冻结参数和既有历史保留，匹配终结结果恰一份，目标 pending 确实关闭，等待 metadata 一致。全过程不调用任何 `call`／`stream`／空输入，也不为验证而触发一次执行。[CAS 契约][store][结果与 pending 关系][pending]
5. 核实平台卡片失效、SDK 终结保存及无未决外部结果后，才能按既定协议收尾。状态写入后平台收尾失败、提交结果不明、CAS 冲突或证据不完整时保留 `RECONCILING`，不能猜测成功后释放 Session。

上面第 1、2、4、5 项的可信 Run／代次／checkpoint 归属与平台收尾是**项目责任**，官方源码没有为本平台自动提供。即使只改存储状态也不能与尚在执行的 Agent 并行；下一次正常调用会从 Store 重新加载，但这不会使旧调用持有的引用自动失效。[每次调用重载][react-reload][在途引用][react-public]

## 5. 就绪与消费证明应分别具备的证据

| 平台判断 | 最少证据；不是框架现成回执 |
| --- | --- |
| FORM 开放提交 | 原 SDK 调用正常结束；关联到该调用的成功保存记录及实际版本；独立读回存在的状态；唯一 pending 与冻结 ToolUse、归属和等待关联一致；平台交互已提交；不存在取消／未决批次 |
| 回答已被 SDK 持久消费 | 原响应一次领取及对应 SDK 调用键；状态前版本；匹配结果被该次调用接纳的关联事实；本次完整结果／异常情况；与该调用关联的成功保存及状态后版本／摘要；旧 pending 与匹配结果变化一致。不能仅以 pending 消失、进程事件或版本增加作证 |
| pending 安全终结 | 排他与 fence 证据；原始持久快照；受控终结身份及目标；确定 CAS 提交／读回；ToolUse 与历史保留；匹配终结结果和等待 metadata 一致；无模型／工具执行；无未决外部结果 |

上述字段是为满足已定契约而提出的证据要求。框架事件是过程证据，持久版本只标识一次状态写入，两者都须与项目调用归属结合。若上下文压缩或项目扩展使匹配结果无法从最终快照追溯，应补充可核验的写入链路证据；无法证明时保持待核对，不把扫描不到旧 ID 解释为消费成功。[结果接纳][react-answer][调用保存][react-save][pending 只扫描末条 ASSISTANT][pending]

## 6. 不能从本次静态研究证明的事项

- 项目正式依赖、MyBatis-Plus 事务、Store 适配和调用关联已经实现。
- 任意权限 ASK、多个 pending、损坏状态、跨发布版本状态都可用同一变换安全修复。
- 本地终结 ERROR 代表真实会议写请求未发送、失败、已撤销或未知结果已消除。
- 调用句柄结束、`interrupt()` 成功或 UI 卡片失效等于持久 pending 已终结。已终结 `RunControl` 的 cancel／interrupt 会直接返回 false。[句柄源码][control]
- 单次 CAS 成功可以抵御所有其他保存策略或失控执行者；`OVERWRITE` 明确绕过旧版本冲突。[保存策略][react-cas]
- 源码未触发模型的预期已经通过真实进程重启、MySQL 故障与副作用计数验收。

## 7. 后续必须隔离运行的探针

使用无网络的假 Model、计数工具和隔离状态库；不得连接真实模型／会议 MCP。本表是后续实施准入建议，本次没有执行。

| 探针 | 必须观察的结果 |
| --- | --- |
| 合法唯一输入挂起；保存前／后分别断进程 | 保存前事件不能开放表单；保存后新进程只读识别匹配 pending、版本及等待 metadata |
| 挂起后通过状态候选终结，再以全新 Agent／进程只读加载 | 模型调用增量 0、所有工具执行增量 0；匹配终结结果恰一份，ToolUse／参数及旧历史未丢，等待关联不复活 |
| 同一终结操作重试、双执行者竞争、旧 fence 写回 | 不重复追加终结结果；冲突拒绝；旧调用不能覆盖新状态；证明失败进入待核对 |
| 保存前抛错、commit 结果不明、保存后平台收尾失败 | 不把异常当成功；可确定读回时按证据收尾，否则 Run 保持 `RECONCILING` 且 Session 不接纳新 Run |
| 正常 FORM 回答续接、SDK 已接纳但保存失败、同次产生下一张卡 | 接纳与消费分开；失败不自动重送；旧消费与新卡就绪有各自持久证据 |
| 状态缺失、错 owner／代次、额外 pending、遗留 ASK／shutdown 标志、未决会议写入 | 不创建空状态假装恢复，不发空输入，不运行其他 pending；超出受支持边界拒绝并待核对 |

## 官方固定源码

[react-save]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L1204-L1234
[react-cas]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L470-L643
[react-reload]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L646-L706
[react-resume]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L1821-L1915
[react-metadata]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L2028-L2072
[react-patch]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L2144-L2187
[react-answer]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L2235-L2316
[react-acting]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L2920-L2985
[react-wait]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L3287-L3305
[react-public]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L4412-L4700
[state]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/state/AgentState.java#L29-L258
[store]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/state/AgentStateStore.java#L61-L138
[jdbc]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-extensions/agentscope-extensions-jdbc/src/main/java/io/agentscope/extensions/jdbc/state/JdbcAgentStateStore.java#L204-L296
[jdbc-tx]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-extensions/agentscope-extensions-jdbc/src/main/java/io/agentscope/extensions/jdbc/state/JdbcAgentStateStore.java#L528-L548
[pending]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/util/MessageUtils.java#L75-L170
[msg]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/message/Msg.java#L73-L92
[msg-copy]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/message/Msg.java#L683-L712
[result]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/message/ToolResultBlock.java#L132-L230
[result-id]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/message/ToolResultBlock.java#L328-L336
[control]: https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/agent/RunControl.java#L68-L92
