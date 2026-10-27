import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'
import { createHash } from 'node:crypto'

const root = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(root, 'target'), { recursive: true })
const temp = await mkdtemp(path.join(root, 'target', 'history-tests-'))
for (const [file, name] of [['src/transport/http.ts', 'http.mjs'], ['src/features/workbench/history.ts', 'history.mjs']]) {
  const source = await readFile(path.join(root, file), 'utf8')
  await writeFile(path.join(temp, name), ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
  } }).outputText.replace('../../transport/http', './http.mjs'))
}
const { displayMessages } = await import(pathToFileURL(path.join(temp, 'history.mjs')))
const oldFetch = globalThis.fetch
after(async () => { globalThis.fetch = oldFetch; await rm(temp, { recursive: true, force: true }) })
const body = '<img src=x onerror="alert(1)">\n[link](javascript:alert(1)) 中文😀'
const sha256 = createHash('sha256').update(body).digest('hex')
const ref = { contentId: 'id', contentVersion: '1', kind: 'TEXT', bodyFormat: 'markdown-v1', byteCount: String(Buffer.byteLength(body)), sha256 }
const page = { items: [{ messageId: '9007199254740993', messageNo: '1', senderKind: 'ASSISTANT', contents: [ref] }] }
test('verified text stays literal, IDs stay strings, reads never submit or call external URLs', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, method: options.method })
    return Response.json({ ...ref, body, nextPartCursor: null })
  }
  const result = await displayMessages('101', page)
  assert.equal(result[0].texts[0].body, body)
  assert.equal(result[0].texts[0].bodyFormat, 'markdown-v1')
  assert.equal(result[0].messageId, '9007199254740993')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'GET')
  assert.match(calls[0].url, /^\/api\/sessions\/101\/projection\/content\//)
})
for (const [name, override] of [
  ['digest corruption', { body: body + 'x' }],
  ['different fixed version', { contentVersion: '2' }],
  ['wrong length', { byteCount: '1' }],
  ['different format', { bodyFormat: 'json-v1' }],
  ['incomplete content', { nextPartCursor: 'more' }],
]) test(`${name} rejects the entire page`, async () => {
  globalThis.fetch = async () => Response.json({ ...ref, body, nextPartCursor: null, ...override })
  await assert.rejects(displayMessages('101', page), /CONTENT_INVALID/)
})
test('401 aborts further content reads and cannot display a partial page', async () => {
  let count = 0
  globalThis.fetch = async () => { count++; return Response.json({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401 }) }
  await assert.rejects(displayMessages('101', { items: [...page.items, ...page.items] }), error => error.status === 401)
  assert.equal(count, 1)
})
