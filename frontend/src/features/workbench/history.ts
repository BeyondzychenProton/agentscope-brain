import { ApiFailure, read } from '../../transport/http'

export interface Capability {
  employeeId: string; acceptsText: boolean; acceptsImages: boolean; allowedActions: string[]
  unavailableReason: string | null; textSubmissionAvailable?: boolean; imageLimits: Record<string, unknown>
}
export interface ContentRef {
  contentId: string; contentVersion: string; kind: string; bodyFormat: string; byteCount: string; sha256: string
}
export interface FormalMessage {
  messageId: string; messageNo: string; senderKind: 'USER' | 'ASSISTANT'; acceptedAt: string; contents: ContentRef[]
}
export interface Page {
  items: FormalMessage[]; nextPageCursor: string | null; hasMore: boolean; messageUpperBound: string
}
export interface Snapshot {
  protocolVersion: string; sessionId: string; streamId: string; watermark: string; cursor: string
  session: { employeeId: string; title: string; allowedActions: string[] }; messages: Page
  runs?: Array<{ runId: string; status: string; processingStatus: string | null }>
}
export interface DisplayMessage { messageId: string; senderKind: 'USER' | 'ASSISTANT'; texts: string[] }
export const protocol = 'web-projection-v1'
export function basePath(sessionId: string) { return `/api/sessions/${encodeURIComponent(sessionId)}/projection` }

/** Verify exact UTF-8 text bytes before exposing any part of the page to the renderer. */
export async function displayMessages(sessionId: string, page: Page): Promise<DisplayMessage[]> {
  const result: DisplayMessage[] = []
  for (const message of page.items) {
    if (!['USER', 'ASSISTANT'].includes(message.senderKind)) throw new ApiFailure(0, 'CONTENT_INVALID')
    const texts: string[] = []
    for (const ref of message.contents) {
      if (ref.kind !== 'TEXT' || !['plain-text', 'markdown-v1'].includes(ref.bodyFormat)) throw new ApiFailure(0, 'CONTENT_INVALID')
      const content = await read<ContentRef & { body: string; nextPartCursor: string | null }>(
        `${basePath(sessionId)}/content/${encodeURIComponent(ref.contentId)}?protocolVersion=${protocol}&contentVersion=${encodeURIComponent(ref.contentVersion)}`)
      const bytes = new TextEncoder().encode(content.body)
      const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(x => x.toString(16).padStart(2, '0')).join('')
      if (content.contentId !== ref.contentId || content.contentVersion !== ref.contentVersion || content.bodyFormat !== ref.bodyFormat
        || content.byteCount !== ref.byteCount || BigInt(ref.byteCount) !== BigInt(bytes.length)
        || digest !== ref.sha256 || digest !== content.sha256 || content.nextPartCursor !== null) throw new ApiFailure(0, 'CONTENT_INVALID')
      texts.push(content.body)
    }
    result.push({ messageId: message.messageId, senderKind: message.senderKind, texts })
  }
  return result
}
