# `office.*` MCP 工具契约草案

> ⚠️ 状态：**草稿，需与 MCP 网关（R-002）确认后生效**。
> 本文档把「需求清单里已定名的工具」与「v1.0.0 Skill 文档里出现的工具名」做了对齐，凡标注「建议新增」的，均为本 Skill 为满足完整流程而提出的命名建议，不是已确认的上线工具。

## 1. 工具名对齐表（必读）

`meeting-booking.md` v1.0.0 与《海卓大脑-会议预订-需求清单》使用了两套命名，**以需求清单的 `office.*` 为准**：

| v1.0.0 文档中的旧名 | 本 Skill 采用名 | 依据 | 状态 |
| --- | --- | --- | --- |
| `identity.resolve` | `office.attendee_resolve` | 需求清单 R-103 | ✅ 已定名，待出参确认 |
| `calendar.get_freebusy` | `office.calendar_freebusy` | 需求清单 R-104 | ✅ 已定名，待出参确认 |
| `room.search` | `office.room_recommend` | 需求清单 R-105 | ✅ 已定名，待出参确认 |
| `calendar.find_slots` | `office.calendar_find_slots` | 需求清单未列 | ⚠️ 建议新增；若无此工具，则由本 Skill 基于 `freebusy` 结果本地求交集 |
| `room.reserve` | `office.room_book` | 需求清单 D2「预订 / 取消接口」 | ⚠️ 建议定名 |
| `calendar.verify` | `office.booking_verify` | v1.0.0 第 5.8 节 | ⚠️ 建议定名 |
| — | `office.room_cancel` | 需求清单 D2 | ⚠️ 建议新增（取消流程必需） |
| `ask_clarifying_question` | **不使用** | — | ❌ 改用宿主内置的结构化提问能力，不占用 MCP 工具槽位 |

## 2. 通用约定

- 命名空间：`office.*`，全部经 MCP 网关调用，携带统一身份（R-001 换发的 `user_id` + 部门 + 职级）。
- 幂等：所有写工具接受 `idempotency_key`（客户端生成 UUID，重试时复用同一值），服务端保证同一 key 只生效一次。
- 审计：每个响应回传 `trace_id`，贯穿路由 → 编排 → 子能力 → MCP（R-004）。
- 分页：参会人超 15 人时 `calendar_freebusy` 必须分批（R-104）。
- 权限：写操作受网关鉴权，越权返回 `PERMISSION_DENIED` 并进审批流。

## 3. 工具清单

### 3.1 `office.attendee_resolve`（读）

解析姓名 / 部门 → 员工身份。

| 项 | 内容 |
| --- | --- |
| 入参 | `names: string[]`、`department?: string`、`trace_id?: string` |
| 出参 | `results: [{ input, status: "unique" \| "ambiguous" \| "not_found", candidates?: [{ user_id, name, department, title }] }]` |
| 错误 | `AUTH_EXPIRED`、`RATE_LIMIT`、`UPSTREAM_ERROR` |
| 调用方责任 | `ambiguous` 必须让用户点选；`not_found` 必须告知，不得猜测同音字 |

### 3.2 `office.calendar_freebusy`（读）

| 项 | 内容 |
| --- | --- |
| 入参 | `user_ids: string[]`（≤15/批）、`start: ISO8601`、`end: ISO8601`、`granularity_minutes?: number`（默认 15） |
| 出参 | `busy: [{ user_id, intervals: [{ start, end }] }]` —— **仅区间，不含主题 / 地点 / 参会人** |
| 错误 | `PARTIAL_FAILURE`（部分人查询失败，需明确告知覆盖范围） |
| 红线 | 出参若带上日程详情，本 Skill 也不得向用户转述，只答忙/闲 |

### 3.3 `office.calendar_find_slots`（读，建议新增）

| 项 | 内容 |
| --- | --- |
| 入参 | `user_ids`、`date_range: { from, to }`、`duration_minutes`、`working_hours?: { start: "09:00", end: "18:00" }`、`exclude?: [{ start, end }]`（午休 11:30–13:00） |
| 出参 | `slots: [{ start, end, all_available: boolean, unavailable_user_ids: string[] }]` |
| 备注 | 无此工具时，本 Skill 用 3.2 的结果本地求交集，效果等价 |

### 3.4 `office.room_recommend`（读）

| 项 | 内容 |
| --- | --- |
| 入参 | `start`、`end`、`attendee_count`、`equipment?: string[]`、`floor?: string[]` |
| 出参 | `rooms: [{ room_id, name, floor, capacity, equipment: string[], available: boolean }]` |
| 红线 | `equipment` 以本返回值唯一为准，不得用 `room-directory.md` 推断（该表设备字段尚未提供） |

### 3.5 `office.room_book`（写）

| 项 | 内容 |
| --- | --- |
| 入参 | `room_id`、`subject`、`start`、`duration_minutes`、`attendee_user_ids[]`、`description?`、`equipment?`、`idempotency_key`、`confirmation_id`（确认卡 ID，证明已获用户确认） |
| 出参 | `booking_id`、`status: "booked" \| "pending_approval"`、`room_hold_expires_at` |
| 错误 | `ROOM_TAKEN`（换备选）、`CONFLICT`（参会人冲突）、`PERMISSION_DENIED`、`QUOTA_EXCEEDED`（>20 人） |
| 前置 | 必须先有确认卡 + 用户明确肯定答复；网关侧建议校验 `confirmation_id` |

### 3.6 `office.room_cancel`（写，建议新增）

| 项 | 内容 |
| --- | --- |
| 入参 | `booking_id`、`reason?`、`idempotency_key` |
| 出参 | `status: "cancelled"`，或 `pending_approval`（取消他人会议） |
| 红线 | 他人创建的会议一律走审批，不得直接取消 |

### 3.7 `office.booking_verify`（读）

| 项 | 内容 |
| --- | --- |
| 入参 | `booking_id` |
| 出参 | `calendar_event_exists: boolean`、`room_occupied: boolean`、`attendee_conflicts: [{ user_id, conflict_with }]` |
| 调用方责任 | 三项全绿才回报「预订成功」；任一为否，如实说明，不粉饰 |

## 4. 统一错误码

| 错误码 | 含义 | 本 Skill 的应对 |
| --- | --- | --- |
| `AUTH_EXPIRED` | 登录态失效 | 提示重新登录，不重试 |
| `PERMISSION_DENIED` | 越权 | 转人工审批，不重试 |
| `ROOM_TAKEN` | 会议室被占 | 推荐备选或让用户选 |
| `CONFLICT` | 参会人冲突 | 走 Step 5 情况 B |
| `ATTENDEE_AMBIGUOUS` | 人名多匹配 | 列候选让用户点选 |
| `QUOTA_EXCEEDED` | 超 20 人 | 提示拆分会议 |
| `RATE_LIMIT` | 限流 | 退避重试 1 次 |
| `UPSTREAM_ERROR` | 上游异常 | 重试 1 次（仅幂等读），仍失败转人工 |

## 5. 待确认项（联调前必须敲定）

1. 除 R-103/104/105 外，其余工具是否已在 MCP 网关注册，最终命名是否为本表的 `office.*`。
2. `calendar_freebusy` 出参是否会带日程详情 —— 若带，需在网关侧脱敏。
3. 写工具是否支持 `idempotency_key`；不支持时，重试前必须先查状态防重复创建。
4. 取消/改期他人会议的人工审批回调机制。
5. 周期会议（RRule）接口形态 —— 首期已明确不支持。
