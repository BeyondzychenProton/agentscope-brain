import { read } from '../../transport/http'
import { basePath } from './history'
import {
  PROCESS_PROTOCOL,
  type ProcessBatch,
  type ProcessStep,
  type ProcessStepKind,
  type ProcessStepStatus,
} from './processProtocol'

export const processProtocol = PROCESS_PROTOCOL

export type ProcessSnapshot = {
  protocolVersion?: string
  sessionId?: string
  streamId?: string
  watermark?: string
  cursor?: string
  steps?: unknown[]
}

export type ProcessAfter = {
  protocolVersion?: string
  sessionId?: string
  streamId?: string
  watermark?: string
  cursor?: string
  batches?: ProcessBatch[]
}

const KINDS = new Set<string>(['STAGE', 'TOOL_STEP', 'REASONING'])
const STATUSES = new Set<string>([
  'RUNNING', 'DONE', 'FAILED', 'WAITING', 'CANCELLED', 'INTERRUPTED', 'UNKNOWN',
])

function text(value: unknown): string | null {
  if (typeof value !== 'string') return null
  return value
}

function asKind(value: unknown): ProcessStepKind {
  return typeof value === 'string' && KINDS.has(value) ? value as ProcessStepKind : 'STAGE'
}

function asStatus(value: unknown): ProcessStepStatus {
  return typeof value === 'string' && STATUSES.has(value) ? value as ProcessStepStatus : 'UNKNOWN'
}

function mapStep(raw: unknown): ProcessStep | null {
  if (!raw || typeof raw !== 'object') return null
  const row = raw as Record<string, unknown>
  const stepId = text(row.stepId)
  const runId = text(row.runId)
  const title = text(row.title)
  const viewRevision = text(row.viewRevision)
  if (!stepId || !runId || title == null || !viewRevision) return null
  const anchor = row.orderAnchor
  if (!anchor || typeof anchor !== 'object') return null
  const order = anchor as Record<string, unknown>
  const seq = text(order.seq)
  const index = text(order.index)
  if (!seq || !index) return null
  const kind = asKind(row.kind ?? row.stepKind)
  const status = asStatus(row.status ?? row.stepStatus)
  const detailRefRaw = row.detailRef
  let detailRef: ProcessStep['detailRef'] = null
  if (detailRefRaw && typeof detailRefRaw === 'object') {
    const ref = detailRefRaw as Record<string, unknown>
    const detailVersion = text(ref.detailVersion)
    const byteLength = text(ref.byteLength)
    const sha256 = text(ref.sha256)
    if (detailVersion && byteLength && sha256) {
      detailRef = { detailVersion, byteLength, sha256 }
    }
  }
  return {
    stepId,
    runId,
    kind,
    title,
    summary: text(row.summary),
    status,
    orderAnchor: { seq, index },
    viewRevision,
    collapsedByDefault: Boolean(row.collapsedByDefault),
    operationId: text(row.operationId),
    detail: text(row.detail),
    detailVersion: text(row.detailVersion),
    detailRef,
  }
}

/** Map snapshot.steps (kind/status or stepKind/stepStatus) into ProcessStep[]. */
export function stepsFromSnapshot(raw: ProcessSnapshot | { steps?: unknown } | null | undefined): ProcessStep[] {
  const list = raw && typeof raw === 'object' && Array.isArray(raw.steps) ? raw.steps : []
  const steps: ProcessStep[] = []
  for (const item of list) {
    const mapped = mapStep(item)
    if (mapped) steps.push(mapped)
  }
  return steps
}

export function processHasProjection(raw: ProcessSnapshot): boolean {
  const steps = Array.isArray(raw.steps) ? raw.steps.length : 0
  const watermark = text(raw.watermark) ?? '0'
  const streamId = text(raw.streamId)?.trim() ?? ''
  try {
    return steps > 0 || BigInt(watermark) > 0n || streamId.length > 0
  } catch {
    return steps > 0 || streamId.length > 0
  }
}

export async function processSnapshot(sessionId: string): Promise<ProcessSnapshot> {
  return read<ProcessSnapshot>(
    `${basePath(sessionId)}/process/snapshot?protocolVersion=${processProtocol}&processLimit=50`,
  )
}

export async function processAfter(sessionId: string, cursor: string): Promise<ProcessAfter> {
  return read<ProcessAfter>(
    `${basePath(sessionId)}/process/after?protocolVersion=${processProtocol}&cursor=${encodeURIComponent(cursor)}`,
  )
}
