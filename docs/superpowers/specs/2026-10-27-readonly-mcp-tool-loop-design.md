# 只读 MCP 工具循环（#42 续）

日期：2026-10-27。承接普通文字领取／模型回复之后；消除假 `<tool_call>` 正文。

## 目标

普通 Run 执行时向兼容模型暴露发布内**只读**会议 MCP 工具；原生 `tool_calls` → 平台 HMAC 调 MCP → 结果回灌 → 最终纯文本正式 ASSISTANT。写工具不对模型可见。

## 只读白名单

`identity.resolve`、`department.search`、`calendar.find_slots`、`calendar.get_freebusy`、`calendar.verify`、`meeting.list_mine`、`room.search`。

不可见：`room.reserve`、`meeting.cancel`。

## 数据流

1. 领取后装 Skill 正文 + 白名单工具契约（发布 `tools` + `tool_binding_revision`／catalog schema）。
2. 兼容聊天请求带 `tools`；解析 `choices[].message.tool_calls`。
3. 每项：白名单校验 → 入参 JSON 校验（契约 schema）→ MCP Streamable HTTP `tools/call`（`X-MCP-*`，手机号=当前可信登录号）。
4. 将 `role=tool` 结果回灌，最多 6 轮；最终无 tool_calls 的文本再 `MESSAGE_COMMIT`。
5. 超轮次／MCP／校验失败：可观察失败或受控说明，不把原始 tool 标签当正式回复。

## 非范围

确认卡、写工具执行、过程时间线、整段替换为 AgentScope ReAct。
