# 过程时间线（#41）：契约先行 → B 组件夹具 → A 投影与恢复

日期：2026-10-27。依据 [#41](https://github.com/BeyondzychenProton/agentscope-brain/issues/41)、PR #40 基线提交 `08f3b0623b9b007a65df17f978d0384362e2fa97` 的[过程时间线与 WorkBuddy 式展示设计修订](https://github.com/BeyondzychenProton/agentscope-brain/blob/08f3b0623b9b007a65df17f978d0384362e2fa97/docs/正式应用设计/过程时间线与WorkBuddy式展示设计修订.md)，以及已关闭 #20／#21 契约。不宣称 #41 或阶段 2 完成。

## 目标

聊天流内呈现可折叠的执行过程（阶段、工具步骤、有界详情），事实来自已持久账本与投影；本会话按 **B→A** 推进，停在「刷新／补读可恢复」。合成组件预览不是完成证据。

## 做法（方案 2）

契约先行的同一份规格 → 实现 B → 进 A 前回写 #41 → 实现 A（SSE 可降级后置）。

## 1. 架构与协议边界

四层不变：`AgentEvent`（仅服务端）→ 业务账本 → 投影 → 前端 reducer。浏览器不读 SDK live、AG-UI 原流或 2.0.4 `.log.jsonl`。

| 协议 | 规则 |
| --- | --- |
| `web-projection-v1` | 批次枚举不变；**禁止**写入 `PROCESS_STEP_*`；**禁止**当作可忽略 `extensions` |
| `web-projection-process-v1` | **本规格冻结的新协议名**；承载过程时间线变更；流／序号／游标与 v1 **隔离**，不混读、不混游标 |
| 变更类型 | `PROCESS_STEP_UPSERT`、`PROCESS_STEP_DETAIL_SET`；正式回复仍用既有 `MESSAGE_COMMIT` |
| 批次语义 | 整批校验与应用；未知类型失败关闭；游标不前移（同 #20） |

2.0.4 下 `reasoningDisplay=native_from_log` 不可生效，**不生产发射** `REASONING`。夹具可含合成 `REASONING` 仅供布局，须标明合成数据。

## 2. 步骤模型、状态与安全

### 2.1 `PROCESS_STEP` 字段

| 字段 | 约定 |
| --- | --- |
| `stepId` | 服务端稳定 ID；同事实重放幂等，不生成第二步 |
| `runId` | 归属 Run |
| `kind` | `STAGE` \| `TOOL_STEP` \| `REASONING`（生产 2.0.4 只发前两种） |
| `title` / `summary` | 受控中文短文案 |
| `status` | 见 §2.2 |
| `orderAnchor` | `{ seq, index }`：首次出现批次序号与批内下标（十进制字符串序号）；更新不改首次位置 |
| `viewRevision` | 展示修订 |
| `collapsedByDefault` | `REASONING` 默认折叠；`RUNNING` 的 `TOOL_STEP` 可默认展开 |
| `detail` / `detailRef` | 有界内联或固定版本引用 |
| `operationId` | `TOOL_STEP` 与工具账本关联（可空于纯 STAGE） |

### 2.2 状态语义

`RUNNING`｜`DONE`｜`FAILED`｜`WAITING`｜`CANCELLED`｜`INTERRUPTED`｜`UNKNOWN`。

- 不得因枚举不足伪造 `DONE`／成功。  
- `UNKNOWN` 不得显示成「未执行」。  
- 工具真实成功而模型答复失败：工具步与 Run／正式回复分别保留。  
- SDK／工具结束 ≠ 业务成功；正式成功仍看账本与 `MESSAGE_COMMIT`／回执。

### 2.3 详情与安全（开放真实投影前置）

- 内联 `detail` UTF-8 **≤ 8 KiB**；超出仅 `detailRef`（固定版本、长度、SHA-256）；截断须明示。  
- 批次预算沿用 #20（约 1 MiB／128 项等）。  
- 投影前脱敏：确认 token、系统提示、未完成工具参数、密钥、原始请求／响应、raw thinking 不得进入投影。  
- 敏感键检查失败 → 失败关闭；禁止先写原文再靠前端隐藏。  
- 详情为纯文本或已批准安全渲染；禁止执行命令或 HTML。

未通过本节门槛前，不向任何客户端发射 `PROCESS_STEP_*`。

## 3. B：前端模块与挂载

| 单元 | 职责 | 非职责 |
| --- | --- | --- |
| `processTimelineReducer` | 整批应用 `web-projection-process-v1`；按锚点维护步骤；展开态键 | HTTP、轮询、伪造步骤 |
| `ProcessTimeline.vue` | 独立过程块：总览、步骤行、有界详情、状态文案 | 生成合成数据、鉴权 |
| 合成夹具 + 预览 | 无秘密固定 JSON；标明「合成数据演示」 | 真实 Session／启动 Agent |
| `RunWatch` | 有界只读轮询 | 有过程块接管时不再渲染顶栏 |
| `WorkbenchPage` | 用户消息与正式回复之间插入过程块 | 假动画、旁路开时间线 |

**挂载：** 用户气泡 → 过程块 → 正式回复。  
**顶栏：** 时间线协议已启用且存在活动 Run 时，优先过程块（无步骤则占位「执行中…」等）；一旦有可展示步骤，只留过程块。RunWatch 仅轮询。未启用时间线的兼容路径可仍用旧顶栏。

**B 夹具矩阵：** 正常 UPSERT／更新、详情、`WAITING`、`FAILED`、`INTERRUPTED`、`UNKNOWN`、与 `MESSAGE_COMMIT` 并存、合成 `REASONING` 折叠行。

## 4. A：投影、持久与恢复

**配置默认（会议主路径）：** `reasoningDisplay=stages_only`，`toolStepDetail=summary`。浏览器无独立旁路开关。

| 层 | 做法 |
| --- | --- |
| 批次 | 复用 `session_projection_event`；按 `protocolVersion` 隔离 |
| 步骤事实 | 新增表建议名 `projection_process_step` + `projection_process_step_detail`（实现时可微调，语义不变）：归属 Session／Run／用户、锚点、不可变修订、`operationId`；不只覆盖最新文本 |
| 写入 | 自已提交 Run／工具账本归一化；与业务变更同事务一致；提交后才可读／推送 |
| 读取 | 新协议快照（含有界 `processLimit`）+ `after(cursor)` 补读；由 RunWatch 轮询触发补读 |
| 本会话恢复 | 刷新／重连后同 Run 的顺序、状态、详情版本与正式回复关系正确；读路径执行增量为 0 |
| SSE | 本会话可后置；以轮询降级满足补读 |

**错误：** 乱序／缺口／残缺／错版本／坏引用整批失败；显示失败不删除或回滚外部工具权威结果；跨用户不可读；v1 误收新类型按 #20 失败关闭。

**本会话 A 停线：** 发射 + 工作台渲染 + 刷新／补读恢复。后置：完整 SSE、跨用户／撤权全套证据、真实隔离工具关闭包、生产 `REASONING`。

## 5. 节奏与交付

```
规格审阅 → 实现 B → 回写 #41 冻结结果 → 实现 A（停在刷新／补读恢复）
```

1. 本规格（B 期间可小步修订；进 A 前 Issue 回写以冻结版为准）。  
2. B：reducer、组件、夹具、测试、工作台挂载。  
3. #41 评论：协议名、字段、持久映射、配置与安全门槛。  
4. A：写入读回、刷新恢复、脱敏用例；阻塞项写明。

## 6. 验收（本会话）

**B**

1. 合成预览呈现 WorkBuddy 式过程块（含折叠 REASONING）与状态矩阵。  
2. 有步骤时无旧 RunWatch 顶栏并存；更新不改首次顺序、不丢展开态键。  
3. reducer 对未知类型／缺口整批失败；夹具测试通过。  
4. 预览标明合成；不写入完成 #41。

**A**

1. 安全门槛用例通过后才发射 `PROCESS_STEP_*`。  
2. 至少一条路径：账本事实 → `web-projection-process-v1` 批次 → 过程块。  
3. 刷新后同 Run 步骤与详情版本可对上；补读不增加模型／工具写计数。  
4. v1 读路径不受新类型污染。

## 7. 非目标

- 宣称 #41／阶段 2 完成，或以合成预览代替真实 Run 证据  
- `PROCESS_STEP_*` 进入 v1 批次或 `extensions`  
- 2.0.4 启用 `native_from_log`／生产 `REASONING`／`.log.jsonl` 拆思维  
- 完整 SSE 主恢复路径、每场景 Adapter、FORM／确认、图片 MinIO、变更类 MCP  
- 未请求时的 commit／push；编辑已关闭 #42  

## 8. 与既有规格关系

- 不推翻 #20：先入库再推送、读路径不调模型／SDK、整批 reducer、token 不进投影。  
- 修订点仅限过程展示与新协议；默认会议路径仍为阶段／工具摘要，WorkBuddy 推理为后续 SDK 门槛后的配置项。  
- PR #40 仍可能未合入；实现以本规格 + #41 回写为执行冻结，合入后核对无漂移。

## 9. 最小批次样例（夹具形状）

完整矩阵放测试夹具文件；下列为形状冻结（非运行证据、可含合成 REASONING）：

```json
{
  "protocolVersion": "web-projection-process-v1",
  "sessionId": "1001",
  "streamId": "proc-stream-1",
  "seq": "3",
  "previousSeq": "2",
  "cursor": "opaque-cursor-3",
  "occurredAt": "2026-10-27T10:00:00Z",
  "changes": [
    {
      "type": "PROCESS_STEP_UPSERT",
      "stepId": "step-tool-1",
      "runId": "9001",
      "kind": "TOOL_STEP",
      "title": "本地计算",
      "summary": "17 × 23",
      "status": "DONE",
      "orderAnchor": { "seq": "2", "index": "1" },
      "viewRevision": "3",
      "collapsedByDefault": false,
      "operationId": "op-1"
    },
    {
      "type": "PROCESS_STEP_DETAIL_SET",
      "stepId": "step-tool-1",
      "detail": "17 × 23 → 391",
      "detailVersion": "1",
      "byteLength": 18,
      "sha256": "demo-not-a-real-hash"
    }
  ]
}
```

`REASONING`／`WAITING`／`FAILED`／`UNKNOWN`／`MESSAGE_COMMIT` 并存样例在 B 夹具中补齐，字段不得偏离 §2。
