import { after, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'
import { createPinia, setActivePinia } from 'pinia'

// Compile the actual two TypeScript modules without adding a test framework or
// copying their implementation. Temporary modules stay outside the production bundle.
const frontend = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(frontend, 'target'), { recursive: true })
const temporary = await mkdtemp(path.join(frontend, 'target', 'auth-tests-'))
for (const [source, name] of [['src/transport/http.ts', 'http.mjs'], ['src/features/auth/store.ts', 'store.mjs']]) {
  const text = await readFile(path.join(frontend, source), 'utf8')
  const output = ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  await writeFile(path.join(temporary, name), output.replace('../../transport/http', './http.mjs'))
}
const { useAuth } = await import(pathToFileURL(path.join(temporary, 'store.mjs')).href)
const originalFetch = globalThis.fetch
after(async () => { globalThis.fetch = originalFetch; await rm(temporary, { recursive: true, force: true }) })
beforeEach(() => setActivePinia(createPinia()))
const restricted = { authStage: 'PASSWORD_CHANGE_REQUIRED', userId: '1', displayName: 'synthetic', authenticatedAt: '2026-10-27T01:00:00Z', absoluteExpiresAt: '2026-10-28T01:00:00Z' }

function stubWrite(outcome) {
  const requests = []
  globalThis.fetch = async (url, options) => {
    requests.push({ url, method: options.method })
    if (url === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-csrf' })
    if (outcome instanceof Error) throw outcome
    return outcome
  }
  return requests
}
function assertSinglePost(requests) {
  assert.equal(requests.filter(x => x.method === 'POST').length, 1)
  assert.equal(requests.length, 2)
}

test('partial success clears identity, states that password changed, and never retries', async () => {
  const auth = useAuth(); auth.identity = { ...restricted }
  const requests = stubWrite(Response.json({ code: 'PASSWORD_CHANGED_RELOGIN_REQUIRED' }, { status: 503 }))
  await assert.rejects(auth.changeInitialPassword('synthetic-current', 'synthetic-new-password'))
  assert.equal(auth.identity, null)
  assert.match(auth.passwordNotice, /密码已修改/)
  assert.match(auth.passwordNotice, /新密码重新登录/)
  assertSinglePost(requests)
})

for (const [name, outcome] of [
  ['unproven SQL outcome', () => Response.json({ code: 'AUTH_OUTCOME_UNCERTAIN' }, { status: 503 })],
  ['network interruption', () => new TypeError('synthetic network interruption')],
  ['unreadable success response', () => new Response('malformed-json', { status: 200 })],
]) test(`${name} preserves uncertainty and never automatically repeats password submission`, async () => {
  const auth = useAuth(); auth.identity = { ...restricted }
  const requests = stubWrite(outcome())
  await assert.rejects(auth.changeInitialPassword('synthetic-current', 'synthetic-new-password'))
  assert.equal(auth.identity, null)
  assert.match(auth.passwordNotice, /结果尚未确认/)
  assert.match(auth.passwordNotice, /请勿直接重复提交/)
  assertSinglePost(requests)
})

test('known current-password validation error keeps restricted identity without claiming a changed password', async () => {
  const auth = useAuth(); auth.identity = { ...restricted }
  const requests = stubWrite(Response.json({ code: 'CURRENT_PASSWORD_INVALID' }, { status: 422 }))
  await assert.rejects(auth.changeInitialPassword('synthetic-wrong', 'synthetic-new-password'))
  assert.equal(auth.identity.authStage, 'PASSWORD_CHANGE_REQUIRED')
  assert.equal(auth.passwordNotice, '')
  assertSinglePost(requests)
})

test('expired or revoked authentication asks for login and discards old identity', async () => {
  const auth = useAuth(); auth.identity = { ...restricted }
  const requests = stubWrite(Response.json({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401 }))
  await assert.rejects(auth.changeInitialPassword('synthetic-current', 'synthetic-new-password'))
  assert.equal(auth.identity, null)
  assert.match(auth.passwordNotice, /重新登录/)
  assertSinglePost(requests)
})

test('normal success keeps original deadline, reaches READY, and stores no supplied credentials', async () => {
  const auth = useAuth(); auth.identity = { ...restricted }
  const requests = stubWrite(Response.json({ ...restricted, authStage: 'READY' }))
  await auth.changeInitialPassword('synthetic-current', 'synthetic-new-password')
  assert.equal(auth.identity.authStage, 'READY')
  assert.equal(auth.identity.authenticatedAt, restricted.authenticatedAt)
  assert.equal(auth.identity.absoluteExpiresAt, restricted.absoluteExpiresAt)
  assert.doesNotMatch(JSON.stringify(auth.$state), /synthetic-current|synthetic-new-password/)
  assertSinglePost(requests)
})
