import { ApiFailure, read } from '../../transport/http'

export interface SessionSummary {
  sessionId: string; employeeId: string; title: string | null; sessionVersion: string; updatedAt: string
  activeRun: { runId: string; status: string; processingStatus: string | null } | null
  allowedActions: string[]
}
interface SessionPage { items: SessionSummary[]; nextPageCursor: string | null; hasMore: boolean }

/** Each search owns its response generation; obsolete reads cannot repopulate a cleared view. */
export class SessionNavigation {
  items: SessionSummary[] = []
  nextPageCursor: string | null = null
  busy = false
  private generation = 0
  private employeeId = ''
  private query = ''

  clear() {
    this.generation++
    this.items = []
    this.nextPageCursor = null
    this.busy = false
  }
  async search(employeeId: string, query: string) {
    this.clear()
    this.employeeId = employeeId
    this.query = query.trim()
    if (!employeeId) return
    await this.load(false)
  }
  async more() {
    if (!this.busy && this.nextPageCursor) await this.load(true)
  }
  private async load(append: boolean) {
    const generation = ++this.generation
    this.busy = true
    const params = new URLSearchParams({ employeeId: this.employeeId, limit: '50' })
    if (this.query) params.set('q', this.query)
    if (append && this.nextPageCursor) params.set('pageCursor', this.nextPageCursor)
    try {
      const page = await read<SessionPage>(`/api/sessions?${params}`)
      if (generation !== this.generation) return
      if (!Array.isArray(page.items) || page.items.length > 50 || typeof page.hasMore !== 'boolean'
        || (page.nextPageCursor !== null && typeof page.nextPageCursor !== 'string')
        || page.hasMore !== Boolean(page.nextPageCursor)
        || page.items.some(s => !/^[1-9][0-9]*$/.test(s.sessionId) || typeof s.sessionId !== 'string'
          || s.employeeId !== this.employeeId || (s.title !== null && typeof s.title !== 'string'))
        || new Set(page.items.map(s => s.sessionId)).size !== page.items.length) {
        throw new ApiFailure(0, 'SESSION_PAGE_INVALID')
      }
      const known = new Set(append ? this.items.map(s => s.sessionId) : [])
      this.items = [...(append ? this.items : []), ...page.items.filter(s => !known.has(s.sessionId))]
      this.nextPageCursor = page.nextPageCursor
    } catch (failure) {
      if (generation === this.generation) throw failure
    } finally {
      if (generation === this.generation) this.busy = false
    }
  }
}
