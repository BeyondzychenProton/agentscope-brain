# 业务 Session、Run 与 AgentScope 状态持久化学习笔记

> 学习目标：判断一次对话或会议写操作中断后，哪些事实已经成立，下一轮能否安全继续。  
> 范围：本项目一期 Web 会议室数字员工；AgentScope Java 以 `v2.0.4` 为核对基线。本文整理已确认的设计和静态源码结论，不表示应用集成与故障恢复已经验收。业务规则以[会话设计规格](../../数据库设计/会话运行与消息/业务会话运行消息与跨渠道续传设计规格.md)和[建表规格](../../数据库设计/会话运行与消息/一期会话运行消息建表规格.md)为准。

## 1. 先记住一件事：一次对话有多份不同的记录

用户说“帮我预订明天下午三点的会议室”。系统需要同时回答三个问题：**用户原话是什么？Agent 下次从哪里接着思考？会议室究竟订上没有？** 这三个答案不在同一份记录中。

| 记录 | 谁负责 | 它回答什么 | 它不能代替什么 |
| --- | --- | --- | --- |
| 业务 `Session` | 平台 | 这段对话属于谁、哪个数字员工及发布版本，当前能否接收新 Run；同时保存受控的框架状态槽 `state_session_key`。 | Web 登录会话或 AgentScope 状态表。 |
| 正式业务 `Message` 与图片原件 | 平台 | 用户实际提交了什么、员工完整回答了什么；页面和严格全量版本续接主要读取这里。 | 流式片段、工具日志或被压缩的模型上下文。 |
| 业务 `Run` 与过程记录 | 平台 | 本轮输入是否正在执行、等待确认、待核对或已经完整结束；中断的部分回复只属于过程。 | 会议预约的最终业务结果。 |
| `AgentState` | AgentScope 框架状态存储 | Agent 下次调用需要恢复的当前工作上下文。 | 平台完整的原始消息历史；上下文可能被压缩。 |
| Session Transcript | AgentScope Harness 的日志机制 | 模型消息、工具使用和结果等执行经过。 | 正式业务消息和完整图片原件。 |
| 会议写操作记录与会议系统 | 平台记录操作意图及结果；会议系统判定预约事实 | 哪个 `requestId`、哪个操作者、哪些参数曾被确认并发送，预约是否真正生效。 | Agent 自述或 Run 的成功状态。 |

长期 `Memory` 更适合偏好，不作为预约编号、状态或完整聊天记录的权威来源。上述框架存储边界见[状态、记忆与工作区调研](AgentScope状态记忆工作区持久化.md)和[SQL 多模态能力调研](AgentScope%20SQL多模态消息持久化能力.md)。

## 2. 顺着一次预订看三个持久化边界

### 边界一：接纳用户输入

服务端从已认证登录状态取得可信 `UserId`，检查目标业务 Session 的所有者、授权及可运行状态。首次输入在**一笔平台业务事务**中创建 `business_session`、正式用户 `business_message`（包括有序内容块和图片原件）与 `business_run`；已有 Session 则在一笔事务中创建新消息和 Run。事务提交后才能调用模型。即使之后模型失败，已经接纳的用户 Message 也不能删除。浏览器同一次提交的重发返回原消息和 Run；用户主动重试失败轮次时沿用原用户 Message，创建新的 Run。[设计草案 §4.1](../../数据库设计/会话运行与消息/一期会话运行消息数据库设计草案.md#41-接纳输入与重试)

平台从业务 Session 取 `state_session_key`，作为框架的逻辑 `sessionId`；从可信身份记录取 `UserId`，作为框架 `userId`。这两个值都不能由前端自由指定。当前建表规格针对 AgentScope `v2.0.4` MySQL 状态实现说明了键映射：框架内部再组合物理 `session_id` 并以 `state_key=agent_state` 读取当前状态。`agentscope_sessions` 保存的是框架状态，**不是**平台的 `business_session` 或逐条正式消息表。[建表规格：状态槽映射](../../数据库设计/会话运行与消息/一期会话运行消息建表规格.md)、[官方状态存储接口](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/state/AgentStateStore.java)

### 边界二：确认并发送会议写操作

Agent 提出 `room.reserve` 后，平台先保存规范化预订参数、参数摘要、固定 `requestId`、操作者与操作提案，再向用户展示具体参数并等待确认。Run 此时处于 `WAITING_CONFIRMATION`；用户的确认仍归属于**原 Run**。确认时重新核对当前登录人、号码、授权和参数，消费确认与操作转为待发送状态在同一平台事务完成，然后才调用会议系统。[设计草案 §4.4](../../数据库设计/会话运行与消息/一期会话运行消息数据库设计草案.md#44-确认会议操作及管理员查看)

这里的 `requestId` 是操作身份，不是魔法锁。只有会议系统按它防重或提供可靠的按号查询，平台才能在故障后安全地判断一次写入是否已发生。参数也要与该身份固定绑定；不能换参数却声称仍是同一次经用户确认的操作。

### 边界三：结束本轮 Run

模型生成的完整回复先是候选结果。只有**AgentScope 状态可恢复**，并且**完整员工回复已成为正式业务 Message**，Run 才能标记 `SUCCEEDED`。例如会议室已被占用，Agent 完整告知用户且状态和消息均保存成功，Run 仍可为 `SUCCEEDED`：预订失败是一个明确的业务结果，不等于对话处理失败。相反，预订成功但 Agent 回复中断或状态未保存，Run 不能成功。[设计草案 §4.2](../../数据库设计/会话运行与消息/一期会话运行消息数据库设计草案.md#42-run-完成取消与重启)

AgentScope 状态存储与平台业务表即使位于同一个 MySQL 实例，也不能预设为同一个事务。当前方案使用 Run 状态、候选回复和状态版本等信息处理两侧提交错开的窗口；**候选结果保存顺序、状态保存回调与版本能力仍需固定版本的应用集成验收**。Transcript 写入是另一条框架日志路径，不是正式 Message 或 Run 成功的替代凭据。[官方 JDBC 状态存储源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-extensions/agentscope-extensions-jdbc/src/main/java/io/agentscope/extensions/jdbc/state/JdbcAgentStateStore.java)、[建表规格：完成 Run](../../数据库设计/会话运行与消息/一期会话运行消息建表规格.md)

## 3. 为什么结果不明时不能简单重跑

考虑平台已经保存 `requestId=K1` 与预订参数，然后进程崩溃。它可能发生在请求发送**之前**，也可能发生在会议系统预订成功、平台记录成功**之前**。重启后，本地可能看到相同的“待发送或已发送但无最终结果”记录，却对应两种不同的外部事实。

| 平台此时的选择 | 如果实际尚未发送 | 如果会议系统已经执行 |
| --- | --- | --- |
| 用新 `requestId` 再调用 | 可能补上预订 | 可能造成第二笔预订 |
| 一律不再发送 | 可能留下未完成的用户请求 | 避免平台自动造成第二次调用 |
| 按原 `requestId` 向会议系统核对 | 取决于上游是否能给出权威结论 | 可确认已有结果，避免把成功误作失败 |

因此：**本地保存 `requestId` 不能单独保证外部“恰好执行一次”**。若上游既不防重也无法可靠查询，平台能停止自动重发并保留待核对状态，却不能同时保证自动完成和绝不重复。查询无记录是否足以证明未执行，也必须按上游实际一致性契约判断，不能自行推定。

本项目规定：写入结果不明时，操作保持 `UNKNOWN`，Run 进入 `RECONCILING`，暂停该 Session 的新普通 Run；使用原 `requestId` 和原参数核对，不换 ID 自动重试，也不自动重放整个 Agent。Redis 即使缓存了“处理中”标记，也不知道会议系统是否已实际预订，不能解除这个阻断。页面仍可查看正式历史和待核对状态。[设计草案 §4.2、§4.4](../../数据库设计/会话运行与消息/一期会话运行消息数据库设计草案.md)

## 4. 两侧保存错开时怎样判断

| 已知事实 | Run 和 Session 的处理方向 |
| --- | --- |
| 用户 Message 已提交，模型在任何会议写入前失败，框架状态可恢复。 | 保留用户 Message；Run 可记明确失败并释放占用，用户可选择新输入或显式重试原消息。 |
| `room.reserve` 确认成功，但 AgentState 或正式员工 Message 未保存。 | 预约事实按会议系统记录；Run 不能成功，Session 暂停，先恢复本轮状态/结果；不得再调用一次 `room.reserve`。 |
| AgentState 已保存，正式员工 Message 提交失败。 | 框架可能记得回复，但页面没有正式答复；Run 仍未完成，应利用可靠候选结果和版本记录核对或修复，不能直接开新 Run 或盲目重新生成。 |
| 会议系统明确返回“会议室已被占用”，Agent 完整答复，状态与正式消息均保存。 | Run 可为 `SUCCEEDED`；会议预订没有成功，但本轮对话已正确完成。 |
| 外部写入是否发生无法确认。 | 操作保持 `UNKNOWN`，Run 待核对，Session 阻止新普通 Run；不能把未知改写成失败。 |

这个表描述**应达到的业务行为**，不是声称当前程序已经具备自动修复能力。状态缺失时能否安全重建，取决于保存下来的候选结果、会议操作记录及实际框架集成；无法证明一致性时应继续暂停并人工处理。[设计草案：恢复约束](../../数据库设计/会话运行与消息/一期会话运行消息数据库设计草案.md#4-事务与恢复约束)

## 5. Transcript 为什么不能替代业务 Message

AgentScope Harness 的 Transcript 由框架中间件和写入器产生，记录执行过程；它可以帮助排查“Agent 做过什么”。其逻辑形式是 Workspace 内的会话日志（通常为 JSONL），具体是否共享、落在什么后端，取决于 Workspace/TranscriptStore 的配置；**只配置 JDBC/MySQL `AgentStateStore` 并不等于同时把 Transcript 配成持久共享存储**。[官方 Transcript 写入器](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/session/SessionTranscriptWriter.java)、[官方 Workspace 文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/workspace.md)

Transcript 的条目由原始 `Msg` 派生，不承诺保留文字与图片的全部原始交错顺序、图片原件或完整工具输入输出；AgentState 的当前 `context` 还可能因压缩而替换早期消息。故页面历史、原始图片读取及跨版本严格全量续接以平台正式 Message/媒体记录为准，Transcript 用于运行排查。[官方 Transcript 转换源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/session/SessionTranscriptWriter.java#L193-L249)、[官方压缩实现](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/CompactionMiddleware.java#L217-L229)

## 6. 最后压缩成五条判断

1. **Session 定归属与可运行性；Message 定正式原话和完整回复；Run 定一轮处理是否完成。**
2. **AgentState 定框架能否续聊；Transcript 说明过程；会议系统定预约是否真正生效。**
3. **已接纳的用户 Message 不因模型失败而删除；没有完整正式回复和可恢复状态，Run 不成功。**
4. **外部写入未知时先核对，阻断本 Session 的新普通 Run；Redis 缓存不改变外部事实。**
5. **业务动作的成功或失败与 Run 的成功或失败分开判断。**

当前已有建表 SQL 的隔离 MySQL 结构检查，但本笔记涉及的状态往返、事务错开故障、重启核对、Transcript 后端与会议系统幂等/查询契约仍需应用和外部系统联调验证。参见[建表规格的验收状态](../../数据库设计/会话运行与消息/一期会话运行消息建表规格.md)。
