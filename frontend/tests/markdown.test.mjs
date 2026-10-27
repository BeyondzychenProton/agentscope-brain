import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'

const root = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(root, 'target'), { recursive: true })
const temp = await mkdtemp(path.join(root, 'target', 'markdown-tests-'))

const mdSource = await readFile(path.join(root, 'src/features/workbench/markdown.ts'), 'utf8')
// Drop DOMPurify browser import for Node unit tests; purify is always injected below.
const stubbed = mdSource
  .replace(/import MarkdownIt from 'markdown-it'\r?\n/, "import MarkdownIt from 'markdown-it'\n")
  .replace(/import DOMPurify from 'dompurify'\r?\n/, '')
  .replace(
    /export function browserPurify\(dirty: string\): string \{[\s\S]*?\n\}/,
    'export function browserPurify(dirty) { return dirty }',
  )
await writeFile(path.join(temp, 'markdown.mjs'), ts.transpileModule(stubbed, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText.replace("from 'markdown-it'", `from ${JSON.stringify(pathToFileURL(path.join(root, 'node_modules/markdown-it/dist/markdown-it.mjs')).href)}`))

const { renderMarkdownV1 } = await import(pathToFileURL(path.join(temp, 'markdown.mjs')))
after(async () => {
  if (!path.resolve(temp).startsWith(path.resolve(root, 'target') + path.sep)) throw new Error('Unexpected cleanup path')
  await rm(temp, { recursive: true, force: true })
})

/** Deterministic stand-in: strip event handlers and non-http(s) hrefs. */
function testPurify(dirty) {
  return dirty
    .replace(/\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
    .replace(/\shref\s*=\s*("javascript:[^"]*"|'javascript:[^']*'|javascript:[^\s>]+)/gi, ' href=""')
    .replace(/<img\b[^>]*>/gi, '')
}

test('keeps https links and basic structure after purify', () => {
  const result = renderMarkdownV1('见 [文档](https://example.com/a) 与列表\n\n- 一项', testPurify)
  assert.equal(result.kind, 'html')
  assert.match(result.value, /href="https:\/\/example\.com\/a"/)
  assert.match(result.value, /<li>/)
})

test('rejects javascript links and raw html / image markdown', () => {
  const source = '<img src=x onerror="alert(1)">\n[坏链](javascript:alert(1))\n\n![图](https://example.com/x.png)'
  const result = renderMarkdownV1(source, testPurify)
  assert.equal(result.kind, 'html')
  // Raw HTML stays escaped text; no executable handlers or img nodes; no javascript: href.
  assert.match(result.value, /&lt;img/)
  assert.doesNotMatch(result.value, /onerror\s*=/i)
  assert.doesNotMatch(result.value, /href\s*=\s*["']?\s*javascript:/i)
  assert.doesNotMatch(result.value, /<img\b/i)
})

test('purify throw falls back to plain source', () => {
  const source = '**仍可读**'
  const result = renderMarkdownV1(source, () => { throw new Error('purify down') })
  assert.deepEqual(result, { kind: 'plain', value: source })
})

test('non-string purify result falls back to plain', () => {
  const source = 'hello'
  const result = renderMarkdownV1(source, () => null)
  assert.deepEqual(result, { kind: 'plain', value: source })
})

test('code fence remains textual content inside pre/code', () => {
  const result = renderMarkdownV1('```\nconst x = 1\n```', testPurify)
  assert.equal(result.kind, 'html')
  assert.match(result.value, /<pre>/)
  assert.match(result.value, /const x = 1/)
})

test('GFM tables render as table elements not pipe text', () => {
  const source = '办：\n\n| 项目 | 内容 |\n| --- | --- |\n| 主题 | agent开发 |\n\n**注意**：x'
  const result = renderMarkdownV1(source, testPurify)
  assert.equal(result.kind, 'html')
  assert.match(result.value, /<table>/)
  assert.match(result.value, /<th>项目<\/th>/)
  assert.match(result.value, /<td>agent开发<\/td>/)
  assert.match(result.value, /<strong>注意<\/strong>/)
  assert.doesNotMatch(result.value, /\| --- \|/)
})
