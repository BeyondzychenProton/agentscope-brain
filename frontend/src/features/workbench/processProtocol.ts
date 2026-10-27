export const PROCESS_PROTOCOL = 'web-projection-process-v1' as const
export const V1_PROTOCOL = 'web-projection-v1' as const

export type ProcessStepKind = 'STAGE' | 'TOOL_STEP' | 'REASONING'
export type ProcessStepStatus =
  | 'RUNNING' | 'DONE' | 'FAILED' | 'WAITING' | 'CANCELLED' | 'INTERRUPTED' | 'UNKNOWN'

export type OrderAnchor = { seq: string; index: string }

export type ProcessStep = {
  stepId: string
  runId: string
  kind: ProcessStepKind
  title: string
  summary: string | null
  status: ProcessStepStatus
  orderAnchor: OrderAnchor
  viewRevision: string
  collapsedByDefault: boolean
  operationId: string | null
  detail: string | null
  detailVersion: string | null
  detailRef: { detailVersion: string; byteLength: string; sha256: string } | null
}

export type ProcessBatch = {
  protocolVersion: typeof PROCESS_PROTOCOL
  sessionId: string
  streamId: string
  seq: string
  previousSeq: string
  cursor: string
  occurredAt: string
  changes: ProcessChange[]
}

export type ProcessChange =
  | ({ type: 'PROCESS_STEP_UPSERT' } & Omit<ProcessStep, 'detail' | 'detailVersion' | 'detailRef'> & {
      detail?: string | null
      detailVersion?: string | null
      detailRef?: ProcessStep['detailRef']
    })
  | {
      type: 'PROCESS_STEP_DETAIL_SET'
      stepId: string
      detail?: string
      detailVersion: string
      byteLength: string
      sha256: string
      detailRef?: ProcessStep['detailRef']
    }
