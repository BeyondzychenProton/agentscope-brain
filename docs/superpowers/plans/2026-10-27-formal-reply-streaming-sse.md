# 正式回复流式 + 只读 SSE（#43）实施计划

> **For agentic workers:** 按任务逐步实现；每步 TDD（能纯测的先红后绿）；规格见 `docs/superpowers/specs/2026-10-27-formal-reply-streaming-sse-design.md`。

**Goal:** 活动 Run 持久 `TEXT_APPEND` 预览 + `projection/events`／`stream` 只读 SSE；收尾 `MESSAGE_COMMIT` 提升；FE 预览状态机与降级轮询。

**Architecture:** 模型流式 → 短事务 `StreamingReplyWriter` → `session_projection_event`（v1）→ TextHistory 读／SSE；Finalize 提升预览；FE subscription + reducer。过程协议不共用 SSE。

**Tech Stack:** Java/Spring、JdbcTemplate、H2 单测、Vue/TS、EventSource、既有 HistoryCursor。

---

### Task 1: StreamingReplyWriter（OPEN／APPEND／CLOSE）

**Files:**
- Create: `backend/.../projection/api/StreamingReplyProjection.java`
- Create: `backend/.../projection/internal/application/StreamingReplyWriter.java`
- Test: `backend/.../StreamingReplyWriterTest.java`

**Steps:** 红→绿：首包 CONTENT_SET OPEN；续包 TEXT_APPEND 版本链；CLOSE PROMOTED／FAILED／INTERRUPTED；关闭后 append 拒绝；256KiB 上限。

### Task 2: 模型流式解析

**Files:**
- Modify: `CompatibleChatModelCaller.java`
- Test: 单元解析 SSE data 行（可用固定字符串夹具）

**Steps:** `stream(messages, tools, consumer)` 累积 content／tool_calls；合并回调。

### Task 3: ToolLoop 接入流式持久

**Files:**
- Modify: `OrdinaryToolLoop.java`、`OrdinaryRunWorker.java`（仅传 lease 上下文若需要；**勿**覆盖无关脏改意图之外的逻辑时注意合并）
- Modify: `OrdinaryRunFinalize.java` 提升 OPEN 预览

**Steps:** 流式 delta → writer；tool_calls → INTERRUPTED；succeed 提升；fail CLOSE FAILED。

### Task 4: TextHistory 读路径

**Files:**
- Modify: `TextHistory.java`、`TextHistoryController.java`
- Test: `TextHistoryStreamingTest.java`（或扩展）

**Steps:** 去掉／收窄「有 run_progress 则 503」；snapshot 含 OPEN 预览；`content` 可读 OPEN INLINE；新增 `after`／`/events`；`/stream` SSE（忽略 Last-Event-ID 覆盖）。

### Task 5: 前端 reducer + 订阅

**Files:**
- Create: `frontend/src/features/workbench/textProjectionReducer.ts`
- Create: `frontend/src/features/workbench/projectionSubscription.ts`
- Test: vitest reducer／降级计数
- Modify: `WorkbenchPage.vue`、`history.ts` 类型

**Steps:** 预览状态机；EventSource；3 次失败→events 轮询；与 RunWatch／过程块共存。

### Task 6: 验证记录

**Files:**
- Create: `verification/issue-43/验收记录.md`

**Steps:** 单测命令与结果；注明读路径零执行；本 Issue ≠ #41。

---

## 明确不做

- 不改 #41 关闭包；不把 PROCESS_STEP 写入 v1
- 不提交／强推除非用户要求
- 不碰无关 OrdinaryToolLoop 脏改以外的噪声文件（AGENTS.md、verification/issue-30…）
