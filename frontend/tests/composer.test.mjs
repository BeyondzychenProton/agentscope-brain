import { after, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'

const root = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(root, 'target'), { recursive: true })
const temp = await mkdtemp(path.join(root, 'target', 'composer-tests-'))
for (const [file, name] of [['src/transport/http.ts', 'http.mjs'], ['src/features/auth/tabRecovery.ts', 'tabRecovery.mjs'], ['src/features/workbench/composer.ts', 'composer.mjs']]) {
  const source = await readFile(path.join(root, file), 'utf8')
  await writeFile(path.join(temp, name), ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
  } }).outputText.replace('../../transport/http', './http.mjs').replace('../auth/tabRecovery', './tabRecovery.mjs'))
}
const { TextComposerState } = await import(pathToFileURL(path.join(temp, 'composer.mjs')))
const oldFetch = globalThis.fetch
const oldStorage = globalThis.sessionStorage
let storage
beforeEach(() => {
  storage = new Map()
  globalThis.sessionStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) }
})
after(async () => {
  globalThis.fetch = oldFetch; globalThis.sessionStorage = oldStorage
  if (!path.resolve(temp).startsWith(path.resolve(root, 'target') + path.sep)) throw new Error('Unexpected cleanup path')
  await rm(temp, { recursive: true, force: true })
})
const scope = { userId: '1', employeeId: '1', sessionId: null }
const accepted = key => ({ submissionKey: key, registrationStatus: 'ACCEPTED', acceptanceStatus: 'ACCEPTED', submissionVersion: '2',
  processingStatus: 'WAITING', result: { sessionId: '9007199254740993', messageId: '3', runId: '4' } })
const unresolved = key => ({ submissionKey: key, registrationStatus: 'UNOBSERVED', acceptanceStatus: 'UNKNOWN', submissionVersion: null, processingStatus: null, result: null })
const fakeServer = handler => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    const body = options.body ? JSON.parse(options.body) : null
    calls.push({ url, method: options.method, body })
    if (url === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF', token: 'synthetic' })
    if (url === '/api/session-intents') return Response.json({ intentId: '2', clientIntentId: body.clientIntentId, employeeId: body.employeeId, status: 'PREPARED' })
    return handler(url, options, body)
  }
  return calls
}
const start = async text => { const state = new TextComposerState(); await state.open(scope); state.draft.text = text; state.persist(); return state }

test('local draft creates no Session; accepted write preserves exact text and clears only after receipt', async () => {
  const calls = fakeServer((url, options, body) => Response.json(accepted(body.submissionKey)))
  const state = await start(' 第一行\n第二行😀 ')
  assert.equal(calls.length, 0)
  const result = await state.send()
  const post = calls.find(c => c.url === '/api/submissions')
  assert.equal(post.body.text, ' 第一行\n第二行😀 ')
  assert.deepEqual(post.body.target, { intentId: '2' })
  assert.deepEqual(post.body.uploadIds, [])
  assert.equal(result.result.sessionId, '9007199254740993')
  assert.equal(state.draft.text, '')
  assert.equal(state.pending, false)
  assert.equal(calls.filter(c => c.url === '/api/submissions').length, 1)
})
test('unknown and UNOBSERVED keep original content frozen until explicit permanent close', async () => {
  let originalKey
  const calls = fakeServer((url, options, body) => {
    if (url === '/api/submissions') { originalKey = body.submissionKey; throw new Error('response lost') }
    if (url.endsWith('/close')) return Response.json({ ...unresolved(originalKey), registrationStatus: 'CLOSED_UNACCEPTED', acceptanceStatus: 'CLOSED_UNACCEPTED', submissionVersion: '1' })
    return Response.json(unresolved(originalKey))
  })
  const state = await start('保留原文')
  await state.send(); await state.send(); await state.lookup()
  assert.equal(state.pending, true)
  assert.equal(state.draft.text, '保留原文')
  assert.equal(calls.filter(c => c.url === '/api/submissions').length, 1)
  await state.close()
  assert.equal(state.pending, false)
  assert.equal(state.draft.text, '保留原文')
  const firstKey = originalKey
  assert.equal(state.draft.intentId, null)
  await state.send()
  assert.notEqual(state.draft.pending.submissionKey, firstKey)
  assert.equal(state.draft.lastReceipt, null)
  assert.equal(calls.filter(c => c.url === '/api/submissions').length, 2)
})
test('receipt lookup after lost response never sends a second message', async () => {
  let key
  const calls = fakeServer((url, options, body) => {
    if (url === '/api/submissions') { key = body.submissionKey; throw new Error('lost') }
    return Response.json(accepted(key))
  })
  const state = await start('保留')
  await state.send()
  const result = await state.lookup()
  assert.equal(result.submissionKey, key)
  assert.equal(state.draft.text, '')
  assert.equal(calls.filter(c => c.url === '/api/submissions').length, 1)
})
test('refresh restores pending exact key and performs receipt GET only', async () => {
  let key
  const calls = fakeServer((url, options, body) => {
    if (url === '/api/submissions') { key = body.submissionKey; throw new Error('lost') }
    return Response.json(unresolved(key))
  })
  const state = await start('刷新保留')
  await state.send()
  const restored = new TextComposerState()
  await restored.open(scope)
  assert.equal(restored.draft.pending.submissionKey, key)
  assert.equal(restored.draft.text, '刷新保留')
  assert.equal(calls.filter(c => c.url === '/api/submissions').length, 1)
  assert.ok(calls.some(c => c.method === 'GET' && c.url.endsWith(key)))
})
test('late accepted receipt cannot hijack a newly selected conversation', async () => {
  let finish
  const calls = fakeServer((url, options, body) => new Promise(resolve => { finish = () => resolve(Response.json(accepted(body.submissionKey))) }))
  const state = await start('old')
  const pending = state.send()
  while (!finish) await new Promise(resolve => setTimeout(resolve, 0))
  await state.open({ ...scope, sessionId: '99' })
  state.draft.text = 'new draft'; state.persist()
  finish()
  assert.equal(await pending, undefined)
  assert.equal(state.draft.text, 'new draft')
  assert.equal(state.draft.scope.sessionId, '99')
  assert.equal(calls.filter(c => c.url === '/api/submissions').length, 1)
})
test('storage failures preserve in-memory input and warn without claiming saved recovery', async () => {
  fakeServer(() => { throw new Error('lost') })
  const state = await start('保留')
  sessionStorage.setItem = () => { throw new Error('quota') }
  state.persist()
  assert.match(state.storageWarning, /未能保存/)
  await state.send()
  assert.equal(state.pending, true)
  assert.equal(state.draft.text, '保留')
  assert.match(state.storageWarning, /未能保存/)
})
test('wrong receipt cannot clear frozen content or advance to another Session', async () => {
  fakeServer(() => Response.json(accepted(crypto.randomUUID())))
  const state = await start('保留')
  assert.equal(await state.send(), undefined)
  assert.equal(state.pending, true)
  assert.equal(state.draft.text, '保留')
})
test('close that loses its response reuses the same close request and can return original acceptance', async () => {
  let key, firstClose
  const calls = fakeServer((url, options, body) => {
    if (url === '/api/submissions') { key = body.submissionKey; throw new Error('lost') }
    if (url.endsWith('/close')) {
      if (!firstClose) { firstClose = body.requestKey; throw new Error('close lost') }
      assert.equal(body.requestKey, firstClose)
      return Response.json(accepted(key))
    }
  })
  const state = await start('original')
  await state.send(); await state.close()
  assert.equal(state.pending, true)
  const result = await state.close()
  assert.equal(result.submissionKey, key)
  assert.equal(state.draft.text, '')
  assert.equal(calls.filter(c => c.url === '/api/submissions').length, 1)
})
test('blank, invalid surrogate and excessive UTF-8 text never issue business writes', async () => {
  const calls = fakeServer(() => { throw new Error('unexpected write') })
  const state = await start('   ')
  await state.send()
  state.draft.text = '\ud800'; await state.send()
  state.draft.text = '😀'.repeat(65537); await state.send()
  assert.equal(calls.length, 0)
})

test('a late failed write after disposal cannot resurrect recovery removed by logout', async () => {
  let rejectWrite
  fakeServer(() => new Promise((resolve, reject) => { rejectWrite = reject }))
  const state = await start('退出后不得复活的文字')
  const sending = state.send()
  while (!rejectWrite) await new Promise(resolve => setTimeout(resolve, 0))
  state.dispose()
  storage.clear() // Simulates prefix cleanup after the view was disposed.
  rejectWrite(new Error('late network error'))
  await sending
  assert.equal(state.draft, null)
  assert.equal(storage.size, 0)
})
