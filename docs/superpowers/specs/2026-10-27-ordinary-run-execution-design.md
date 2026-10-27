# 普通文字 Run 执行（#42 第三切片）

日期：2026-10-27。承接 [#42](https://github.com/BeyondzychenProton/agentscope-brain/issues/42) 建议切片之后、过程时间线 [#41](https://github.com/BeyondzychenProton/agentscope-brain/issues/41) 之前。沿用 #21／#22／#23 与 #31 表契约；不重开已关闭设计议题。

## 目标

登录工作台发送普通文字后，服务端领取 `WAITING` 调度、真实调用已配置的兼容 LLM，写出一条正式 `ASSISTANT` 回复并可刷新读回。每 Run 至多一条正式员工回复。

## 范围

包含：本机开启文字接纳前置；C0 领取（≤2 HELD）；`runtime_sdk_call` 账本；文字-only 模型调用；正式收尾与 v1 投影；前端有界轮询；更新 #42 进度说明。

不做：过程时间线／新投影协议、图片／FORM／会议工具、完整 SSE、记忆学习 worker、重启后自动重放已领取调用、宣称阶段 2／#41 完成。

## 数据流

1. 既有 `TextSubmission` 接纳 → Session／USER／Run `ACCEPTED`／dispatch `WAITING`+`NONE`／投影。
2. 短事务领取：C0 → 授权复核 → `CLAIMED`+`HELD`、waiting--／occupied++、Run `RUNNING`、插入 `runtime_sdk_call`（`PREPARED`→`STARTED`）。
3. 事务外：装载正式文字对话与固定发布 Skill 正文 → 调 `brain.resources.model` → 工作上下文摘要保存并独立读回（`store_version_capability=UNAVAILABLE` 时仍要求 SAVED+MATCHED 摘要一致）。
4. 短事务收尾：按 claim_token／fence／call 写 `ASSISTANT` + TEXT 投影（`MESSAGE_COMMIT` + `RUN_SET SUCCEEDED`）→ Run `SUCCEEDED` → call `VERIFIED` → dispatch `COMPLETED`+`RELEASED`。
5. 前端对当前 session 有界轮询 snapshot，直到出现 ASSISTANT 或终态。

失败：模型失败写可观察失败／待核对，不写假回复；收尾不明按原键回查，禁止第二条 ASSISTANT；已 HELD 无静止证明不自动重放。

## 读模型调整

`TextHistory` 在执行中须能返回 `processingStatus=RUNNING`（CLAIMED／RUNNING），不得因出现 `runtime_sdk_call` 而对仍活动的本切片 Run 抛 `PROJECTION_SCOPE_UNAVAILABLE`。终态后 `activeRun` 为空并展示 ASSISTANT 正文。

## 验收

- 单元：领取 CAS、容量、收尾幂等、每 Run 一条 ASSISTANT。
- 隔离：HTTP 接纳 → 领取 → 替身模型闭环。
- 本机：开启开关与 capacity／memory_profile 后真实模型一轮问答；刷新零增量调用。
