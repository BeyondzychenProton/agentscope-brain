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
  let output = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText
  if (name === 'processTimelineReducer.ts') {
    output = output.replace(/from ['"]\.\/processProtocol['"]/g, "from './processProtocol.mjs'")
  }
  await writeFile(path.join(temp, name.replace(/\.ts$/, '.mjs')), output)
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
