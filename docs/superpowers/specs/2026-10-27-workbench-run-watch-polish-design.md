# 工作台 RunWatch 与 Composer 门控（前端切片 1）

日期：2026-10-27。承接 #42 普通执行本机贯通之后；不宣称 #41／阶段 2 完成。

## 目标

发送并接纳后输入区保持可见；活动 Run 期间可编辑草稿但不可发送；有界只读轮询抽成 `RunWatch`，为后续过程时间线留同一状态挂载点。

## 根因

接纳成功后草稿清空；活动 Run 时快照无 `START_RUN`，原 composer `v-if` 在「无 available 且无正文」时卸挂。页面级 `busy` 曾绑入 `canSend`，轮询读历史会误锁发送。

## 模块边界

| 单元 | 职责 | 非职责 |
| --- | --- | --- |
| `features/workbench/runWatch.ts` | 按 runId 有界轮询、续跑、状态文案 | 发消息、草稿、SSE、时间线步骤 |
| `TextComposer` | 常驻输入；活动 Run 可编辑、禁发 | 排队、打断、伪造 START_RUN |
| `WorkbenchPage` | 组合 start／resume／stop；`busy` 仅表示身份／历史加载 | 内联轮询定时器 |

Composer 在已登录且已选员工时挂载。消息流顶部保留单一状态条，供后续 `ProcessTimeline` 叠放；本切片不实现时间线。

## 状态机

相位：`idle`｜`waiting`｜`running`｜`failed`｜`endedEmpty`｜`stalled`｜`readError`。

- `start(sessionId, runId)`：接纳后开始监视。
- `resume(observe)`：打开／刷新时若存在 WAITING／RUNNING 则续跑。
- `stop()`：切会话、清页、卸载。
- 轮询约 1.5s→5s、最多约 60 次；只读 snapshot，不置页面 `busy`。
- `endedEmpty`：目标 run 从 `runs` 消失后，本轮监视开始后未见新增 ASSISTANT（不以会话内任意旧 ASSISTANT 判成功）。

## 发送门控

```
canSend = 能力允许发送
  AND 无活动 Run（快照无 WAITING／RUNNING，且 RunWatch 非 waiting／running）
  AND 非 composer pending／busy
```

不做打断、排队、多 Run。后端同 Session 单活动 Run 语义不变。

## 错误

401／需改密：停监视并走既有清页。快照失败：`readError`，不自动重发提交。模型失败：既有 `MODEL_*`／欠费文案；composer 保持可见。

## 非目标

打断、排队、SSE、#41 时间线、会话改名、Markdown 渲染、修改后端 `allowedActions` 语义。

## 验收

1. 接纳后输入区不消失；等待时可打草稿、不能发送。
2. 打开仍有 WAITING／RUNNING 的会话自动续监视。
3. 轮询刷新不因页面 `busy` 误锁发送（活动 Run 除外）。
4. `RunWatch` 独立模块与纯逻辑单测；页面无内联轮询定时器。
