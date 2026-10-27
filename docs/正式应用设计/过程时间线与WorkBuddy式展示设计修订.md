# 过程时间线与 WorkBuddy 式展示设计修订

> 日期：2026-10-27，Asia/Shanghai。  
> 状态：设计修订提案（对话确认方向）。**尚未**替代 #20／#21 已关闭决议中的默认展示策略，也**尚未**实现。本文描述在保持 `web-projection-v1` 与四层架构前提下，将过程卡升级为可配置的交错时间线（WorkBuddy 式效果）。  
> 关联：[#20 消息渲染、运行事件与恢复投影设计规格](消息渲染运行事件与恢复投影设计规格.md)、[#21 工作台交互与前端工程设计规格](工作台交互与前端工程设计规格.md)、[正式 Web 应用设计入口](README.md)、[AgentScope 运行与交互契约核验](../调研/正式Web应用/AgentScope运行与交互契约核验.md)、Harness [Session Log](https://java.agentscope.io/v2/en/docs/harness/session-log)。

## 1. 动机与结论

产品侧希望过程区呈现 **WorkBuddy 式交错时间线**：可折叠「深度思考」、带时钟图标的工具步骤、可展开的有界工具详情，最后才是正式回复气泡。

**结论：**

- **传输与恢复模型不变**：`AgentEvent`（live）→ SessionLog（`SessionEvent`／`session.log`，框架通用）→ 业务账本（业务表与工具／确认账本，产品）→ `web-projection-v1`（薄映射）→ 前端 reducer。
- **浏览器仍只认投影**；不从 SDK live、开源 QwenPaw Console SSE 或 AG-UI 原流作为恢复源。
- **变更点在过程展示策略与投影形状**：过程卡从「仅受控阶段标签」扩展为「可配置过程时间线」（受控阶段／工具步／可选原生推理）。
- **不是**一场景一个 Adapter；配置挂在员工发布，映射挂在 `runtime` 通用管线。

参考交互（目标交互示意，非运行证据，亦非本仓库实现证据）：深度思考可折 → 工具步骤列表 → 展开后显示命令与有界输出 → 正式回复。

![目标交互示意（非运行证据）](assets/workbuddy-timeline-ref.png)

上图为目标交互示意（非运行证据）。它只说明期望的交错布局，不证明本仓库已经实现该时间线，也不替代已关闭的 #20／#21 默认展示策略。

## 2. 四层职责（复习）

四层架构表述保持为：AgentEvent → SessionLog → 业务账本 → `web-projection-v1`。下表按层复习职责；浏览器侧 reducer 只消费最后一层。

| 层 | 职责 | 思维／过程相关 |
| --- | --- | --- |
| `AgentEvent` live | 执行侧实时信号；仅服务端订阅 | 可含 `THINKING_BLOCK_*`；**不直推浏览器** |
| SessionLog（`session.log`／SessionEvent） | 框架已提交 journal；snapshot + cursor | 可持久 reasoning；**不等于**对用户公开 |
| 业务账本 | Session／Run、正式消息、工具操作、确认、素材 | **不**将思维原文写入正式消息或确认载荷 |
| `web-projection-v1` | 浏览器唯一展示真相 | 按配置映射过程时间线项；默认可仅阶段 |

## 3. 过程时间线投影形状

在现有 `changes[]` 上增加（名称以实现契约冻结为准，语义如下）：

| 变更 | 含义 | UI |
| --- | --- | --- |
| `PROCESS_STEP_UPSERT` | 按 `stepId` 幂等写入／更新时间线一步 | 一行思考或一行工具／阶段 |
| `PROCESS_STEP_DETAIL_SET` | 为某步设置可展开详情（有界） | 展开区：命令、输出摘要 |
| 既有 `TEXT_*`／`MESSAGE_COMMIT` | 预览与正式正文 | 最终气泡；推理**不**升格为正式员工消息 |
| 既有 `RUN_SET`／`TOOL_SET`／`INTERACTION_SET` | Run 态、工具账本、交互壳 | 与时间线并存；账本权威仍在业务表 |

### 3.1 `PROCESS_STEP` 字段（建议）

- `stepId`、`runId`、投影 `seq` 锚点
- `kind`：`REASONING` | `TOOL_STEP` | `STAGE`
- `title`：短中文（如「深度思考」「读取 AGENTS.md」「正在理解预约需求」）
- `status`：`RUNNING` | `DONE` | `FAILED`
- `summary`：可选一行摘要
- `detail` 或 `detailRef`：有界详情（命令、截断后的输出）；**禁止**确认 token、系统提示、未完成工具参数、原始密钥
- `collapsedByDefault`：reasoning 默认折叠；当前 `RUNNING` 工具步可默认展开

### 3.2 映射规则

1. Session Log 中已提交的 reasoning → `kind=REASONING`（**仅当**员工配置 `reasoningDisplay=native_from_log`）。
2. 工具完整提案／执行／结果 → `kind=TOOL_STEP`（标题用产品文案；详情有界）。
3. 关闭原生推理且需要进度时 → `kind=STAGE`（一期会议默认路径，对齐原「受控阶段」）。
4. `THINKING_*` live **禁止**直接进入投影；只消费已提交 log 或已落盘工具事实。
5. 合并：reasoning／碎正文可沿用约 100 ms 或新增 4 KiB；新工具步、状态变为 DONE／FAILED、交互等待等**立即**落盘投影。
6. `AGENT_END`／SDK 完成 **不等于**业务成功；正式成功仍靠平台收尾与 `MESSAGE_COMMIT`／真实回执。

## 4. 员工配置

```text
reasoningDisplay: off | stages_only | native_from_log
toolStepDetail: summary | summary_and_bounded_output
```

建议默认：

| 员工类型 | `reasoningDisplay` | `toolStepDetail` |
| --- | --- | --- |
| 会议数字员工（一期主路径） | `stages_only` | `summary` |
| Coding／调研类（WorkBuddy 目标） | `native_from_log` | `summary_and_bounded_output` |

浏览器无独立旁路开关；只渲染投影中已有的过程项。

## 5. 前端

- 组件：`ProcessTimeline`，挂在对应 Run 的过程区。
- 按应用游标后的有序 `PROCESS_STEP` 列表渲染；保留用户展开状态；阶段／步骤更新不抢焦点。
- 总览可显示「深度思考中」类摘要；点步展开 detail。
- 正式区仅预览与 `MESSAGE_COMMIT`；与确认卡、表单底部区并存规则同 #21。
- SSE 失败时降级轮询；时间线与会话投影共用应用游标。

## 6. 实现切片

| 切片 | 内容 | 验收 |
| --- | --- | --- |
| P0 | 契约字段 + 前端时间线组件（可用合成数据） | 布局达到 WorkBuddy 交错效果 |
| P1 | 工具事实 → `TOOL_STEP`；会议默认 `STAGE` | 约会议室可见步骤，无 Thinking 原文 |
| P2 | Session Log reasoning → `REASONING`（配置打开） | Coding／调研员工出现交错深度思考 |
| P3 | detail 有界与脱敏巡检；与确认／`MESSAGE_COMMIT` 并存 | 刷新不丢；token 永不进投影 |

**明确不做：** 每业务场景一个事件 Adapter；以 AG-UI／QwenPaw Console 为恢复源；将整段 Thinking 写入正式员工消息。

## 7. 与已关闭规格的关系

- **不推翻** #20：先入库再 SSE、读路径不调模型／SDK、整批 reducer、确认 token 不进投影。
- **修订** #20／#21 过程卡一节：默认仍可为 `stages_only`；WorkBuddy 为配置开启的时间线形态。
- 字段名与 JSON 示例须先与已关闭的 #20／#21 对齐并冻结，再进入实现；冻结前默认展示策略不变。
- 实现归属后续正式工程议题（原 #22／#23 范围）；本文仅设计修订，不构成已实现声明。

## 8. 修订检查清单（合入正式规格前）

- [ ] 与 #20 `changes[]` 枚举及 JSON 示例对齐并冻结字段名
- [ ] 与 `run_progress`／`structured_payload`／`source_kind` 白名单对齐（是否复用或新增过程项表）
- [ ] 巡检：拒绝投影与 payload 中的 rawThinking、confirmationToken、systemPrompt 等键
- [ ] 原型：将合成过程卡升级为时间线交互说明（非运行证据）
- [ ] 员工发布配置项写入配置／发布规格
