export type RunWatchPhase =
  | 'idle'
  | 'waiting'
  | 'running'
  | 'failed'
  | 'endedEmpty'
  | 'stalled'
  | 'readError'

export type WatchedRun = { runId: string; status: string; processingStatus: string | null }

export type RunWatchObserve = {
  sessionId: string
  runs: WatchedRun[]
  /** ASSISTANT message ids currently visible; used only to detect replies added after watch started. */
  assistantMessageIds: string[]
}

export type RunWatchScheduler = {
  schedule: (fn: () => void, ms: number) => unknown
  clear: (handle: unknown) => void
}

const ACTIVE = (run: WatchedRun) =>
  run.status === 'WAITING' || run.status === 'RUNNING'
  || run.processingStatus === 'WAITING' || run.processingStatus === 'RUNNING'

export function failedRunNotice(reason: string | null | undefined) {
  if (reason === 'MODEL_Arrearage' || reason === 'Arrearage') {
    return '模型服务拒绝执行（账号欠费或不可用）。请在百炼控制台恢复额度后重试发送。'
  }
  if (reason?.startsWith('MODEL_')) {
    return `模型调用失败（${reason}）。请检查模型配置后重试发送。`
  }
  return '本轮执行失败，没有正式回复。请稍后重试发送。'
}

/** Bounded read-only poll for one ordinary run. Does not set page busy or write submissions. */
export class RunWatch {
  phase: RunWatchPhase = 'idle'
  notice = ''
  sessionId: string | null = null
  runId: string | null = null
  private generation = 0
  private attempt = 0
  private timer: unknown = null
  private baselineAssistants = new Set<string>()
  private readonly maxAttempts: number
  private readonly reload: () => Promise<RunWatchObserve | null>
  private readonly scheduler: RunWatchScheduler

  constructor(
    reload: () => Promise<RunWatchObserve | null>,
    scheduler: RunWatchScheduler = {
      schedule: (fn, ms) => setTimeout(fn, ms),
      clear: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
    },
    maxAttempts = 60,
  ) {
    this.reload = reload
    this.scheduler = scheduler
    this.maxAttempts = maxAttempts
  }

  get active() {
    return this.phase === 'waiting' || this.phase === 'running'
  }

  start(sessionId: string, runId: string, baselineAssistantIds: string[] = []) {
    this.arm(sessionId, runId, baselineAssistantIds, 0)
    this.phase = 'waiting'
    this.notice = '消息已接纳，等待执行。'
    this.queue(0)
  }

  /** Continue watching if the snapshot still has a WAITING/RUNNING run. */
  resume(observe: RunWatchObserve) {
    const active = observe.runs.find(ACTIVE)
    if (!active) {
      if (this.sessionId === observe.sessionId && this.active) this.stop()
      return
    }
    if (this.sessionId === observe.sessionId && this.runId === active.runId && this.active) {
      this.apply(observe)
      return
    }
    this.arm(observe.sessionId, active.runId, observe.assistantMessageIds, 0)
    this.apply(observe)
    if (this.active) this.queue(0)
  }

  stop() {
    this.generation += 1
    this.clearTimer()
    this.sessionId = null
    this.runId = null
    this.attempt = 0
    this.baselineAssistants = new Set()
    this.phase = 'idle'
    this.notice = ''
  }

  private arm(sessionId: string, runId: string, baseline: string[], attempt: number) {
    this.generation += 1
    this.clearTimer()
    this.sessionId = sessionId
    this.runId = runId
    this.attempt = attempt
    this.baselineAssistants = new Set(baseline)
  }

  private queue(attempt: number) {
    this.clearTimer()
    if (!this.active && this.phase !== 'waiting' && this.phase !== 'running') return
    const generation = this.generation
    const delay = Math.min(1500 + attempt * 500, 5000)
    this.timer = this.scheduler.schedule(() => {
      void this.tick(generation)
    }, delay)
  }

  private async tick(generation: number) {
    if (generation !== this.generation || !this.sessionId || !this.runId) return
    try {
      const observe = await this.reload()
      if (generation !== this.generation) return
      if (!observe || observe.sessionId !== this.sessionId) {
        this.finish('readError', '执行状态读取未完成，请刷新后查看。')
        return
      }
      this.apply(observe)
      if (!this.active) return
      if (this.attempt >= this.maxAttempts) {
        this.finish('stalled', '执行仍在进行或结果未就绪，请刷新后继续查看。')
        return
      }
      this.attempt += 1
      this.queue(this.attempt)
    } catch {
      if (generation !== this.generation) return
      this.finish('readError', '执行状态读取未完成，请刷新后查看。')
    }
  }

  private apply(observe: RunWatchObserve) {
    const targetId = this.runId
    if (!targetId) return
    const target = observe.runs.find(run => run.runId === targetId)
    if (target?.status === 'FAILED' || target?.processingStatus === 'FAILED') {
      this.finish('failed', failedRunNotice(target.processingStatus))
      return
    }
    if (target && ACTIVE(target)) {
      const running = target.status === 'RUNNING' || target.processingStatus === 'RUNNING'
      this.phase = running ? 'running' : 'waiting'
      this.notice = running ? '正在调用模型，请稍候…' : '消息已接纳，等待执行。'
      return
    }
    const added = observe.assistantMessageIds.some(id => !this.baselineAssistants.has(id))
    if (added) {
      this.finish('idle', '')
      return
    }
    this.finish('endedEmpty', '本轮执行已结束，但没有正式回复。请刷新后核对，或稍后重试发送。')
  }

  private finish(phase: RunWatchPhase, notice: string) {
    this.clearTimer()
    this.phase = phase
    this.notice = notice
    if (phase !== 'waiting' && phase !== 'running') {
      this.runId = null
    }
  }

  private clearTimer() {
    if (this.timer != null) {
      this.scheduler.clear(this.timer)
      this.timer = null
    }
  }
}
