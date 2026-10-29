# 会议助手平台事实工具：当前时间与当前用户

日期：2026-10-29。为会议助手补两个**平台内置**只读工具：当前日期时间、当前登录用户信息。

## 目标

模型在推理“今天/明天/下周三”以及“我自己/我的会议”时，不再依赖模型自身时钟或凭空猜测身份。两个工具由后端进程内实现，不经会议 MCP，不写入发布配置，对所有发布版本常驻可用。

## 工具定义

| 注册名 | 目录名 | 入参 | 说明 |
| --- | --- | --- | --- |
| `platform_current_time` | `platform.current_time` | 无 | 获取平台当前日期与时间 |
| `platform_current_user` | `platform.current_user` | 无 | 获取当前登录用户身份信息 |

两者的参数 schema 固定为 `{"type":"object","properties":{},"additionalProperties":false}`，避免模型猜测字段。

## 返回结构

`platform.current_time`：

```json
{"iso":"2026-10-29T14:03:00+08:00","local":"2026-10-29 14:03:00",
 "weekday":"星期四","timezone":"Asia/Shanghai","epochMillis":1793000000000}
```

时区取配置 `brain.tools.timezone`，缺省 `Asia/Shanghai`；时间源为注入的 `Clock`，便于测试固定。

`platform.current_user`：

```json
{"userId":"1024","displayName":"张三","maskedPhone":"***8000"}
```

字段来源：`platform_user.display_name` 与 `phone_reservation` 当前预留号码。号码按现有 `CurrentPlatformUser` 规则打码为 `***`+后四位；无预留号码时省略 `maskedPhone`，任何情况下都不返回完整号码。

## 接入点

1. `AgentToolCatalog`：新增 `LOCAL_READ_ONLY` 名称集合与 `localTools()` 元数据；与 `READ_ONLY`（MCP 只读）、`MUTATING`（写）区分。
2. `AgentTools.forRun`：先注册两个本地工具，再按发布 `tools` 清单注册 MCP 工具。发布工具清单为空时返回仅含本地工具的 toolkit（现状返回空 toolkit）。本地工具不依赖手机号；仅在确有 MCP 工具时解析手机号并保留 `MCP_PHONE_UNAVAILABLE`。
3. `OrdinaryToolLoop`：工具清单 = 本地工具 + 发布只读工具；`platform.*` 分派到本地实现，其余仍走 MCP；`TOOL_NOT_ALLOWED` 判定扩展为 `READ_ONLY ∪ LOCAL_READ_ONLY`。

## 新增组件

`PlatformFacts`（`runtime/internal/integration`）：`currentTime()` 与 `currentUser(userId)` 返回 JSON 字符串；前者只依赖 `Clock`/`ZoneId`，后者查库。工具包装、错误映射与日志留在 `AgentTools`/`OrdinaryToolLoop`，与现有 MCP 工具一致。

## 错误处理

- 失败统一返回 `ToolResultBlock.error("PLATFORM_FACT_UNAVAILABLE")`（普通 Run 为 `{"error":"PLATFORM_FACT_UNAVAILABLE"}`），并 `warn` 记录工具名与用户标识，不记录手机号。
- 用户不存在或没有唯一用户行时按失败处理，不返回半成品身份。

## 测试

- `PlatformFactsTest`：固定 `Clock` 验证 `iso`/`local`/`weekday`/`timezone`/`epochMillis`；切换到其它时区；用户字段与打码；无预留号码时省略 `maskedPhone`；用户不存在时抛错。
- `AgentToolsTest`：发布工具清单为空时仍注册两个本地工具，且不触发手机号查询；本地工具可执行成功。

## 非范围

MCP 契约与发布数据变更、写操作确认、过程时间线、跨时区用户偏好。
