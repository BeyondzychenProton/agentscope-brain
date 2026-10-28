import { ApiFailure, read } from '../../transport/http'
import { basePath, protocol } from './history'
import {
  applyTextBatch,
  emptyTextProjectionState,
  TextBatchError,
  type TextBatch,
  type TextProjectionState,
} from './textProjectionReducer'

export type SubscriptionStatus = 'idle' | 'connecting' | 'live' | 'degraded_poll' | 'paused'

export type ProjectionSubscriptionCallbacks = {
  onState: (state: TextProjectionState) => void
  onStatus: (status: SubscriptionStatus) => void
  onCommit: () => void
  onAuthRequired: () => void
}

/**
 * SSE for web-projection-v1 with poll fallback after 3 consecutive SSE failures.
 * Application cursor is only the explicit `after` query param (never Last-Event-ID).
 */
export class ProjectionSubscription {
  status: SubscriptionStatus = 'idle'
  private state: TextProjectionState = emptyTextProjectionState()
  private sessionId: string | null = null
  private source: EventSource | null = null
  private pollTimer: ReturnType<typeof setTimeout> | null = null
  private sseFailures = 0
  private readFailures = 0
  private stopped = true
  private runActive = false
  private readonly callbacks: ProjectionSubscriptionCallbacks

  constructor(callbacks: ProjectionSubscriptionCallbacks) {
    this.callbacks = callbacks
  }

  current() {
    return this.state
  }

  start(sessionId: string, initial: TextProjectionState, runActive: boolean) {
    this.stop()
    this.stopped = false
    this.sessionId = sessionId
    this.state = initial
    this.runActive = runActive
    this.sseFailures = 0
    this.readFailures = 0
    this.callbacks.onState(this.state)
    this.connectSse()
  }

  setRunActive(active: boolean) {
    this.runActive = active
  }

  stop() {
    this.stopped = true
    this.teardownSse()
    if (this.pollTimer != null) {
      clearTimeout(this.pollTimer)
      this.pollTimer = null
    }
    this.status = 'idle'
    this.callbacks.onStatus(this.status)
  }

  private connectSse() {
    if (this.stopped || !this.sessionId || !this.state.cursor) return
    this.teardownSse()
    this.status = 'connecting'
    this.callbacks.onStatus(this.status)
    const url = `${basePath(this.sessionId)}/stream?protocolVersion=${encodeURIComponent(protocol)}&after=${encodeURIComponent(this.state.cursor)}`
    const source = new EventSource(url, { withCredentials: true })
    this.source = source
    source.addEventListener('projection.batch', (event) => {
      try {
        const batch = JSON.parse((event as MessageEvent).data) as TextBatch
        const before = this.state.committedMessageIds.length
        this.state = applyTextBatch(this.state, batch)
        this.sseFailures = 0
        this.readFailures = 0
        this.status = 'live'
        this.callbacks.onStatus(this.status)
        this.callbacks.onState(this.state)
        if (this.state.committedMessageIds.length > before) this.callbacks.onCommit()
      } catch (failure) {
        if (failure instanceof TextBatchError) {
          this.schedulePoll(true)
          return
        }
        this.noteSseFailure()
      }
    })
    source.addEventListener('caught_up', () => {
      this.sseFailures = 0
      this.status = 'live'
      this.callbacks.onStatus(this.status)
    })
    source.addEventListener('auth_required', () => {
      this.stop()
      this.callbacks.onAuthRequired()
    })
    source.addEventListener('reset_required', () => {
      this.schedulePoll(true)
    })
    source.onerror = () => {
      this.noteSseFailure()
    }
  }

  private noteSseFailure() {
    this.teardownSse()
    this.sseFailures += 1
    if (this.sseFailures >= 3) {
      this.status = 'degraded_poll'
      this.callbacks.onStatus(this.status)
      this.schedulePoll(false)
      return
    }
    const delay = Math.min(4000, 1000 * 2 ** (this.sseFailures - 1))
    this.pollTimer = setTimeout(() => this.connectSse(), delay)
  }

  private schedulePoll(resnapshotHint: boolean) {
    this.teardownSse()
    if (this.stopped) return
    this.status = 'degraded_poll'
    this.callbacks.onStatus(this.status)
    const delay = this.runActive ? 2000 : 10000
    this.pollTimer = setTimeout(async () => {
      if (this.stopped || !this.sessionId || !this.state.cursor) return
      try {
        const page = await read<{
          batches: TextBatch[]
          lastCursor: string
          headSeq: string
          hasMore: boolean
        }>(`${basePath(this.sessionId)}/events?protocolVersion=${encodeURIComponent(protocol)}&after=${encodeURIComponent(this.state.cursor)}&limit=100`)
        this.readFailures = 0
        const before = this.state.committedMessageIds.length
        for (const batch of page.batches ?? []) {
          this.state = applyTextBatch(this.state, batch)
        }
        if ((page.batches ?? []).length === 0 && typeof page.lastCursor === 'string') {
          this.state = { ...this.state, cursor: page.lastCursor }
        }
        this.callbacks.onState(this.state)
        if (this.state.committedMessageIds.length > before) this.callbacks.onCommit()
        // Retry SSE every 30s while polling.
        if (Date.now() % 30_000 < delay) {
          this.sseFailures = 0
          this.connectSse()
          return
        }
        this.schedulePoll(false)
      } catch (failure) {
        if (failure instanceof ApiFailure && (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED')) {
          this.stop()
          this.callbacks.onAuthRequired()
          return
        }
        this.readFailures += 1
        if (this.readFailures >= 5) {
          this.status = 'paused'
          this.callbacks.onStatus(this.status)
          return
        }
        if (resnapshotHint || failure instanceof TextBatchError) {
          this.callbacks.onCommit()
        }
        this.schedulePoll(false)
      }
    }, delay)
  }

  private teardownSse() {
    if (this.source) {
      this.source.close()
      this.source = null
    }
  }
}
