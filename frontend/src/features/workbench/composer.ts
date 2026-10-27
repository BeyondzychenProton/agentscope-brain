import { ApiFailure, read, write } from '../../transport/http'
import { readTabRecovery, writeTabRecovery } from '../auth/tabRecovery'

export interface Receipt {
  submissionKey: string
  registrationStatus: 'UNOBSERVED' | 'REGISTERED' | 'RECONCILING' | 'ACCEPTED' | 'CLOSED_UNACCEPTED'
  acceptanceStatus: 'UNKNOWN' | 'ACCEPTED' | 'CLOSED_UNACCEPTED'
  submissionVersion: string | null
  result: { sessionId: string; messageId: string; runId: string } | null
  processingStatus: string | null
}
interface Intent { intentId: string; clientIntentId: string; employeeId: string; status: string }
export interface Scope { userId: string; employeeId: string; sessionId: string | null }
interface Payload { submissionKey: string; target: { intentId: string } | { sessionId: string }; text: string; uploadIds: string[] }
interface Draft {
  scope: Scope; text: string; clientIntentId: string; prepareKey: string; intentId: string | null
  pending: Payload | null; lastReceipt: Receipt | null; closeRequest?: { requestKey: string; expectedSubmissionVersion: string | null } | null
}
const sameScope = (a: Scope, b: Scope) => a.userId === b.userId && a.employeeId === b.employeeId && a.sessionId === b.sessionId
const key = (s: Scope) => 'text.' + [s.userId, s.employeeId, s.sessionId ?? 'new'].map(encodeURIComponent).join('.')
const fresh = (scope: Scope): Draft => ({ scope: { ...scope }, text: '', clientIntentId: crypto.randomUUID(),
  prepareKey: crypto.randomUUID(), intentId: null, pending: null, lastReceipt: null })

/** Store pending key and exact content before a write. Reads never retry a submission. */
export class TextComposerState {
  draft: Draft | null = null
  busy = false
  notice = ''
  storageWarning = ''
  get pending() { return Boolean(this.draft?.pending) }

  async open(scope: Scope): Promise<Receipt | undefined> {
    const draft = fresh(scope)
    try {
      const raw = readTabRecovery(key(scope))
      if (raw) {
        const saved = JSON.parse(raw) as Draft
        if (!sameScope(saved.scope, scope) || typeof saved.text !== 'string'
          || typeof saved.clientIntentId !== 'string' || typeof saved.prepareKey !== 'string'
          || (saved.pending && (saved.pending.text !== saved.text || !saved.pending.submissionKey
            || (scope.sessionId ? !('sessionId' in saved.pending.target) || saved.pending.target.sessionId !== scope.sessionId
              : !('intentId' in saved.pending.target) || saved.pending.target.intentId !== saved.intentId)))) {
          throw new Error('RECOVERY_INVALID')
        }
        Object.assign(draft, saved)
      }
      this.storageWarning = ''
    } catch {
      this.storageWarning = '当前标签的草稿恢复不可用，输入仍可保留在本页。'
    }
    this.draft = draft
    this.busy = false
    this.notice = ''
    // Only a receipt GET on restore. UNOBSERVED never unlocks or resends the old content.
    if (draft.pending) return this.lookup()
    return undefined
  }
  persist(draft = this.draft) {
    if (!draft || draft !== this.draft) return
    try {
      writeTabRecovery(key(draft.scope), JSON.stringify(draft))
      if (this.draft === draft) this.storageWarning = ''
    } catch {
      if (this.draft === draft) this.storageWarning = '草稿未能保存到当前标签，刷新后可能无法恢复；当前输入仍保留。'
    }
  }
  async send(): Promise<Receipt | undefined> {
    const d = this.draft
    if (!d || this.busy || d.pending || !d.text.trim()) return
    if (new TextEncoder().encode(d.text).length > 262144 || hasLoneSurrogate(d.text)) {
      this.notice = '文字须为有效 Unicode，且 UTF-8 长度不能超过 256 KiB。'; return
    }
    this.busy = true
    this.notice = ''
    try {
      if (!d.scope.sessionId && !d.intentId) {
        this.persist(d)
        const intent = await write<Intent>('/api/session-intents', {
          requestKey: d.prepareKey, clientIntentId: d.clientIntentId, employeeId: d.scope.employeeId,
        })
        if (intent.clientIntentId !== d.clientIntentId || intent.employeeId !== d.scope.employeeId || !/^[1-9][0-9]*$/.test(intent.intentId)) {
          throw new ApiFailure(0, 'INTENT_INVALID')
        }
        if (intent.status === 'ACCEPTED') throw new ApiFailure(409, 'INTENT_ALREADY_ACCEPTED')
        if (intent.status !== 'PREPARED') throw new ApiFailure(409, 'PREPARATION_STALE')
        d.intentId = intent.intentId
        this.persist(d)
      }
      if (this.draft !== d) return
      d.closeRequest = null
      d.lastReceipt = null
      d.pending = {
        submissionKey: crypto.randomUUID(),
        target: d.scope.sessionId ? { sessionId: d.scope.sessionId } : { intentId: d.intentId! },
        text: d.text, uploadIds: [],
      }
      this.persist(d)
      const receipt = await write<Receipt>('/api/submissions', d.pending)
      return this.apply(d, receipt)
    } catch (failure) {
      if (failure instanceof ApiFailure && (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED')) throw failure
      if (this.draft === d) {
        this.notice = d.pending
          ? '本次接纳结果尚未确认。原文与提交键已保留，请回查；编辑或重新发送前须先封闭原提交。'
          : failure instanceof ApiFailure && failure.code === 'INTENT_ALREADY_ACCEPTED'
            ? '原新建意图已绑定会话，请先查看原会话结果。当前文字仍保留。'
            : '发送准备未完成，文字仍保留。可再次明确发送以核对同一准备意图。'
      }
    } finally {
      this.persist(d)
      if (this.draft === d) this.busy = false
    }
    return undefined
  }
  async lookup(): Promise<Receipt | undefined> {
    const d = this.draft
    if (!d?.pending || this.busy) return
    this.busy = true
    try {
      const receipt = await read<Receipt>('/api/submissions/' + encodeURIComponent(d.pending.submissionKey))
      const accepted = this.apply(d, receipt)
      if (!accepted && this.draft === d) this.notice = receipt.acceptanceStatus === 'CLOSED_UNACCEPTED'
        ? '原提交已封闭且未接纳，可编辑文字后明确发送。' : '原提交尚未确认接纳，原文保持冻结。可以再次回查，或明确封闭后编辑。'
      return accepted
    } catch (failure) {
      if (failure instanceof ApiFailure && (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED')) throw failure
      if (this.draft === d) this.notice = '回查未完成，原文与提交键仍保留，请稍后回查。'
    } finally {
      if (this.draft === d) this.busy = false
    }
    return undefined
  }
  async close(): Promise<Receipt | undefined> {
    const d = this.draft
    if (!d?.pending || this.busy) return
    this.busy = true
    try {
      d.closeRequest ??= { requestKey: crypto.randomUUID(), expectedSubmissionVersion: d.lastReceipt?.submissionVersion ?? null }
      this.persist(d)
      const receipt = await write<Receipt>('/api/submissions/' + encodeURIComponent(d.pending.submissionKey) + '/close', d.closeRequest)
      const accepted = this.apply(d, receipt)
      if (!accepted && this.draft === d) this.notice = '原提交已封闭且未接纳，可编辑文字后明确发送。'
      return accepted
    } catch (failure) {
      if (failure instanceof ApiFailure && (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED')) throw failure
      if (this.draft === d) this.notice = '封闭尚未确认，原文保持冻结；请先回查原提交。'
    } finally {
      if (this.draft === d) this.busy = false
    }
    return undefined
  }
  private apply(d: Draft, receipt: Receipt): Receipt | undefined {
    if (d !== this.draft) return undefined
    if (!d.pending || receipt.submissionKey !== d.pending.submissionKey
      || !['UNOBSERVED', 'REGISTERED', 'RECONCILING', 'ACCEPTED', 'CLOSED_UNACCEPTED'].includes(receipt.registrationStatus)
      || !['UNKNOWN', 'ACCEPTED', 'CLOSED_UNACCEPTED'].includes(receipt.acceptanceStatus)
      || (receipt.registrationStatus === 'ACCEPTED') !== (receipt.acceptanceStatus === 'ACCEPTED')
      || (receipt.registrationStatus === 'CLOSED_UNACCEPTED') !== (receipt.acceptanceStatus === 'CLOSED_UNACCEPTED')) {
      throw new ApiFailure(0, 'SUBMISSION_RECEIPT_INVALID')
    }
    d.lastReceipt = receipt
    if (receipt.acceptanceStatus === 'ACCEPTED') {
      if (!receipt.result || ![receipt.result.sessionId, receipt.result.messageId, receipt.result.runId]
        .every(id => typeof id === 'string' && /^[1-9][0-9]*$/.test(id))) throw new ApiFailure(0, 'SUBMISSION_RECEIPT_INVALID')
      d.text = ''
      d.pending = null
      d.closeRequest = null
      // A completed blank-page intent cannot be used for the next new conversation.
      d.clientIntentId = crypto.randomUUID(); d.prepareKey = crypto.randomUUID(); d.intentId = null
      this.persist(d)
      if (this.draft === d) { this.notice = '消息已接纳，等待执行。'; return receipt }
    } else if (receipt.acceptanceStatus === 'CLOSED_UNACCEPTED') {
      d.pending = null
      d.closeRequest = null
      if (!d.scope.sessionId) {
        d.clientIntentId = crypto.randomUUID(); d.prepareKey = crypto.randomUUID(); d.intentId = null
      }
      this.persist(d)
    }
    return undefined
  }
  dispose() { this.draft = null; this.busy = false }
}

function hasLoneSurrogate(text: string) {
  for (const char of text) if (char.length === 1 && char.charCodeAt(0) >= 0xd800 && char.charCodeAt(0) <= 0xdfff) return true
  return false
}
