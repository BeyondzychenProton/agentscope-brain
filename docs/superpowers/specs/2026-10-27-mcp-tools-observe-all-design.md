# 会议 MCP 全量工具开放与调用观测（前端无关）

日期：2026-10-27。偏离既有「只读 MCP 工具循环」规格：本阶段写工具对模型可见且可直接执行，不做确认卡。

## 目标

1. 普通 Run 工具循环向模型暴露当前发布内全部会议 MCP 工具（9 个，含 `room.reserve` / `meeting.cancel`）。
2. 每次模型↔工具往返可在应用日志中观测：轮次、工具名、参数摘要、成败、耗时。

## 现状

- `employee_release.config_json.tools` 与 `tool_binding_revision` 已含 9 个工具；catalog 契约齐全。
- `ReadonlyToolCatalog.READ_ONLY` 与 `OrdinaryToolLoop` 执行拦截使 `room.reserve`、`meeting.cancel` 对模型不可用／调用返回 `TOOL_NOT_ALLOWED`。
- 工具循环中间调用不落库、不打日志，失败时只能看到外层 `TOOL_LOOP_EXCEEDED`。

## 模块边界

| 单元 | 职责 | 非职责 |
| --- | --- | --- |
| `ReadonlyToolCatalog`（可改名语义，文件可暂不重命名） | 按发布 `tools` + catalog 契约装载全部 listed 工具；去掉只读过滤 | 动态 `tools/list`、改发布表数据 |
| `OrdinaryToolLoop` | 允许发布内工具执行；结构化 SLF4J 观测 | 确认卡、审计表、UI 时间线 |
| `MeetingMcpClient` | 可选：单次 HTTP 调用耗时／错误码日志 | 改签名或 endpoint |

发布表白名单本切片**不强制改库**（已齐）；若某环境缺项，运维补绑定，不在本切片自动插入。

## 工具可见与执行

- 暴露集合 = 发布 `config_json.tools` 中 `providerKey=meeting-mcp`（或既有约定键）且 catalog 有 `contract/meeting-mcp/{name}/v1` 的工具。
- 固定全集期望：`identity.resolve`、`department.search`、`calendar.find_slots`、`calendar.get_freebusy`、`calendar.verify`、`meeting.list_mine`、`room.search`、`room.reserve`、`meeting.cancel`。
- 执行时：名称须在本 Run 装载的 catalog 内；否则 `TOOL_NOT_ALLOWED`。不再用单独 `READ_ONLY` 集合拦截写工具。
- 本阶段无确认卡门控；模型可直接触发预约／取消。

## 观测日志

每次 tool 执行（及可选每轮模型返回 tool_calls 摘要）打日志，至少含：

- `userId`、`releaseId`、`round`（0-based）
- `toolName`、参数 JSON **截断**（如 ≤512 字符）
- 成功：结果字符串长度、耗时 ms
- 失败：错误码／简短 message、耗时 ms

级别：成功 INFO，失败 WARN。不记录 HMAC secret、完整超长 MCP body。  
优先能关联到当前 `runId`（若 `OrdinaryRunWorker` → `toolLoop.run` 可扩展传参则带上）。

不新建审计表。

## 错误

既有失败码与终态（含 `TOOL_LOOP_EXCEEDED`）不变；观测只增强可诊断性。

## 非目标

确认卡、过程时间线、工具调用入库、改 MCP 地址、提高 `MAX_ROUNDS`（除非验收证明必须）、像素级 Skill 文案改写。

## 验收

1. 装载发布后，模型侧 tools 列表含全部 9 个名称（含 reserve／cancel）。
2. 调用 `room.reserve`／`meeting.cancel` 不再因只读白名单得到 `TOOL_NOT_ALLOWED`（仍受 MCP／参数校验约束）。
3. 一次含多轮工具的 Run，日志中可按 round 看到工具名与成败／耗时。
4. 既有相关单测通过；对「写工具可执行路径」有最小覆盖或回归说明。
