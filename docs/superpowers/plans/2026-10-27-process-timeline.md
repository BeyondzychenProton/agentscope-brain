# 过程时间线（#41）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.  
> **本仓库提交约定：** 仅在用户明确要求时执行 `git commit`／`git push`；计划中的 Commit 步骤视为「待用户授权后再做」。

**Goal:** 按 `docs/superpowers/specs/2026-10-27-process-timeline-design.md` 交付 B（`ProcessTimeline` + 合成夹具 + reducer）与 A（`web-projection-process-v1` 发射、快照／补读、刷新恢复）；SSE 与真实隔离工具关闭包后置。

**Architecture:** 前端纯 reducer 消费新协议批次；工作台在用户消息与正式回复之间挂独立过程块，有步骤／活动 Run（协议启用）时收起 RunWatch 顶栏。服务端复用 `session_projection_event`，用**独立 process 流**与 `last_process_projection_seq` 隔离协议；步骤落 `projection_process_step`／`projection_process_step_detail`；安全门槛通过后才写入。

**Tech Stack:** Vue 3、既有 `node:test` + TypeScript transpile、Spring／JdbcTemplate、MySQL DDL（`db/schema/`）。

**Spec:** `docs/superpowers/specs/2026-10-27-process-timeline-design.md`

---

## File map

| 路径 | 职责 |
| --- | --- |
| `frontend/src/features/workbench/processProtocol.ts` | 协议常量、步骤／变更类型 |
| `frontend/src/features/workbench/processTimelineReducer.ts` | 整批应用、锚点排序、展开态键 |
| `frontend/src/features/workbench/processTimelineView.ts` | 顶栏是否隐藏、占位文案（纯函数） |
| `frontend/src/features/workbench/ProcessTimeline.vue` | 过程块 UI |
| `frontend/src/features/workbench/fixtures/process-timeline/*.json` | 合成批次矩阵 |
| `frontend/tests/process-timeline-reducer.test.mjs` | reducer／view 单测 |
| `frontend/src/pages/WorkbenchPage.vue` | 挂载过程块、条件隐藏顶栏 |
| `frontend/package.json` / `frontend/README.md` | `test:process-timeline` |
| `db/schema/12_过程时间线投影建表.sql` | process 流头、步骤表、放宽 protocol check |
| `backend/.../process/`（application） | 安全校验、写入、读取 |
| Issue #41 评论 | 进 A 前冻结回写 |

---

## Phase B — 组件与夹具

### Task 1: 协议类型与常量

**Files:**
- Create: `frontend/src/features/workbench/processProtocol.ts`

- [ ] **Step 1: 写入类型与常量**

```ts
export const PROCESS_PROTOCOL = 'web-projection-process-v1' as const
export const V1_PROTOCOL = 'web-projection-v1' as const

export type ProcessStepKind = 'STAGE' | 'TOOL_STEP' | 'REASONING'
export type ProcessStepStatus =
  | 'RUNNING' | 'DONE' | 'FAILED' | 'WAITING' | 'CANCELLED' | 'INTERRUPTED' | 'UNKNOWN'

export type OrderAnchor = { seq: string; index: string }

export type ProcessStep = {
  stepId: string
  runId: string
  kind: ProcessStepKind
  title: string
  summary: string | null
  status: ProcessStepStatus
  orderAnchor: OrderAnchor
  viewRevision: string
  collapsedByDefault: boolean
  operationId: string | null
  detail: string | null
  detailVersion: string | null
  detailRef: { detailVersion: string; byteLength: string; sha256: string } | null
}

export type ProcessBatch = {
  protocolVersion: typeof PROCESS_PROTOCOL
  sessionId: string
  streamId: string
  seq: string
  previousSeq: string
  cursor: string
  occurredAt: string
  changes: ProcessChange[]
}

export type ProcessChange =
  | ({ type: 'PROCESS_STEP_UPSERT' } & Omit<ProcessStep, 'detail' | 'detailVersion' | 'detailRef'> & {
      detail?: string | null
      detailVersion?: string | null
      detailRef?: ProcessStep['detailRef']
    })
  | {
      type: 'PROCESS_STEP_DETAIL_SET'
      stepId: string
      detail?: string
      detailVersion: string
      byteLength: string
      sha256: string
      detailRef?: ProcessStep['detailRef']
    }
```

- [ ] **Step 2: 自检**

Run: `cd frontend && npx tsc --noEmit -p tsconfig.json`（或项目既有 `npm run typecheck`）  
Expected: 无因本文件引入的错误（若暂未被引用，至少文件语法正确）。

- [ ] **Step 3: Commit（仅当用户要求）**

```bash
git add frontend/src/features/workbench/processProtocol.ts
git commit -m "$(cat <<'EOF'
feat: 冻结过程时间线协议前端类型

EOF
)"
```

---

### Task 2: 合成夹具矩阵

**Files:**
- Create: `frontend/src/features/workbench/fixtures/process-timeline/happy-path.json`
- Create: `frontend/src/features/workbench/fixtures/process-timeline/status-matrix.json`
- Create: `frontend/src/features/workbench/fixtures/process-timeline/reasoning-synthetic.json`
- Create: `frontend/src/features/workbench/fixtures/process-timeline/README.md`（一行：合成数据，非运行证据）

- [ ] **Step 1: `happy-path.json`**

按规格 §9 写一批：`STAGE` RUNNING→后续批 UPSERT 为 DONE；`TOOL_STEP` DONE + `PROCESS_STEP_DETAIL_SET`；可选末尾仅作注释说明正式回复仍来自 v1 `MESSAGE_COMMIT`（本文件只含 process 变更）。

最小 `changes` 示例（完整文件须含外层 envelope：`protocolVersion=web-projection-process-v1`，`seq`／`previousSeq` 合法前缀）：

```json
{
  "protocolVersion": "web-projection-process-v1",
  "sessionId": "1001",
  "streamId": "proc-stream-demo",
  "seq": "1",
  "previousSeq": "0",
  "cursor": "demo-cursor-1",
  "occurredAt": "2026-10-27T10:00:00Z",
  "changes": [
    {
      "type": "PROCESS_STEP_UPSERT",
      "stepId": "step-stage-1",
      "runId": "9001",
      "kind": "STAGE",
      "title": "理解请求",
      "summary": null,
      "status": "DONE",
      "orderAnchor": { "seq": "1", "index": "0" },
      "viewRevision": "1",
      "collapsedByDefault": false,
      "operationId": null
    },
    {
      "type": "PROCESS_STEP_UPSERT",
      "stepId": "step-tool-1",
      "runId": "9001",
      "kind": "TOOL_STEP",
      "title": "本地计算",
      "summary": "17 × 23",
      "status": "DONE",
      "orderAnchor": { "seq": "1", "index": "1" },
      "viewRevision": "1",
      "collapsedByDefault": false,
      "operationId": "op-1"
    },
    {
      "type": "PROCESS_STEP_DETAIL_SET",
      "stepId": "step-tool-1",
      "detail": "17 × 23 → 391",
      "detailVersion": "1",
      "byteLength": "18",
      "sha256": "demo-not-a-real-hash"
    }
  ]
}
```

- [ ] **Step 2: `status-matrix.json`**

同一 `runId` 下多步：`WAITING`、`FAILED`、`INTERRUPTED`、`UNKNOWN`、`CANCELLED` 各至少一步（可用多 `seq` 文件数组，或单文件 `batches: ProcessBatch[]`）。推荐单文件导出数组：

```json
[
  { "protocolVersion": "web-projection-process-v1", "seq": "1", "previousSeq": "0", "...": "..." }
]
```

保证相邻批 `previousSeq = 前批 seq`。

- [ ] **Step 3: `reasoning-synthetic.json`**

含 `kind: "REASONING"`、`collapsedByDefault: true`、`title: "深度思考"`；文件头注释或同目录 README 写明合成。

- [ ] **Step 4: Commit（仅当用户要求）**

---

### Task 3: `processTimelineReducer`（TDD）

**Files:**
- Create: `frontend/src/features/workbench/processTimelineReducer.ts`
- Create: `frontend/tests/process-timeline-reducer.test.mjs`
- Modify: `frontend/package.json`（加 script）

- [ ] **Step 1: 写失败测试**

`frontend/tests/process-timeline-reducer.test.mjs`（沿用 run-watch 的 transpile 导入模式）：

```js
import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'

const root = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(root, 'target'), { recursive: true })
const temp = await mkdtemp(path.join(root, 'target', 'process-timeline-tests-'))
for (const name of ['processProtocol.ts', 'processTimelineReducer.ts']) {
  const source = await readFile(path.join(root, 'src/features/workbench', name), 'utf8')
  await writeFile(path.join(temp, name.replace(/\.ts$/, '.mjs')), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText)
}
const {
  emptyProcessState, applyProcessBatch, ProcessBatchError,
} = await import(pathToFileURL(path.join(temp, 'processTimelineReducer.mjs')))
after(async () => {
  if (!path.resolve(temp).startsWith(path.resolve(root, 'target') + path.sep)) throw new Error('Unexpected cleanup path')
  await rm(temp, { recursive: true, force: true })
})

test('rejects wrong protocolVersion without moving cursor', () => {
  const state = emptyProcessState()
  assert.throws(() => applyProcessBatch(state, {
    protocolVersion: 'web-projection-v1',
    sessionId: '1', streamId: 's', seq: '1', previousSeq: '0', cursor: 'c', occurredAt: 't',
    changes: [],
  }), (e) => e instanceof ProcessBatchError)
  assert.equal(state.cursor, null)
})

test('upsert is idempotent and keeps first orderAnchor', () => {
  let state = emptyProcessState()
  const base = {
    protocolVersion: 'web-projection-process-v1',
    sessionId: '1', streamId: 's', occurredAt: 't', changes: [],
  }
  state = applyProcessBatch(state, {
    ...base, seq: '1', previousSeq: '0', cursor: 'c1',
    changes: [{
      type: 'PROCESS_STEP_UPSERT', stepId: 'a', runId: '9', kind: 'STAGE', title: '理解',
      summary: null, status: 'RUNNING', orderAnchor: { seq: '1', index: '0' },
      viewRevision: '1', collapsedByDefault: false, operationId: null,
    }],
  })
  state = applyProcessBatch(state, {
    ...base, seq: '2', previousSeq: '1', cursor: 'c2',
    changes: [{
      type: 'PROCESS_STEP_UPSERT', stepId: 'a', runId: '9', kind: 'STAGE', title: '理解',
      summary: '已完成', status: 'DONE', orderAnchor: { seq: '9', index: '9' },
      viewRevision: '2', collapsedByDefault: false, operationId: null,
    }],
  })
  assert.equal(state.steps[0].status, 'DONE')
  assert.deepEqual(state.steps[0].orderAnchor, { seq: '1', index: '0' })
  assert.equal(state.cursor, 'c2')
})

test('unknown change type fails whole batch', () => {
  const state = emptyProcessState()
  assert.throws(() => applyProcessBatch(state, {
    protocolVersion: 'web-projection-process-v1',
    sessionId: '1', streamId: 's', seq: '1', previousSeq: '0', cursor: 'c', occurredAt: 't',
    changes: [{ type: 'NOT_A_REAL_TYPE' }],
  }), (e) => e instanceof ProcessBatchError)
})

test('seq gap fails and does not advance cursor', () => {
  let state = emptyProcessState()
  state = applyProcessBatch(state, {
    protocolVersion: 'web-projection-process-v1',
    sessionId: '1', streamId: 's', seq: '1', previousSeq: '0', cursor: 'c1', occurredAt: 't',
    changes: [{
      type: 'PROCESS_STEP_UPSERT', stepId: 'a', runId: '9', kind: 'STAGE', title: 't',
      summary: null, status: 'DONE', orderAnchor: { seq: '1', index: '0' },
      viewRevision: '1', collapsedByDefault: false, operationId: null,
    }],
  })
  assert.throws(() => applyProcessBatch(state, {
    protocolVersion: 'web-projection-process-v1',
    sessionId: '1', streamId: 's', seq: '3', previousSeq: '2', cursor: 'c3', occurredAt: 't',
    changes: [],
  }), (e) => e instanceof ProcessBatchError)
  assert.equal(state.cursor, 'c1')
})
```

先创建空的 `processTimelineReducer.ts` 导出占位，使 Step 2 能跑到断言失败（或模块缺导出）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && node --test tests/process-timeline-reducer.test.mjs`  
Expected: FAIL（缺实现或断言失败）。

- [ ] **Step 3: 最小实现**

`processTimelineReducer.ts`：

```ts
import { PROCESS_PROTOCOL, type ProcessBatch, type ProcessStep } from './processProtocol'

export class ProcessBatchError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ProcessBatchError'
  }
}

export type ProcessState = {
  steps: ProcessStep[]
  cursor: string | null
  lastSeq: string | null
  streamId: string | null
  expanded: Record<string, boolean>
}

export function emptyProcessState(): ProcessState {
  return { steps: [], cursor: null, lastSeq: null, streamId: null, expanded: {} }
}

function anchorKey(a: { seq: string; index: string }) {
  return `${BigInt(a.seq)}:${BigInt(a.index)}`
}

export function applyProcessBatch(state: ProcessState, batch: ProcessBatch): ProcessState {
  if (batch.protocolVersion !== PROCESS_PROTOCOL) {
    throw new ProcessBatchError('PROTOCOL_MISMATCH')
  }
  if (batch.changes.some(c => c.type !== 'PROCESS_STEP_UPSERT' && c.type !== 'PROCESS_STEP_DETAIL_SET')) {
    throw new ProcessBatchError('UNKNOWN_CHANGE')
  }
  const expectedPrev = state.lastSeq ?? '0'
  if (batch.previousSeq !== expectedPrev || (state.lastSeq != null && BigInt(batch.seq) !== BigInt(state.lastSeq) + 1n)) {
    throw new ProcessBatchError('SEQ_GAP')
  }
  if (state.streamId != null && state.streamId !== batch.streamId) {
    throw new ProcessBatchError('STREAM_MISMATCH')
  }

  const byId = new Map(state.steps.map(s => [s.stepId, { ...s }]))
  for (const change of batch.changes) {
    if (change.type === 'PROCESS_STEP_UPSERT') {
      const prev = byId.get(change.stepId)
      const next: ProcessStep = {
        stepId: change.stepId,
        runId: change.runId,
        kind: change.kind,
        title: change.title,
        summary: change.summary ?? null,
        status: change.status,
        orderAnchor: prev ? prev.orderAnchor : change.orderAnchor,
        viewRevision: change.viewRevision,
        collapsedByDefault: change.collapsedByDefault,
        operationId: change.operationId ?? null,
        detail: change.detail ?? prev?.detail ?? null,
        detailVersion: change.detailVersion ?? prev?.detailVersion ?? null,
        detailRef: change.detailRef ?? prev?.detailRef ?? null,
      }
      byId.set(change.stepId, next)
    } else {
      const prev = byId.get(change.stepId)
      if (!prev) throw new ProcessBatchError('DETAIL_WITHOUT_STEP')
      byId.set(change.stepId, {
        ...prev,
        detail: change.detail ?? prev.detail,
        detailVersion: change.detailVersion,
        detailRef: change.detailRef ?? prev.detailRef,
      })
    }
  }

  const steps = [...byId.values()].sort((a, b) => {
    const d = BigInt(a.orderAnchor.seq) - BigInt(b.orderAnchor.seq)
    if (d !== 0n) return d < 0n ? -1 : 1
    const i = BigInt(a.orderAnchor.index) - BigInt(b.orderAnchor.index)
    return i < 0n ? -1 : i > 0n ? 1 : 0
  })

  return {
    steps,
    cursor: batch.cursor,
    lastSeq: batch.seq,
    streamId: batch.streamId,
    expanded: state.expanded,
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && node --test tests/process-timeline-reducer.test.mjs`  
Expected: PASS。

- [ ] **Step 5: 注册 script**

`frontend/package.json` scripts 增加：`"test:process-timeline": "node --test tests/process-timeline-reducer.test.mjs"`

- [ ] **Step 6: Commit（仅当用户要求）**

---

### Task 4: 顶栏／占位纯函数

**Files:**
- Create: `frontend/src/features/workbench/processTimelineView.ts`
- Modify: `frontend/tests/process-timeline-reducer.test.mjs`（或新建 `process-timeline-view.test.mjs`，一并挂到 `test:process-timeline`）

- [ ] **Step 1: 失败测试**

```js
test('hides legacy status bar when process timeline owns the run', () => {
  assert.equal(shouldHideRunWatchBanner({
    processProtocolEnabled: true,
    hasProcessSteps: true,
    runActive: true,
  }), true)
  assert.equal(shouldHideRunWatchBanner({
    processProtocolEnabled: true,
    hasProcessSteps: false,
    runActive: true,
  }), true)
  assert.equal(shouldHideRunWatchBanner({
    processProtocolEnabled: false,
    hasProcessSteps: false,
    runActive: true,
  }), false)
})
```

- [ ] **Step 2: 实现**

```ts
export function shouldHideRunWatchBanner(input: {
  processProtocolEnabled: boolean
  hasProcessSteps: boolean
  runActive: boolean
}): boolean {
  return input.processProtocolEnabled && input.runActive
}

export function processPlaceholderNotice(input: {
  hasProcessSteps: boolean
  runWatchNotice: string
  snapshotWaiting: boolean
}): string | null {
  if (input.hasProcessSteps) return null
  if (input.runWatchNotice) return input.runWatchNotice
  if (input.snapshotWaiting) return '执行中…'
  return null
}
```

说明：规格「活动 Run 且协议启用 → 优先过程块占位」；故 `runActive && enabled` 即藏顶栏，占位文案由过程块标题区显示。

- [ ] **Step 3: 测试 PASS + Commit（用户要求时）**

---

### Task 5: `ProcessTimeline.vue`

**Files:**
- Create: `frontend/src/features/workbench/ProcessTimeline.vue`

- [ ] **Step 1: 组件**

Props：`steps: ProcessStep[]`、`synthetic?: boolean`、`placeholder?: string | null`、`runId?: string | null`。

行为：
- `synthetic` 时顶部显示「合成数据演示」。
- 按 `steps` 渲染；`REASONING` 默认折叠（尊重 `expanded` 可用 `v-model` 式本地 `Set` 或 props `expanded` + emit）。
- `TOOL_STEP`／`STAGE` 显示 title、status、summary；有 `detail` 可展开。
- 无 steps 且有 `placeholder` 时显示占位一行。
- 详情用 `<pre class="process-detail">` 纯文本，禁止 `v-html`。

样式：贴合现有 workbench 紫系变量；圆角块、左边步骤轨即可，勿新造整页皮肤。

- [ ] **Step 2: 本机目视**

临时在 `WorkbenchPage` 导入夹具跑通后，在 Task 6 正式挂载；本步可先 Story 式：在页面加 `?processFixture=1` 查询开关（仅 dev 心态，生产勿默认开）——实现放 Task 6。

- [ ] **Step 3: Commit（用户要求时）**

---

### Task 6: 工作台挂载

**Files:**
- Modify: `frontend/src/pages/WorkbenchPage.vue`
- Modify: `frontend/README.md`（一句：过程时间线 B 合成预览／`test:process-timeline`）

- [ ] **Step 1: 状态**

```ts
import ProcessTimeline from '../features/workbench/ProcessTimeline.vue'
import { applyProcessBatch, emptyProcessState } from '../features/workbench/processTimelineReducer'
import { shouldHideRunWatchBanner, processPlaceholderNotice } from '../features/workbench/processTimelineView'
import { PROCESS_PROTOCOL } from '../features/workbench/processProtocol'
import happy from '../features/workbench/fixtures/process-timeline/happy-path.json'

const processState = reactive(emptyProcessState())
const processProtocolEnabled = ref(false) // A 接通前：仅 fixture 查询开启

// on load / route:
const wantFixture = route.query.processFixture === '1'
if (wantFixture) {
  processProtocolEnabled.value = true
  Object.assign(processState, applyProcessBatch(emptyProcessState(), happy as any))
}
```

- [ ] **Step 2: 模板**

在 `.chat` 内：
1. 用 `shouldHideRunWatchBanner` 包裹原 `runWatch.notice` 顶栏：`v-if="!hideBanner && (runWatch.notice || snapshotRunActive)"`。
2. 在消息列表中：对「当前活动 run 相关」——B 阶段简化为：在**全部正式消息之后**、composer 之前插入一个过程块（因当前页尚未把消息与 run 交错建模）。注释标明：A 阶段改为按 run 插到对应用户消息之后。

```vue
<p v-if="!hideRunWatchBanner && (runWatch.notice || snapshotRunActive)" class="readonly-note" role="status">
  {{ runWatch.notice || (snapshotRunActive ? '消息已接纳，等待执行。' : '') }}
</p>
<!-- messages ... -->
<ProcessTimeline
  v-if="processProtocolEnabled && (runActive || processState.steps.length || wantFixture)"
  :steps="processState.steps"
  :synthetic="wantFixture"
  :placeholder="processPlaceholderNotice({
    hasProcessSteps: processState.steps.length > 0,
    runWatchNotice: runWatch.notice,
    snapshotWaiting: snapshotRunActive,
  })"
/>
```

- [ ] **Step 3: 手工**

1. `npm run dev`，打开 `/workbench?processFixture=1`，见合成过程块与「合成数据演示」。  
2. 无 query 时行为与改前一致（顶栏仍在）。  

- [ ] **Step 4: Commit（用户要求时）**

---

## Gate — 回写 #41

### Task 7: Issue 冻结评论

**Files:** 无仓库文件；`gh issue comment 41`

- [ ] **Step 1: 确认 B 测试绿**

Run: `cd frontend && npm run test:process-timeline`  
Expected: PASS。

- [ ] **Step 2: 写评论文件并发表**

UTF-8 文件（例 `.codex-local/issue-41-freeze.md`）内容要点：
- 采用规格路径与协议名 `web-projection-process-v1`
- 变更类型、字段、状态枚举、8KiB／脱敏门槛
- 持久：独立 process 流 + `projection_process_step`／`detail` 表建议名
- 本会话 A 停线：刷新／补读；SSE／关闭包后置
- B 已交付夹具／组件；**不**关闭 #41

```bash
gh issue comment 41 --repo BeyondzychenProton/agentscope-brain --body-file .codex-local/issue-41-freeze.md
```

- [ ] **Step 3: 回读评论；标签是否改 `ready-for-agent` 问用户后再改**

---

## Phase A — 投影与恢复

### Task 8: DDL

**Files:**
- Create: `db/schema/12_过程时间线投影建表.sql`

要点（完整 SQL 写入文件时保持与 `10_` 风格一致：无 FK、UTC、注释）：

1. `business_session` 增加 `process_projection_stream_id BINARY(16) NULL`、`last_process_projection_seq BIGINT UNSIGNED NOT NULL DEFAULT 0`。  
2. `ALTER` 去掉／重建 `session_projection_event.chk_projection_event_protocol`，允许 `'web-projection-v1','web-projection-process-v1'`。  
3. `CREATE TABLE projection_process_step`（step_id、owner、session、run、kind、status、title、summary、first_seq、first_position、view_revision、operation_id、collapsed_default、…）。  
4. `CREATE TABLE projection_process_step_detail`（step_id、detail_version、body、byte_count、sha256、…）。  

- [ ] **Step 1: 编写 SQL 并在空库／旁路库执行验证**（按项目既有 schema 验收习惯）。  
- [ ] **Step 2: Commit（用户要求时）**

---

### Task 9: 安全门槛（TDD）

**Files:**
- Create: `backend/src/main/java/com/agentscope/brain/projection/internal/application/ProcessStepSecurity.java`
- Create: `backend/src/test/java/com/agentscope/brain/projection/internal/application/ProcessStepSecurityTest.java`

- [ ] **Step 1: 失败测试**

```java
@Test void rejectsInlineDetailOver8KiB() {
  byte[] body = new byte[8 * 1024 + 1];
  Arrays.fill(body, (byte) 'a');
  assertThrows(ProcessStepSecurity.Rejected.class,
      () -> ProcessStepSecurity.validateInlineDetail(body));
}

@Test void rejectsSensitiveKeys() {
  assertThrows(ProcessStepSecurity.Rejected.class,
      () -> ProcessStepSecurity.rejectSensitiveKeys(Map.of("confirmationToken", "x")));
}
```

- [ ] **Step 2: 实现** — `MAX_INLINE = 8192`；敏感键集合含 `confirmationToken`、`systemPrompt`、`rawThinking` 等；字符串内容简单脱敏钩子（token 形似替换为占位）。  
- [ ] **Step 3: `mvn test -Dtest=ProcessStepSecurityTest` PASS**  
- [ ] **Step 4: Commit（用户要求时）**

---

### Task 10: 过程步写入

**Files:**
- Create: `ProcessStepWriter.java`（`Propagation.MANDATORY`，对齐 `AcceptedTextWriter`）
- Test: 写入后读 `session_projection_event` where `protocol_version='web-projection-process-v1'`

行为：
- 分配／复用 `process_projection_stream_id`；`last_process_projection_seq+1`  
- UPSERT 步骤行（首次锚点不可变）  
- 序列化 batch JSON（`protocolVersion=web-projection-process-v1`）写入 `session_projection_event`  
- 调用 `ProcessStepSecurity`；失败抛错回滚  

从现有普通执行收尾／工具成功路径调用（只读 MCP 工具步）：在工具账本提交成功的同一事务附写 `TOOL_STEP`；Run 阶段变化写 `STAGE`。若当前代码路径尚无稳定阶段枚举，先映射 `WAITING`→`STAGE`「排队」、`RUNNING`→「执行中」、结束→`DONE`／`FAILED`。

- [ ] **Step 1–4: 测试 → 实现 → 绿 → Commit（用户要求时）**

---

### Task 11: 读取 API

**Files:**
- Extend or add controller beside `TextHistoryController`：  
  - `GET .../projection/process/snapshot?protocolVersion=web-projection-process-v1`  
  - `GET .../projection/process/after?cursor=&protocolVersion=`  
  - 详情：`GET .../projection/process/steps/{stepId}/detail?detailVersion=`
- 鉴权：本人 Session；跨用户 404／403 与 v1 一致。  
- **禁止**在这些 GET 中调模型／SDK／工具写。

`TextHistory` 现有「有 `run_progress` 则 503」逻辑：**不要**让 process 表触发 v1 503；process 读走新入口。

- [ ] **Step 1–4: 控制器测试或切片测试 → 实现 → 绿 → Commit（用户要求时）**

---

### Task 12: 前端接真读 + 刷新恢复

**Files:**
- Modify: `frontend/src/features/workbench/history.ts` 或新建 `processHistory.ts`
- Modify: `WorkbenchPage.vue` / `runWatch` reload 回调

- [ ] **Step 1: `fetchProcessSnapshot` / `fetchProcessAfter`**

```ts
export const processProtocol = 'web-projection-process-v1'
export async function processSnapshot(sessionId: string) {
  return read<ProcessSnapshot>(`${basePath(sessionId)}/process/snapshot?protocolVersion=${processProtocol}`)
}
```

- [ ] **Step 2: 打开会话／RunWatch.reload 成功后：若 `processProtocolEnabled`（配置或服务端能力位；本会话可用「process 快照 200 即启用」），`applyProcessBatch` 追平。  
- [ ] **Step 3: 手工：发一条普通任务产生 STAGE／TOOL_STEP → 刷新页面 → 步骤仍在；Network 无多余模型调用。  
- [ ] **Step 4: Commit（用户要求时）**

---

### Task 13: 验收记录（不关 #41）

**Files:**
- Create: `verification/issue-41/验收记录.md`（脱敏）

记录：构建指纹、B 测试、A 读回、刷新恢复、后置项（SSE、隔离工具关闭包、生产 REASONING）。

- [ ] **Step 1: 写记录**  
- [ ] **Step 2: 可选 `gh issue comment` 进度（不 close）**

---

## Spec coverage self-check

| 规格项 | 任务 |
| --- | --- |
| 新协议名与隔离 | T1、T8、T10–12 |
| 字段／状态／8KiB／脱敏 | T1–2、T9 |
| 独立过程块 + 藏顶栏 | T4–6 |
| 合成 REASONING + 状态矩阵 | T2、T5–6 |
| B 测试 | T3–4 |
| #41 回写 | T7 |
| 发射 + 刷新／补读 | T10–12 |
| SSE／关闭包后置 | T13 记录 |
| 不进 v1／不生产 REASONING | T8 check、T10 映射 |

## Placeholder scan

无 TBD／「类似 Task N」；Commit 均标注须用户授权。

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-10-27-process-timeline.md`.

两种执行方式：

1. **Subagent-Driven（推荐）** — 每任务新开子代理，任务间复审  
2. **Inline Execution** — 本会话按任务推进，设检查点  

选哪一种？  
（另外：是否现在就把规格＋计划 commit 到 `codex/formal-web-implementation`？默认仍不提交。）
