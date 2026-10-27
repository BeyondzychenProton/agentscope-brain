import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'

const root = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(root, 'target'), { recursive: true })
const temp = await mkdtemp(path.join(root, 'target', 'process-timeline-view-tests-'))
const source = await readFile(path.join(root, 'src/features/workbench/processTimelineView.ts'), 'utf8')
const output = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText
await writeFile(path.join(temp, 'processTimelineView.mjs'), output)
const { shouldHideRunWatchBanner, processPlaceholderNotice } = await import(
  pathToFileURL(path.join(temp, 'processTimelineView.mjs')),
)
after(async () => {
  if (!path.resolve(temp).startsWith(path.resolve(root, 'target') + path.sep)) throw new Error('Unexpected cleanup path')
  await rm(temp, { recursive: true, force: true })
})

test('hides legacy status bar when process timeline owns the run', () => {
  assert.equal(shouldHideRunWatchBanner({
    processProtocolEnabled: true, hasProcessSteps: true, runActive: true,
  }), true)
  assert.equal(shouldHideRunWatchBanner({
    processProtocolEnabled: true, hasProcessSteps: false, runActive: true,
  }), true)
  assert.equal(shouldHideRunWatchBanner({
    processProtocolEnabled: false, hasProcessSteps: false, runActive: true,
  }), false)
})

test('placeholder notice prefers runWatch then 执行中', () => {
  assert.equal(processPlaceholderNotice({ hasProcessSteps: true, runWatchNotice: 'x', snapshotWaiting: true }), null)
  assert.equal(processPlaceholderNotice({ hasProcessSteps: false, runWatchNotice: '等待执行', snapshotWaiting: true }), '等待执行')
  assert.equal(processPlaceholderNotice({ hasProcessSteps: false, runWatchNotice: '', snapshotWaiting: true }), '执行中…')
})
