/** web-projection-v1 reducer for formal reply preview (#43 Variant A). */

export const TEXT_PROTOCOL = 'web-projection-v1'

export class TextBatchError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'TextBatchError'
  }
}

export type TextPreview = {
  contentId: string
  contentVersion: string
  runId: string
  body: string
  status: 'OPEN'
}

export type TextProjectionState = {
  cursor: string | null
  lastSeq: string | null
  streamId: string | null
  previews: Record<string, TextPreview>
  /** contentIds removed by FAILED/INTERRUPTED close or replaced by MESSAGE_COMMIT */
  committedMessageIds: string[]
}

export type TextBatch = {
  protocolVersion: string
  sessionId: string
  streamId: string
  seq: string
  previousSeq: string
  cursor: string
  occurredAt: string
  changes: Array<Record<string, unknown>>
}

export function emptyTextProjectionState(): TextProjectionState {
  return { cursor: null, lastSeq: null, streamId: null, previews: {}, committedMessageIds: [] }
}

export function applyTextBatch(state: TextProjectionState, batch: TextBatch): TextProjectionState {
  if (batch.protocolVersion !== TEXT_PROTOCOL) throw new TextBatchError('PROTOCOL_MISMATCH')
  const expectedPrev = state.lastSeq ?? '0'
  if (batch.previousSeq !== expectedPrev || (state.lastSeq != null && BigInt(batch.seq) !== BigInt(state.lastSeq) + 1n)) {
    throw new TextBatchError('SEQ_GAP')
  }
  if (state.streamId != null && state.streamId !== batch.streamId) throw new TextBatchError('STREAM_MISMATCH')

  const previews = { ...state.previews }
  const committedMessageIds = [...state.committedMessageIds]

  for (const change of batch.changes) {
    const type = String(change.type ?? '')
    if (type === 'CONTENT_SET' && change.status === 'OPEN') {
      const contentId = String(change.contentId)
      previews[contentId] = {
        contentId,
        contentVersion: String(change.contentVersion ?? '1'),
        runId: String(change.runId ?? ''),
        body: typeof change.body === 'string' ? change.body : '',
        status: 'OPEN',
      }
    } else if (type === 'TEXT_APPEND') {
      const contentId = String(change.contentId)
      const prev = previews[contentId]
      if (!prev) throw new TextBatchError('APPEND_WITHOUT_OPEN')
      const delta = typeof change.delta === 'string' ? change.delta : ''
      if (String(change.baseContentVersion) !== prev.contentVersion) throw new TextBatchError('BASE_MISMATCH')
      previews[contentId] = {
        ...prev,
        contentVersion: String(change.contentVersion),
        body: prev.body + delta,
      }
    } else if (type === 'CONTENT_CLOSE') {
      const contentId = String(change.contentId)
      const reason = String(change.closeReason ?? '')
      if (reason === 'FAILED' || reason === 'INTERRUPTED') {
        delete previews[contentId]
      } else if (reason === 'PROMOTED') {
        delete previews[contentId]
      }
    } else if (type === 'MESSAGE_COMMIT') {
      const replaces = Array.isArray(change.replacesContentIds) ? change.replacesContentIds.map(String) : []
      for (const id of replaces) delete previews[id]
      if (typeof change.messageId === 'string') committedMessageIds.push(change.messageId)
    } else if (
      type === 'SESSION_SET'
      || type === 'RUN_SET'
      || (type === 'CONTENT_SET' && change.status === 'CLOSED')
    ) {
      // Formal closed content is loaded via snapshot/history; ignore here.
    } else if (type) {
      throw new TextBatchError('UNKNOWN_CHANGE')
    }
  }

  return {
    cursor: batch.cursor,
    lastSeq: batch.seq,
    streamId: batch.streamId,
    previews,
    committedMessageIds,
  }
}

export function seedOpenPreviews(
  state: TextProjectionState,
  input: {
    streamId: string
    watermark: string
    cursor: string
    openPreviews: Array<{
      contentId: string
      contentVersion: string
      runId: string
      body: string
      status: string
    }>
  },
): TextProjectionState {
  const previews: Record<string, TextPreview> = {}
  for (const row of input.openPreviews) {
    if (row.status !== 'OPEN') continue
    previews[row.contentId] = {
      contentId: row.contentId,
      contentVersion: row.contentVersion,
      runId: row.runId,
      body: row.body,
      status: 'OPEN',
    }
  }
  return {
    ...state,
    streamId: input.streamId,
    lastSeq: input.watermark,
    cursor: input.cursor,
    previews,
  }
}
