import { PROCESS_PROTOCOL, type ProcessBatch, type ProcessStep } from './processProtocol'

export class ProcessBatchError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ProcessBatchError'
  }
}

export type ProcessState = {
  steps: ProcessStep[]
  cursor: string | null
  lastSeq: string | null
  streamId: string | null
  expanded: Record<string, boolean>
}

export function emptyProcessState(): ProcessState {
  return { steps: [], cursor: null, lastSeq: null, streamId: null, expanded: {} }
}

export function applyProcessBatch(state: ProcessState, batch: ProcessBatch): ProcessState {
  if (batch.protocolVersion !== PROCESS_PROTOCOL) throw new ProcessBatchError('PROTOCOL_MISMATCH')
  if (batch.changes.some(c => c.type !== 'PROCESS_STEP_UPSERT' && c.type !== 'PROCESS_STEP_DETAIL_SET')) {
    throw new ProcessBatchError('UNKNOWN_CHANGE')
  }
  const expectedPrev = state.lastSeq ?? '0'
  if (batch.previousSeq !== expectedPrev || (state.lastSeq != null && BigInt(batch.seq) !== BigInt(state.lastSeq) + 1n)) {
    throw new ProcessBatchError('SEQ_GAP')
  }
  if (state.streamId != null && state.streamId !== batch.streamId) throw new ProcessBatchError('STREAM_MISMATCH')

  const byId = new Map(state.steps.map(s => [s.stepId, { ...s }]))
  for (const change of batch.changes) {
    if (change.type === 'PROCESS_STEP_UPSERT') {
      const prev = byId.get(change.stepId)
      byId.set(change.stepId, {
        stepId: change.stepId, runId: change.runId, kind: change.kind, title: change.title,
        summary: change.summary ?? null, status: change.status,
        orderAnchor: prev ? prev.orderAnchor : change.orderAnchor,
        viewRevision: change.viewRevision, collapsedByDefault: change.collapsedByDefault,
        operationId: change.operationId ?? null,
        detail: change.detail ?? prev?.detail ?? null,
        detailVersion: change.detailVersion ?? prev?.detailVersion ?? null,
        detailRef: change.detailRef ?? prev?.detailRef ?? null,
      })
    } else {
      const prev = byId.get(change.stepId)
      if (!prev) throw new ProcessBatchError('DETAIL_WITHOUT_STEP')
      byId.set(change.stepId, {
        ...prev,
        detail: change.detail ?? prev.detail,
        detailVersion: change.detailVersion,
        detailRef: change.detailRef ?? prev.detailRef,
      })
    }
  }
  const steps = [...byId.values()].sort((a, b) => {
    const d = BigInt(a.orderAnchor.seq) - BigInt(b.orderAnchor.seq)
    if (d !== 0n) return d < 0n ? -1 : 1
    const i = BigInt(a.orderAnchor.index) - BigInt(b.orderAnchor.index)
    return i < 0n ? -1 : i > 0n ? 1 : 0
  })
  return { steps, cursor: batch.cursor, lastSeq: batch.seq, streamId: batch.streamId, expanded: state.expanded }
}
