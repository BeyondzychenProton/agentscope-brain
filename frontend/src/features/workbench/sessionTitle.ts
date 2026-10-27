import { ApiFailure, patch } from '../../transport/http'
import type { SessionSummary } from './sessions'

export type RenameTarget = {
  sessionId: string
  title: string | null
  sessionVersion: string
}

const MAX_TITLE_POINTS = 200

export function validateTitle(raw: string): string | null {
  const title = raw.trim()
  if (!title) return '请输入非空标题。'
  if ([...title].length > MAX_TITLE_POINTS) return '标题不能超过 200 字。'
  for (const char of title) {
    const code = char.codePointAt(0) ?? 0
    if (code < 0x20 || (code >= 0x7f && code <= 0x9f) || (code >= 0xd800 && code <= 0xdfff)) {
      return '标题包含无法保存的字符。'
    }
  }
  return null
}

export function renameFailureMessage(failure: unknown) {
  if (!(failure instanceof ApiFailure)) return '改名未完成，请稍后重试。'
  if (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED') return failure.code
  if (failure.code === 'VERSION_CONFLICT') return '标题可能已变更，请刷新后重试。'
  if (failure.code === 'TITLE_INVALID') return '请输入非空且不超过 200 字的标题。'
  if (failure.code === 'NOT_FOUND') return '该会话不存在，或当前账号无权改名。'
  if (failure.code === 'IDEMPOTENCY_CONFLICT') return '本次改名请求已失效，请关闭后重试。'
  return '改名未完成，请稍后重试。'
}

/** One dialog open keeps a stable requestKey until close. */
export async function renameSession(target: RenameTarget, title: string, requestKey: string): Promise<SessionSummary> {
  const error = validateTitle(title)
  if (error) throw new ApiFailure(400, 'TITLE_INVALID')
  return patch<SessionSummary>(`/api/sessions/${encodeURIComponent(target.sessionId)}/title`, {
    requestKey,
    expectedSessionVersion: target.sessionVersion,
    title: title.trim(),
  })
}
