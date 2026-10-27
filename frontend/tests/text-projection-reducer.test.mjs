import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'

const root = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(root, 'target'), { recursive: true })
const temp = await mkdtemp(path.join(root, 'target', 'text-projection-tests-'))
const source = await readFile(path.join(root, 'src/features/workbench/textProjectionReducer.ts'), 'utf8')
const output = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText
await writeFile(path.join(temp, 'textProjectionReducer.mjs'), output)
const {
  emptyTextProjectionState, applyTextBatch, TextBatchError, seedOpenPreviews,
} = await import(pathToFileURL(path.join(temp, 'textProjectionReducer.mjs')))
after(async () => {
  if (!path.resolve(temp).startsWith(path.resolve(root, 'target') + path.sep)) throw new Error('Unexpected cleanup path')
  await rm(temp, { recursive: true, force: true })
})

const base = {
  protocolVersion: 'web-projection-v1',
  sessionId: '1', streamId: 's', occurredAt: 't',
}

test('CONTENT_SET OPEN then TEXT_APPEND grows preview', () => {
  let state = emptyTextProjectionState()
  state = applyTextBatch(state, {
    ...base, seq: '1', previousSeq: '0', cursor: 'c1',
    changes: [{
      type: 'CONTENT_SET', contentId: 'p1', contentVersion: '1', runId: '9',
      status: 'OPEN', body: '你',
    }],
  })
  state = applyTextBatch(state, {
    ...base, seq: '2', previousSeq: '1', cursor: 'c2',
    changes: [{
      type: 'TEXT_APPEND', contentId: 'p1', baseContentVersion: '1', contentVersion: '2', delta: '好',
    }],
  })
  assert.equal(state.previews.p1.body, '你好')
  assert.equal(state.cursor, 'c2')
})

test('MESSAGE_COMMIT removes preview and records messageId', () => {
  let state = emptyTextProjectionState()
  state = applyTextBatch(state, {
    ...base, seq: '1', previousSeq: '0', cursor: 'c1',
    changes: [{ type: 'CONTENT_SET', contentId: 'p1', contentVersion: '1', runId: '9', status: 'OPEN', body: 'hi' }],
  })
  state = applyTextBatch(state, {
    ...base, seq: '2', previousSeq: '1', cursor: 'c2',
    changes: [{
      type: 'MESSAGE_COMMIT', messageId: 'm1', replacesContentIds: ['p1'],
      contents: [], senderKind: 'ASSISTANT',
    }],
  })
  assert.equal(state.previews.p1, undefined)
  assert.deepEqual(state.committedMessageIds, ['m1'])
})

test('CONTENT_CLOSE INTERRUPTED removes preview', () => {
  let state = emptyTextProjectionState()
  state = applyTextBatch(state, {
    ...base, seq: '1', previousSeq: '0', cursor: 'c1',
    changes: [{ type: 'CONTENT_SET', contentId: 'p1', contentVersion: '1', runId: '9', status: 'OPEN', body: 'x' }],
  })
  state = applyTextBatch(state, {
    ...base, seq: '2', previousSeq: '1', cursor: 'c2',
    changes: [{ type: 'CONTENT_CLOSE', contentId: 'p1', closeReason: 'INTERRUPTED' }],
  })
  assert.equal(state.previews.p1, undefined)
})

test('seq gap fails without advancing cursor', () => {
  let state = emptyTextProjectionState()
  state = applyTextBatch(state, {
    ...base, seq: '1', previousSeq: '0', cursor: 'c1',
    changes: [{ type: 'CONTENT_SET', contentId: 'p1', contentVersion: '1', runId: '9', status: 'OPEN', body: 'a' }],
  })
  assert.throws(() => applyTextBatch(state, {
    ...base, seq: '3', previousSeq: '1', cursor: 'c3',
    changes: [{ type: 'TEXT_APPEND', contentId: 'p1', baseContentVersion: '1', contentVersion: '2', delta: 'b' }],
  }), (e) => e instanceof TextBatchError)
  assert.equal(state.cursor, 'c1')
})

test('seedOpenPreviews restores snapshot preview', () => {
  const state = seedOpenPreviews(emptyTextProjectionState(), {
    streamId: 's', watermark: '4', cursor: 'c4',
    openPreviews: [{ contentId: 'p1', contentVersion: '2', runId: '9', body: '你好世界', status: 'OPEN' }],
  })
  assert.equal(state.previews.p1.body, '你好世界')
  assert.equal(state.lastSeq, '4')
})
