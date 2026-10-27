import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'

const root = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(root, 'target'), { recursive: true })
const temp = await mkdtemp(path.join(root, 'target', 'process-history-tests-'))

const source = await readFile(path.join(root, 'src/features/workbench/processHistory.ts'), 'utf8')
const protocolSource = await readFile(path.join(root, 'src/features/workbench/processProtocol.ts'), 'utf8')
await writeFile(path.join(temp, 'processProtocol.mjs'), ts.transpileModule(protocolSource, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText)
let output = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText
output = output
  .replace(/from ['"]\.\/processProtocol['"]/g, "from './processProtocol.mjs'")
  .replace(/from ['"]\.\.\/\.\.\/transport\/http['"]/g, "from './http-stub.mjs'")
  .replace(/from ['"]\.\/history['"]/g, "from './history-stub.mjs'")
await writeFile(path.join(temp, 'http-stub.mjs'), 'export async function read() { throw new Error("unused") }\n')
await writeFile(path.join(temp, 'history-stub.mjs'), "export function basePath(id) { return `/api/sessions/${id}/projection` }\n")
await writeFile(path.join(temp, 'processHistory.mjs'), output)

const { stepsFromSnapshot, processProtocol } = await import(pathToFileURL(path.join(temp, 'processHistory.mjs')))
after(async () => {
  if (!path.resolve(temp).startsWith(path.resolve(root, 'target') + path.sep)) throw new Error('Unexpected cleanup path')
  await rm(temp, { recursive: true, force: true })
})

test('exports process protocol constant', () => {
  assert.equal(processProtocol, 'web-projection-process-v1')
})

test('maps kind/status snapshot fields into ProcessStep shape', () => {
  const steps = stepsFromSnapshot({
    steps: [{
      stepId: 'a',
      runId: '9',
      kind: 'STAGE',
      title: '理解问题',
      summary: 'done',
      status: 'DONE',
      orderAnchor: { seq: '1', index: '0' },
      viewRevision: '2',
      collapsedByDefault: false,
      operationId: null,
      detail: null,
      detailVersion: '1',
      detailRef: { detailVersion: '1', byteLength: '3', sha256: 'abc' },
    }],
  })
  assert.equal(steps.length, 1)
  assert.deepEqual(steps[0], {
    stepId: 'a',
    runId: '9',
    kind: 'STAGE',
    title: '理解问题',
    summary: 'done',
    status: 'DONE',
    orderAnchor: { seq: '1', index: '0' },
    viewRevision: '2',
    collapsedByDefault: false,
    operationId: null,
    detail: null,
    detailVersion: '1',
    detailRef: { detailVersion: '1', byteLength: '3', sha256: 'abc' },
  })
})

test('accepts stepKind/stepStatus aliases from raw rows', () => {
  const steps = stepsFromSnapshot({
    steps: [{
      stepId: 'b',
      runId: '3',
      stepKind: 'TOOL_STEP',
      title: '查表',
      stepStatus: 'RUNNING',
      orderAnchor: { seq: '2', index: '1' },
      viewRevision: '1',
      collapsedByDefault: true,
    }],
  })
  assert.equal(steps[0].kind, 'TOOL_STEP')
  assert.equal(steps[0].status, 'RUNNING')
  assert.equal(steps[0].summary, null)
  assert.equal(steps[0].operationId, null)
  assert.equal(steps[0].detail, null)
  assert.equal(steps[0].detailVersion, null)
  assert.equal(steps[0].detailRef, null)
})

test('skips malformed steps and empty snapshot lists', () => {
  assert.deepEqual(stepsFromSnapshot({}), [])
  assert.deepEqual(stepsFromSnapshot({ steps: null }), [])
  const steps = stepsFromSnapshot({
    steps: [
      { runId: '1', kind: 'STAGE', title: 'x', status: 'DONE', orderAnchor: { seq: '1', index: '0' }, viewRevision: '1' },
      { stepId: 'ok', runId: '1', kind: 'STAGE', title: 'y', status: 'DONE', orderAnchor: { seq: '1', index: '1' }, viewRevision: '1', collapsedByDefault: false },
    ],
  })
  assert.equal(steps.length, 1)
  assert.equal(steps[0].stepId, 'ok')
})
