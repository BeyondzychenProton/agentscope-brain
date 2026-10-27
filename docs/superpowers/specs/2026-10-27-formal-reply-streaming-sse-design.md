# 正式回复流式投影与只读 SSE（#43）

日期：2026-10-27。依据 [#43](https://github.com/BeyondzychenProton/agentscope-brain/issues/43)、已关闭 [#20](https://github.com/BeyondzychenProton/agentscope-brain/issues/20)／[#21](https://github.com/BeyondzychenProton/agentscope-brain/issues/21)、契约 `docs/正式应用设计/消息渲染运行事件与恢复投影设计规格.md` 与 `后端接口与事务锁序契约.md` §7。不吞并 [#41](https://github.com/BeyondzychenProton/agentscope-brain/issues/41)；本 Issue 完成 ≠ #41 关闭 ≠ 阶段 2 完成。

## 目标

活动 Run 期间，浏览器经**已持久** `web-projection-v1` 批次看到正式回复预览增量；收尾提升为一条正式 ASSISTANT；只读 SSE（`projection/stream`）推送已提交批次，断线后快照 + `after` 补读。

## 做法（方案推荐）

在既有 `AcceptedTextWriter` 收尾路径之上，增加**短事务流式写入**与**只读 SSE／events**；过程协议继续隔离在 `web-projection-process-v1` 轮询，不共用本 Issue 的 SSE。

| 方案 | 要点 | 取舍 |
| --- | --- | --- |
| 1. 仅收尾整段 + SSE 推收尾批 | 实现最短 | 无中途正文，不满足 #43 |
| **2. 模型流式 → 合并持久 TEXT_APPEND + v1 SSE**（选中） | 对齐 #20；读路径零执行 | 需改模型调用与 TextHistory 门禁 |
| 3. 内存推 SSE、不落库 | 感觉快 | 违反只读持久恢复，明确排除 |

## 1. 冻结：模型 delta → 账本与投影

1. **触发面**：`OrdinaryToolLoop` 对 OpenAI 兼容 `chat/completions` 使用 `stream=true`；工具轮与正文轮共用流式解析。
2. **合并预算**（#20 §9）：累计新增 UTF-8 ≥ 4 KiB **或** 距上次提交 ≥ 100 ms 则落库；关键关闭／提升立即落库。单 Run 累计正文上限 256 KiB；超限停止后续生成，保留已提交预览，按安全失败收尾（不把截断当完整正式回复）。
3. **首包**：短事务锁 Session → 分配 `last_projection_seq` → 写 `run_progress`（`TEXT_DELTA` + 投影扩展列）→ `projection_content`（`OPEN`）+ `CONTENT_SET` 批次（`status=OPEN`，`bodyFormat=markdown-v1`）。
4. **续包**：同 contentId，`TEXT_APPEND`（`baseContentVersion` 匹配、`contentVersion=前驱+1`），版本正文为**累计全文** INLINE（便于固定版本读与校验）；同步推进 `run_progress`／头版本。
5. **工具调用打断**：若本轮流式结果含 `tool_calls`，对已打开预览做 `CONTENT_CLOSE`/`INTERRUPTED`（不 `MESSAGE_COMMIT`）；工具结果回灌后可再开新 contentId 预览。工具前说明不提升为正式消息。
6. **收尾提升**（与 `OrdinaryRunFinalize.succeed` 同事务）：若存在该 Run 的 `OPEN` 预览 → `CONTENT_CLOSE`/`PROMOTED`（`replacement_message_id`）+ 既有正式 `CONTENT_SET`/`CLOSED` + `MESSAGE_COMMIT`（`replacesContentIds=[previewContentId]`）+ `RUN_SET SUCCEEDED`；无预览则保持今日整段写入。重复收尾仍靠 `produced_by_run_id` 去重。
7. **失败**：`fail` 路径对仍 `OPEN` 的预览 `CONTENT_CLOSE`/`FAILED`，不产生正式 ASSISTANT。迟到 append：关闭态拒绝（版本／status 门禁 + claim fence 不在 RUNNING 时不写）。
8. **锁序**：模型 I/O **不**持 Session 锁；每次合并写入为短事务子序列（L7 Session → 投影行）。通知可丢，补读以持久日志为准。

## 2. 冻结：SSE 鉴权／游标／Last-Event-ID

| 项 | 约定 |
| --- | --- |
| 路径 | `GET /api/sessions/{sessionId}/projection/stream?protocolVersion=web-projection-v1&after={cursor}` |
| 补读 | 同协议 `GET .../projection/events?protocolVersion=&after=&limit=` → `{batches,lastCursor,headSeq,hasMore}` |
| 应用游标 | **仅**查询参数 `after`（及客户端本地已应用 cursor）；浏览器 `Last-Event-ID` **不得**覆盖或推进应用游标（服务端可忽略该头） |
| 帧 | `event: projection.batch`，`data` 为完整批次 JSON；`id` 可含 stream/seq 定位但不代替 `after` |
| 控制帧 | `caught_up`／`reset_required`／`auth_required`／心跳：**不**分配业务序号、**不**推进应用游标 |
| 查权 | 每次发送批次前及空闲 **15s** 心跳复核登录与本人 Session 归属；401／撤权 → `auth_required` 后关闭 |
| 降级 | 连续 3 次 SSE 失败：退避 1／2／4s 后只读 `events` 轮询；健康轮询每 30s 可再试 SSE；活跃 Run 轮询约 2s，等待／空闲约 10s；连续 5 次读失败暂停自动恢复 |
| 执行计数 | GET／SSE 路径模型／SDK／工具写增量为 **0** |

## 3. 冻结：预览气泡 ↔ `MESSAGE_COMMIT` 前端状态机

| 状态 | 来源 | UI |
| --- | --- | --- |
| 无预览 | 无 OPEN 内容 | 仅正式消息 + RunWatch／过程块 |
| `preview` | `CONTENT_SET`/`TEXT_APPEND` 且 status OPEN | 流式 ASSISTANT 预览气泡（按 contentId）；未 closure 前不算正式消息 |
| `closed-failed` | `CONTENT_CLOSE` FAILED／INTERRUPTED | 移除或降级预览；不进入正式历史 |
| `committed` | `MESSAGE_COMMIT` 且 `replacesContentIds` 含该预览 | 删除预览；插入／校验正式消息；以正式正文为准 |

Reducer 规则（#20／#21）：整批校验成功才应用并前移应用游标；残缺／乱序／基线不符则停在最后已应用 cursor 并补读；未知变更类型失败关闭。刷新：snapshot 含正式消息 + 当前 Run 的 OPEN 预览（或经 watermark 可重建的版本）；再 `events`/`stream` 追赶。

`RunWatch` 保留为有界轮询与降级载体；SSE 健康时由投影订阅驱动正文更新，RunWatch 不再是正文唯一来源。

## 4. 冻结：过程协议是否共用 SSE

**不共用。** `#43` 的 SSE／`events` 仅服务 `web-projection-v1`。`web-projection-process-v1` 继续既有 process snapshot／`after` 轮询（#41）。禁止把 `PROCESS_STEP_*` 写入 v1；禁止单连接混协议游标。

## 5. 模块边界

| 单元 | 职责 | 非职责 |
| --- | --- | --- |
| `CompatibleChatModelCaller` 流式解析 | token／tool_call 增量 | 投影、事务 |
| `StreamingReplyWriter`（或等价） | OPEN／APPEND／CLOSE／与 run_progress 同事务 | 模型 HTTP、正式 message 行 |
| `AcceptedTextWriter` / Finalize | 正式提升与无预览回退路径 | SSE |
| `TextHistory` | snapshot 含 OPEN 预览、`after`／content 读 OPEN INLINE | 写执行 |
| `TextHistoryController` | `/events`、`/stream` | 过程协议 |
| FE `textProjectionReducer` + `projectionSubscription` | 整批应用、预览状态机、SSE→降级轮询 | 伪造动画、过程步 |

## 6. 验收要点（对齐 #43 AC）

- 活动 Run 可见已持久 TEXT_APPEND 预览；刷新水位一致。
- 收尾仅一条正式 ASSISTANT；重复收尾／迟到 delta 不第二答复、不复活预览。
- SSE 只推已提交批次；断线快照+after；读路径执行计数为 0。
- SSE 失败降级轮询可证；恢复可再连。
- 跨用户 404；账号失效停 SSE／补读。
- 明确记录：本 Issue ≠ #41 关闭 ≠ 阶段 2。

## Out of scope

SDK live／未持久 Console 流作恢复源；过程步进 v1；生产 REASONING；FORM／媒体／变更 MCP；定时器假流式。
