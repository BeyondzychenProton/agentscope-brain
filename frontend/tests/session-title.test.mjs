import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'

const root = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(root, 'target'), { recursive: true })
const temp = await mkdtemp(path.join(root, 'target', 'session-title-tests-'))
for (const [file, name] of [
  ['src/transport/http.ts', 'http.mjs'],
  ['src/features/workbench/sessions.ts', 'sessions.mjs'],
  ['src/features/workbench/sessionTitle.ts', 'sessionTitle.mjs'],
]) {
  const source = await readFile(path.join(root, file), 'utf8')
  await writeFile(path.join(temp, name), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText
    .replace('../../transport/http', './http.mjs')
    .replace('./sessions', './sessions.mjs'))
}
const { validateTitle, renameFailureMessage } = await import(pathToFileURL(path.join(temp, 'sessionTitle.mjs')))
const { SessionNavigation } = await import(pathToFileURL(path.join(temp, 'sessions.mjs')))
const { ApiFailure } = await import(pathToFileURL(path.join(temp, 'http.mjs')))
after(async () => {
  if (!path.resolve(temp).startsWith(path.resolve(root, 'target') + path.sep)) throw new Error('Unexpected cleanup path')
  await rm(temp, { recursive: true, force: true })
})

test('validateTitle rejects empty and oversized titles', () => {
  assert.equal(validateTitle('  ok  '), null)
  assert.match(validateTitle('   '), /非空/)
  assert.match(validateTitle('字'.repeat(201)), /200/)
})

test('renameFailureMessage maps version conflict', () => {
  assert.match(renameFailureMessage(new ApiFailure(409, 'VERSION_CONFLICT')), /刷新/)
})

test('applySummary patches matching employee row only', () => {
  const nav = new SessionNavigation()
  nav.employeeId = '1'
  nav.items = [{
    sessionId: '10', employeeId: '1', title: '旧', sessionVersion: '1', updatedAt: '2026-10-27T00:00:00Z',
    activeRun: null, allowedActions: [],
  }]
  nav.applySummary({
    sessionId: '10', employeeId: '1', title: '新', sessionVersion: '2', updatedAt: '2026-10-27T01:00:00Z',
    activeRun: null, allowedActions: [],
  })
  assert.equal(nav.items[0].title, '新')
  assert.equal(nav.items[0].sessionVersion, '2')
  nav.applySummary({
    sessionId: '10', employeeId: '2', title: '错员工', sessionVersion: '3', updatedAt: '2026-10-27T02:00:00Z',
    activeRun: null, allowedActions: [],
  })
  assert.equal(nav.items[0].title, '新')
})
