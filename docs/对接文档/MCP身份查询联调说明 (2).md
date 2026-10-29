# MCP 工具联调说明（测试环境）

本文档说明当前 MCP 工具的调用方式。手机号鉴权及新增工具**需要部署对应版本服务端后才生效**；实际已开放的工具以目标环境的 `tools/list` 为准。

## 连接与凭证

| 项目 | 值 |
| --- | --- |
| 测试环境地址 | `https://cms-test.shsets.com/stage-api/mcp` |
| 传输方式 | MCP Streamable HTTP（JSON-RPC 2.0） |
| 协议版本 | `2025-03-26` |
| App Key | `agent` | 测试环境
| Secret | `ea87216c-46dd-4a0f-afae-04df2538c2c0` | 测试环境

线上环境的app key，secret将另外给。服务端不签发 JWT，**每次 HTTP 请求**都要重新生成时间戳和签名。Agent 必须使用其已认证的实际登录人手机号，不能允许用户自行指定其他人的手机号。

## 每次请求的鉴权头

| 请求头 | 内容 |
| --- | --- |
| `X-MCP-App-Key` | 分配的 App Key |
| `X-MCP-Timestamp` | 当前 Unix 时间戳，单位毫秒，十进制字符串 |
| `X-MCP-User-Phone` | 当前登录用户的完整手机号，须对应满天星有效账号 |
| `X-MCP-Signature` | `lowercase_hex(HMAC-SHA256(secret, UTF-8(timestamp + "\n" + userPhone)))` |
| `Content-Type` | POST 请求使用 `application/json` |
| `Accept` | `application/json, text/event-stream` |

签名中的 `\n` 是一个换行字节（LF），不是反斜杠加字母 n。时间戳与服务端时间相差不得超过 5 分钟。`initialize`、`notifications/initialized`、`tools/list`、`tools/call` 等每一次 `/mcp` 请求都需要这些鉴权头。

验签或用户身份失败返回 HTTP `401`；服务端未配置验签凭证返回 HTTP `503`。这类 HTTP 错误不同于工具结果中的业务状态。共享 Secret 加手机号并不能在技术上证明用户本人授权，Agent 侧须保证登录身份和授权流程可信。

## 调用顺序

下面只展示 JSON-RPC 请求体；每一步都需要重新填写上述请求头。

1. 初始化：

   ```json
   {
     "jsonrpc": "2.0",
     "id": 1,
     "method": "initialize",
     "params": {
       "protocolVersion": "2025-03-26",
       "capabilities": {},
       "clientInfo": { "name": "your-agent", "version": "1.0.0" }
     }
   }
   ```

2. 发送无 `id` 的初始化通知：

   ```json
   { "jsonrpc": "2.0", "method": "notifications/initialized" }
   ```

3. 发现工具：

   ```json
   { "jsonrpc": "2.0", "id": 2, "method": "tools/list" }
   ```

4. 查询员工候选人：

   ```json
   {
     "jsonrpc": "2.0",
     "id": 3,
     "method": "tools/call",
     "params": {
       "name": "identity.resolve",
       "arguments": { "name": "张三", "pageNum": 1 }
     }
   }
   ```

响应可能是 `application/json` 或 `text/event-stream`，客户端应按 MCP Streamable HTTP 处理，不要当作普通 REST JSON 接口解析。

## 员工与部门岗位工具

`identity.resolve` 按通讯录口径查询有效员工：可选 `name`（中文姓名片段）、`deptId`（部门及下级部门）、`postId`（岗位 ID），条件可以组合；不传筛选条件时分页浏览员工。`pageNum` 默认 1，每页固定 10 人。姓名是包含匹配，不支持自动识别拼音或错别字。部门筛选使用 `employee_info.dept_id`，岗位筛选使用 `employee_info.post_id`；返回的中文部门按 `sys_user.dept_id` 展示，中文岗位按该用户绑定的岗位展示。工具不要求额外业务权限，但每次请求仍须验签并对应满天星有效登录账号。

工具业务结果由 MCP `tools/call` 响应承载，示例如下：

```json
{
  "resolutionStatus": "RESOLVED",
  "message": "员工身份解析成功",
  "pageNum": 1,
  "pageSize": 10,
  "total": 1,
  "employees": [
    {
      "employeeNumber": "<海卓工号>",
      "name": "张三",
      "department": "示例部门",
      "postName": "示例岗位",
      "phone": "<员工完整手机号>",
      "email": "<员工邮箱>"
    }
  ]
}
```

| `resolutionStatus` | 含义 |
| --- | --- |
| `RESOLVED` | 姓名精确匹配且结果只有 1 人 |
| `CANDIDATES` | 返回候选员工，需进一步确认 |
| `NOT_FOUND` | 没有符合条件的员工 |
| `INVALID_ARGUMENT` | 查询参数不合法 |
| `UNAUTHENTICATED` | 当前 MCP 登录用户身份不可用 |
| `SERVER_ERROR` | 查询暂不可用 |

`employees` 只包含工号、姓名、中文部门、中文岗位、未脱敏手机号和邮箱。手机号可用于后续业务精确选人，不要在无关对话或日志中扩散。即使只有一个模糊匹配结果，Agent 仍应请用户确认。

`department.search` 返回部门与岗位的中文级联树。可选 `keyword` 按名称片段筛选，保留匹配节点的上级路径；不传则返回完整树。节点包含 `deptId`、`name`、`nodeType`、`postId`、`children`。`nodeType` 可能是 `DEPARTMENT`、`POST`、`SUPERVISION` 或 `OTHER`；选部门时把 `deptId` 传给 `identity.resolve`，选岗位时传 `postId`。

```json
{
  "name": "department.search",
  "arguments": { "keyword": "研发" }
}
```

## 当前工具一览

所有工具都使用本次请求头中的登录手机号作为当前用户。工具参数中的其他手机号只用于选取参会人，不能改变发起人身份。时间使用 `yyyy-MM-dd HH:mm:ss`，按满天星服务端时区解释；区间均为左闭右开 `[startTime, endTime)`。

| 工具 | 必需权限 | 输入参数 | 主要输出 |
| --- | --- | --- | --- |
| `identity.resolve` | 无额外业务权限 | 可选 `name`、`deptId`、`postId`、`pageNum` | `resolutionStatus`、`total`、`employees` |
| `department.search` | 无额外业务权限 | 可选 `keyword` | `status`、`departments` |
| `calendar.get_freebusy` | 无额外业务权限 | `phones`、`startTime`、`endTime` | `status`、`busyByPhone` |
| `calendar.find_slots` | 无额外业务权限 | 上述参数及 `durationMinutes` | `status`、`slots` |
| `calendar.verify` | `administration:chamberAppt:query` | `meetingId`、`phones`、`startTime`、`endTime` | `status`、`verified` |
| `room.search` | `administration:chamber:list` | `startTime`、`endTime`、`attendeeCount`；可选 `floor`、`equipment` | `status`、`rooms` |
| `room.reserve` | `administration:chamberAppt:add` | `roomId`、`attendeePhones`、`startTime`、`durationMinutes`、`meetingTheme`、`requestId`；可选 `meetingNote` | `status`、`meetingId` |
| `meeting.list_mine` | 无额外业务权限 | 可选 `startDate`、`endDate`、`pageNum`、`pageSize` | `status`、`total`、`meetings` |
| `meeting.cancel` | `administration:chamberAppt:edit` | `meetingId`、`requestId` | `status`、`meetingId` |

日历两个工具不检查 `tender:schedule:list`，但每次请求仍须通过 MCP 验签和手机号登录校验。工具结果由 MCP `tools/call` 响应承载，以下只展示各工具 `arguments` 和业务结果，不是完整的 HTTP 响应。`FORBIDDEN` 是工具业务结果，HTTP `401` 是请求未通过验签或登录校验。

## 日历工具

`calendar.get_freebusy` 输入最多 20 个登录手机号，时间跨度必须大于 0 且不超过 31 天。它只返回 `tender_schedule` 中有效、未取消且与查询时间重叠的忙区间；未返回的时间仅代表没有有效日程，不保证当事人实际可参会。

```json
{
  "name": "calendar.get_freebusy",
  "arguments": {
    "phones": ["<张三手机号>", "<李四手机号>"],
    "startTime": "2026-10-20 08:30:00",
    "endTime": "2026-10-20 17:00:00"
  }
}
```

结果中的 `busyByPhone` 以输入手机号为键，每个值是 `[{"startTime":"...","endTime":"..."}]`；没有忙区间的员工对应空数组。`status` 正常为 `SUCCESS`，参数、员工数据或日程数据异常时返回明确状态码。

`calendar.find_slots` 在相同人员与查询范围内，额外接收 `durationMinutes`（至少 15 分钟、5 分钟的整数倍）。它只在工作日及 `sys_config` 的 `mcp.calendar.workday.hours` 工作时段内返回**足以容纳会议时长的连续共同空闲区间**；当前配置脚本为 `08:30;17:00`。结果 `slots` 也是 `startTime`、`endTime` 数组，不是逐个离散的开始时间，更不代表会议室已被锁定。

```json
{
  "name": "calendar.find_slots",
  "arguments": {
    "phones": ["<张三手机号>", "<李四手机号>"],
    "startTime": "2026-10-20 08:30:00",
    "endTime": "2026-10-22 17:00:00",
    "durationMinutes": 30
  }
}
```

工作日数据不可用时返回 `WORKDAY_DATA_UNAVAILABLE`；工作时段配置缺失或错误时返回 `CONFIG_ERROR`。工作时段配置脚本见 `sql/mcp_calendar_config.sql`，部署相应环境时需确保配置已存在。该脚本目前尚未转为 Flyway，后续按上线安排处理。

## 会议室查询与预约

`room.search` 查询未来时间内可预约的会议室。`attendeeCount` 为正整数；可选 `floor` 只接受 `16`、`17`、`18`，按会议室编号前两位筛选。`equipment` 可以传入设备名称数组，但**当前不参与过滤**；返回的 `facility` 仅供参考。结果 `rooms` 中包含 `chamberId`、`chamberName`、`chamberNumber`、`place`、`maxPeople`、`facility`。查询结果不是占用锁，预约时会重新检查容量和占用。

```json
{
  "name": "room.search",
  "arguments": {
    "startTime": "2026-10-20 10:00:00",
    "endTime": "2026-10-20 10:30:00",
    "attendeeCount": 3,
    "floor": "17",
    "equipment": ["投影仪"]
  }
}
```

`room.reserve` 是写操作。Agent **必须先取得当前登录用户确认**，不能仅凭手机号推断用户同意。发起人取自 `X-MCP-User-Phone` 对应的登录用户，**不会自动加入参会人**。`attendeePhones` 必须填写完整参会人登录手机号列表（至少 1 人，去重后最多 20 人）；如果发起人也要参会，必须像其他参会人一样显式填写在此列表中。未填写的发起人不占会议室容量，也不参与参会人日程冲突检查。`durationMinutes` 至少 15 分钟且为 5 的整数倍；`meetingTheme` 最多 35 个字符，`meetingNote` 最多 500 个字符。服务端检查参会人有效日程冲突、会议室容量和该时段占用；有冲突时不创建。结束时间由开始时间和时长计算。

```json
{
  "name": "room.reserve",
  "arguments": {
    "roomId": 17,
    "attendeePhones": ["<张三手机号>", "<李四手机号>"],
    "startTime": "2026-10-20 10:00:00",
    "durationMinutes": 30,
    "meetingTheme": "项目讨论",
    "meetingNote": "讨论下一阶段安排",
    "requestId": "reserve-20261020-001"
  }
}
```

成功时返回 `{"status":"CREATED","message":"会议室预约创建成功","meetingId":123}`。`CONFLICT` 表示参会人已有有效日程；会议室被占用、容量不足等返回 `BUSINESS_ERROR`。预约写入会锁定目标会议室，并在同一事务内重新检查占用；`room.search` 的查询结果仍不代表已锁定会议室。

`calendar.verify` 可核验预约结果：传入 `room.reserve` 返回的 `meetingId`、**与预约时 `attendeePhones` 相同的参会人手机号列表**，以及预期起止时间。不要自动加上发起人；只有发起人确实在 `attendeePhones` 中时才包含其手机号。只有未取消、未删除的预约记录，且参会人集合和时间都一致，才返回 `verified: true`、`status: "VERIFIED"`；其他可能是 `NOT_FOUND`、`MISMATCH`、`EMPLOYEE_NOT_FOUND` 或 `DATA_INTEGRITY_ERROR`。

```json
{
  "name": "calendar.verify",
  "arguments": {
    "meetingId": 123,
    "phones": ["<张三手机号>", "<李四手机号>"],
    "startTime": "2026-10-20 10:00:00",
    "endTime": "2026-10-20 10:30:00"
  }
}
```

## 取消与幂等

`meeting.list_mine` 查询当前登录用户**发起或在预约参会名单中**的会议。无论是否提供日期范围，只返回尚未开始、状态有效且未取消的会议；不提供日期范围时查询全部未来会议。可选的 `startDate`、`endDate` 必须同时提供，格式 `yyyy-MM-dd`，按会议开始日期筛选，包含首尾两天。`pageNum` 默认 1，`pageSize` 默认 10、最大 100；结果按开始时间、会议 ID 升序，并返回 `total`。参会人名称取自当前用户资料，同名人员仅供展示；取消会议时使用 `meetingId`。

```json
{
  "name": "meeting.list_mine",
  "arguments": { "pageNum": 1, "pageSize": 10 }
}
```

结果中的每条 `meetings` 包含 `meetingId`、`roomName`、`startTime`、`durationMinutes`、`meetingTheme`、`attendeeNames` 和 `canCancelMeeting`。后者只表示当前登录用户是会议发起人；它不检查业务权限，真正执行 `meeting.cancel` 时仍需具有 `administration:chamberAppt:edit` 权限。如缺少该权限，需先在满天星系统中授权。

`meeting.cancel` 也必须由 Agent 先取得用户确认。只有本次鉴权手机号对应的**会议发起人**、且会议**尚未开始**时，才能取消。成功返回 `status: "CANCELLED"`，`meetingId` 为被取消的预约 ID；非发起人返回 `FORBIDDEN`，已开始或已取消等返回业务失败状态。

```json
{
  "name": "meeting.cancel",
  "arguments": { "meetingId": 123, "requestId": "cancel-20261020-001" }
}
```

两个写工具均要求 `requestId` 为 8～64 位英文字母、数字、下划线或横线。**同一笔操作首次提交和重试必须使用相同 `requestId` 且保持参数不变；新操作必须使用新号。**同一用户、同一工具、同一请求号在 24 小时内由 Redis 做幂等控制：重复请求可返回原结果，处理中返回 `IN_PROGRESS`，同号不同参数返回 `IDEMPOTENCY_CONFLICT`。若长期 `IN_PROGRESS` 或收到“结果无法确认”，Agent 应停止自动重试并交由人工核对，不能换号盲目再次提交。

联调时先执行 `tools/list`。遇到 HTTP `401` 检查 App Key、Secret、手机号、毫秒时间戳及签名；遇到工具 `FORBIDDEN` 检查该登录账号的满天星权限。
