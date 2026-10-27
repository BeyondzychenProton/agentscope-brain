import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'

const root = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(root, 'target'), { recursive: true })
const temp = await mkdtemp(path.join(root, 'target', 'session-tests-'))
for (const [file, name] of [['src/transport/http.ts', 'http.mjs'], ['src/features/workbench/sessions.ts', 'sessions.mjs']]) {
  const source = await readFile(path.join(root, file), 'utf8')
  await writeFile(path.join(temp, name), ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
  } }).outputText.replace('../../transport/http', './http.mjs'))
}
const { SessionNavigation } = await import(pathToFileURL(path.join(temp, 'sessions.mjs')))
const oldFetch = globalThis.fetch
after(async () => {
  globalThis.fetch = oldFetch
  if (!path.resolve(temp).startsWith(path.resolve(root, 'target') + path.sep)) throw new Error('Unexpected test cleanup path')
  await rm(temp, { recursive: true, force: true })
})
const item = (id, title = '历史') => ({ sessionId: id, employeeId: '1', title, sessionVersion: '1',
  updatedAt: '2026-10-27T00:00:00Z', activeRun: null, allowedActions: [] })
const page = (items, cursor = null) => ({ items, nextPageCursor: cursor, hasMore: Boolean(cursor) })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }

test('search and pagination use server GET with exact IDs, query and cursor', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url: new URL(url, 'https://example.invalid'), options })
    return Response.json(calls.length === 1 ? page([item('9007199254740993', '<img onerror=x>')], 'signed+cursor') : page([item('2')]))
  }
  const state = new SessionNavigation()
  await state.search('1', '  预算%_!中文  ')
  await state.more()
  assert.equal(state.items[0].sessionId, '9007199254740993')
  assert.equal(state.items[0].title, '<img onerror=x>')
  assert.equal(state.items.length, 2)
  assert.equal(calls[1].url.searchParams.get('q'), '预算%_!中文')
  assert.equal(calls[1].url.searchParams.get('pageCursor'), 'signed+cursor')
  assert.ok(calls.every(c => c.options.method === 'GET' && c.options.cache === 'no-store' && c.url.pathname === '/api/sessions'))
})
test('late search results cannot overwrite a newer query', async () => {
  const old = deferred()
  globalThis.fetch = async url => new URL(url, 'https://example.invalid').searchParams.get('q') === 'old'
    ? old.promise : Response.json(page([item('2')]))
  const state = new SessionNavigation()
  const pending = state.search('1', 'old')
  await state.search('1', 'new')
  old.resolve(Response.json(page([item('1')], 'obsolete')))
  await pending
  assert.deepEqual(state.items.map(s => s.sessionId), ['2'])
  assert.equal(state.nextPageCursor, null)
  assert.equal(state.busy, false)
})
test('logout or unmount clear invalidates pending reads', async () => {
  const pending = deferred()
  globalThis.fetch = () => pending.promise
  const state = new SessionNavigation()
  const loading = state.search('1', '')
  state.clear()
  pending.resolve(Response.json(page([item('1')])))
  await loading
  assert.deepEqual(state.items, [])
  assert.equal(state.busy, false)
})
test('obsolete pagination cannot append to another search', async () => {
  const pending = deferred()
  globalThis.fetch = async url => new URL(url, 'https://example.invalid').searchParams.has('pageCursor')
    ? pending.promise : Response.json(page([item('3')], 'more'))
  const state = new SessionNavigation()
  await state.search('1', '')
  const loading = state.more()
  globalThis.fetch = async () => Response.json(page([item('2')]))
  await state.search('1', 'new')
  pending.resolve(Response.json(page([item('1')])))
  await loading
  assert.deepEqual(state.items.map(s => s.sessionId), ['2'])
})
test('bad pages cannot partially append or advance the cursor', async () => {
  const state = new SessionNavigation()
  globalThis.fetch = async () => Response.json(page([item('3')], 'more'))
  await state.search('1', '')
  for (const invalid of [page([item('2'), { ...item('1'), employeeId: '2' }]), page([item('2'), item('2')]),
    page([{ ...item('2'), sessionId: 9007199254740992 }]), { ...page([item('2')]), hasMore: true }]) {
    globalThis.fetch = async () => Response.json(invalid)
    await assert.rejects(state.more(), /SESSION_PAGE_INVALID/)
    assert.deepEqual(state.items.map(s => s.sessionId), ['3'])
    assert.equal(state.nextPageCursor, 'more')
  }
})
test('authentication failure propagates without automatic retry', async () => {
  let calls = 0
  globalThis.fetch = async () => { calls++; return Response.json({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401 }) }
  const state = new SessionNavigation()
  await assert.rejects(state.search('1', ''), e => e.status === 401)
  assert.equal(calls, 1)
  assert.deepEqual(state.items, [])
})