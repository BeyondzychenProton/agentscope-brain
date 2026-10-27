import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import ts from 'typescript'

const root = fileURLToPath(new URL('../', import.meta.url))
await mkdir(path.join(root, 'target'), { recursive: true })
const temp = await mkdtemp(path.join(root, 'target', 'run-watch-tests-'))
{
  const source = await readFile(path.join(root, 'src/features/workbench/runWatch.ts'), 'utf8')
  await writeFile(path.join(temp, 'runWatch.mjs'), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText)
}
const { RunWatch, failedRunNotice } = await import(pathToFileURL(path.join(temp, 'runWatch.mjs')))
after(async () => {
  if (!path.resolve(temp).startsWith(path.resolve(root, 'target') + path.sep)) throw new Error('Unexpected cleanup path')
  await rm(temp, { recursive: true, force: true })
})

function fakeScheduler() {
  const queue = []
  return {
    scheduler: {
      schedule: (fn, ms) => { const id = queue.length + 1; queue.push({ id, fn, ms }); return id },
      clear: (id) => {
        const index = queue.findIndex(item => item.id === id)
        if (index >= 0) queue.splice(index, 1)
      },
    },
    async flush() {
      const next = queue.shift()
      assert.ok(next, 'expected a scheduled poll')
      await next.fn()
    },
    get pending() { return queue.length },
  }
}

const run = (runId, status, processingStatus = status) => ({ runId, status, processingStatus })
const observe = (runs, assistants = []) => ({ sessionId: '10', runs, assistantMessageIds: assistants })

test('failedRunNotice maps model arrearage', () => {
  assert.match(failedRunNotice('MODEL_Arrearage'), /欠费/)
  assert.match(failedRunNotice('MODEL_Timeout'), /MODEL_Timeout/)
})

test('start waits then succeeds when target run leaves and a new assistant appears', async () => {
  const clock = fakeScheduler()
  let step = 0
  const watch = new RunWatch(async () => {
    step += 1
    if (step === 1) return observe([run('4', 'WAITING')])
    if (step === 2) return observe([run('4', 'RUNNING', 'RUNNING')], ['old-a'])
    return observe([], ['old-a', 'new-a'])
  }, clock.scheduler, 60)
  watch.start('10', '4', ['old-a'])
  assert.equal(watch.phase, 'waiting')
  await clock.flush()
  assert.equal(watch.phase, 'waiting')
  await clock.flush()
  assert.equal(watch.phase, 'running')
  await clock.flush()
  assert.equal(watch.phase, 'idle')
  assert.equal(watch.notice, '')
  assert.equal(watch.active, false)
  assert.equal(clock.pending, 0)
})

test('old assistant alone does not count as this round succeeding', async () => {
  const clock = fakeScheduler()
  const watch = new RunWatch(async () => observe([], ['old-a']), clock.scheduler, 60)
  watch.start('10', '4', ['old-a'])
  await clock.flush()
  assert.equal(watch.phase, 'endedEmpty')
  assert.match(watch.notice, /没有正式回复/)
})

test('FAILED stops with mapped notice', async () => {
  const clock = fakeScheduler()
  const watch = new RunWatch(async () => observe([run('4', 'FAILED', 'MODEL_Arrearage')]), clock.scheduler)
  watch.start('10', '4')
  await clock.flush()
  assert.equal(watch.phase, 'failed')
  assert.match(watch.notice, /欠费/)
  assert.equal(clock.pending, 0)
})

test('resume continues an active run from snapshot', async () => {
  const clock = fakeScheduler()
  let calls = 0
  const watch = new RunWatch(async () => {
    calls += 1
    return calls === 1
      ? observe([run('9', 'RUNNING', 'RUNNING')], ['a'])
      : observe([], ['a', 'b'])
  }, clock.scheduler)
  watch.resume(observe([run('9', 'WAITING')], ['a']))
  assert.equal(watch.runId, '9')
  assert.equal(watch.phase, 'waiting')
  await clock.flush()
  assert.equal(watch.phase, 'running')
  await clock.flush()
  assert.equal(watch.phase, 'idle')
})

test('stop cancels pending poll', async () => {
  const clock = fakeScheduler()
  let reloads = 0
  const watch = new RunWatch(async () => { reloads += 1; return observe([run('4', 'WAITING')]) }, clock.scheduler)
  watch.start('10', '4')
  watch.stop()
  assert.equal(watch.phase, 'idle')
  assert.equal(clock.pending, 0)
  assert.equal(reloads, 0)
})

test('attempt cap yields stalled', async () => {
  const clock = fakeScheduler()
  const watch = new RunWatch(async () => observe([run('4', 'RUNNING', 'RUNNING')]), clock.scheduler, 1)
  watch.start('10', '4')
  await clock.flush()
  assert.equal(watch.phase, 'running')
  await clock.flush()
  assert.equal(watch.phase, 'stalled')
  assert.match(watch.notice, /刷新/)
})

test('reload null yields readError', async () => {
  const clock = fakeScheduler()
  const watch = new RunWatch(async () => null, clock.scheduler)
  watch.start('10', '4')
  await clock.flush()
  assert.equal(watch.phase, 'readError')
})
