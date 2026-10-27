import { after, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'
import { createPinia, setActivePinia } from 'pinia'

const frontend = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(frontend, 'target'), { recursive: true })
const temporary = await mkdtemp(path.join(frontend, 'target', 'tab-recovery-'))
const memory = new Map()
const foreign = new Map()
globalThis.sessionStorage = {
  get length() { return memory.size },
  key(index) { return [...memory.keys()][index] ?? null },
  getItem(key) { return memory.has(key) ? memory.get(key) : null },
  setItem(key, value) { memory.set(key, String(value)) },
  removeItem(key) { memory.delete(key) },
  clear() { throw new Error('sessionStorage.clear must not be used for auth cleanup') },
}
globalThis.localStorage = {
  get length() { return foreign.size },
  key(index) { return [...foreign.keys()][index] ?? null },
  getItem(key) { return foreign.has(key) ? foreign.get(key) : null },
  setItem(key, value) { foreign.set(key, String(value)) },
  removeItem(key) { foreign.delete(key) },
  clear() { throw new Error('localStorage.clear must not be used for auth cleanup') },
}

for (const [source, name] of [
  ['src/transport/http.ts', 'http.mjs'],
  ['src/features/auth/tabRecovery.ts', 'tabRecovery.mjs'],
  ['src/features/auth/store.ts', 'store.mjs'],
]) {
  const text = await readFile(path.join(frontend, source), 'utf8')
  const output = ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  await writeFile(path.join(temporary, name), output
    .replace('../../transport/http', './http.mjs')
    .replace('./tabRecovery', './tabRecovery.mjs'))
}
const { useAuth } = await import(pathToFileURL(path.join(temporary, 'store.mjs')).href)
const { RECOVERY_PREFIX, writeTabRecovery, readTabRecovery, clearCurrentTabRecovery } =
  await import(pathToFileURL(path.join(temporary, 'tabRecovery.mjs')).href)
const originalFetch = globalThis.fetch
after(async () => { globalThis.fetch = originalFetch; await rm(temporary, { recursive: true, force: true }) })
beforeEach(() => {
  memory.clear()
  foreign.clear()
  setActivePinia(createPinia())
})

test('recovery helpers are prefix-scoped and never clear the whole storage', () => {
  writeTabRecovery('draft', 'keep-local-text')
  sessionStorage.setItem('unrelated.tab', 'other-tab-marker')
  localStorage.setItem('shared', 'other-browser-or-tab-local')
  assert.equal(readTabRecovery('draft'), 'keep-local-text')
  clearCurrentTabRecovery()
  assert.equal(readTabRecovery('draft'), null)
  assert.equal(sessionStorage.getItem('unrelated.tab'), 'other-tab-marker')
  assert.equal(localStorage.getItem('shared'), 'other-browser-or-tab-local')
  assert.ok([...memory.keys()].every(key => !key.startsWith(RECOVERY_PREFIX)))
})

test('logout and expired restore clear only this tab application recovery', async () => {
  const auth = useAuth()
  writeTabRecovery('composer', 'synthetic-draft')
  sessionStorage.setItem('foreign.marker', 'still-here')
  localStorage.setItem('foreign.local', 'still-here')
  globalThis.fetch = async (url, options) => {
    if (url === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-csrf' })
    if (url === '/api/auth/logout' && options.method === 'POST') return new Response(null, { status: 204 })
    if (url === '/api/me') return Response.json({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401 })
    throw new Error(String(url))
  }
  await auth.logout()
  assert.equal(auth.identity, null)
  assert.equal(readTabRecovery('composer'), null)
  assert.equal(sessionStorage.getItem('foreign.marker'), 'still-here')
  assert.equal(localStorage.getItem('foreign.local'), 'still-here')
  writeTabRecovery('composer', 'restored-for-expiry')
  await auth.restore()
  assert.equal(auth.identity, null)
  assert.equal(readTabRecovery('composer'), null)
  assert.equal(sessionStorage.getItem('foreign.marker'), 'still-here')
})
