<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import { useAuth } from '../features/auth/store'
import { ApiFailure, read } from '../transport/http'
import { SessionNavigation, type SessionSummary } from '../features/workbench/sessions'
import TextComposer from '../features/workbench/TextComposer.vue'
import MarkdownBody from '../features/workbench/MarkdownBody.vue'
import RenameSessionDialog from '../features/workbench/RenameSessionDialog.vue'
import ProcessTimeline from '../features/workbench/ProcessTimeline.vue'
import {
  processAfter,
  processHasProjection,
  processSnapshot,
  stepsFromSnapshot,
} from '../features/workbench/processHistory'
import {
  applyProcessBatch,
  emptyProcessState,
  ProcessBatchError,
  type ProcessState,
} from '../features/workbench/processTimelineReducer'
import { processPlaceholderNotice, shouldHideRunWatchBanner } from '../features/workbench/processTimelineView'
import type { ProcessBatch } from '../features/workbench/processProtocol'
import type { Receipt } from '../features/workbench/composer'
import { RunWatch, type RunWatchObserve } from '../features/workbench/runWatch'
import type { RenameTarget } from '../features/workbench/sessionTitle'
import {
  basePath,
  displayMessages,
  protocol,
  type Capability,
  type DisplayMessage,
  type Page,
  type Snapshot,
} from '../features/workbench/history'
import {
  emptyTextProjectionState,
  seedOpenPreviews,
  type TextProjectionState,
} from '../features/workbench/textProjectionReducer'
import { ProjectionSubscription, type SubscriptionStatus } from '../features/workbench/projectionSubscription'
import happyPathBatches from '../features/workbench/fixtures/process-timeline/happy-path.json' with { type: 'json' }

const auth = useAuth()
const router = useRouter()
const route = useRoute()
const busy = ref(false)
const error = ref('')
const capabilities = ref<Capability[]>([])
const snapshot = ref<Snapshot | null>(null)
const messages = ref<DisplayMessage[]>([])
const nextPageCursor = ref<string | null>(null)
let loadSeq = 0
const navigation = reactive(new SessionNavigation())
const searchTitle = ref('')
const listError = ref('')
const selectedEmployeeId = ref('')
const headerMenuOpen = ref(false)
const renameOpen = ref(false)
const renameTarget = ref<RenameTarget | null>(null)
const sessionId = computed(() => (typeof route.query.sessionId === 'string' ? route.query.sessionId : ''))
const employees = computed(() => auth.identity?.employees ?? [])
const historyCapability = computed(() =>
  capabilities.value.find((x) => x.employeeId === snapshot.value?.session.employeeId),
)
const employeeId = computed(() => selectedEmployeeId.value || employees.value[0]?.employeeId || '')
const employeeLabel = computed(() => employees.value.find(x => x.employeeId === (snapshot.value?.session.employeeId || employeeId.value))?.displayName ?? '数字员工')
const titleText = computed(() => (sessionId.value ? snapshot.value?.session.title || '会话' : '新对话'))
const subtitleText = computed(() =>
  sessionId.value
    ? `${employeeLabel.value} · 文字会话`
    : `${employeeLabel.value} · 可以开始整理思路`,
)
const textInputAvailable = computed(() => (sessionId.value
  ? Boolean(snapshot.value?.session.allowedActions?.includes('START_RUN'))
  : Boolean(capabilities.value.find(c => c.employeeId === employeeId.value)?.textSubmissionAvailable)))
const snapshotRunActive = computed(() => (snapshot.value?.runs ?? []).some(run =>
  run.status === 'WAITING' || run.status === 'RUNNING'
  || run.processingStatus === 'WAITING' || run.processingStatus === 'RUNNING'))
const runWatch = reactive(new RunWatch(() => reloadForWatch()))
const runActive = computed(() => runWatch.active || snapshotRunActive.value)
const canSendText = computed(() => textInputAvailable.value && !runActive.value)
const avatarText = computed(() => (auth.identity?.displayName ?? '用').slice(0, 1))
const processState = reactive(emptyProcessState())
const processProtocolEnabled = ref(false)
const textProjection = reactive(emptyTextProjectionState())
const sseStatus = ref<SubscriptionStatus>('idle')
const projectionSub = new ProjectionSubscription({
  onState(next) { Object.assign(textProjection, next) },
  onStatus(status) { sseStatus.value = status },
  onCommit() { void reloadAfterCommit() },
  onAuthRequired() { void failed(new ApiFailure(401, 'UNAUTHORIZED')) },
})
const previewList = computed(() => Object.values(textProjection.previews))
const sseStatusLabel = computed(() => {
  switch (sseStatus.value) {
    case 'live': return 'live'
    case 'connecting': return 'connecting'
    case 'degraded_poll': return 'poll'
    case 'paused': return 'paused'
    default: return sseStatus.value
  }
})
const wantFixture = computed(() => route.query.processFixture === '1')
const hideRunWatchBanner = computed(() => shouldHideRunWatchBanner({
  processProtocolEnabled: processProtocolEnabled.value,
  hasProcessSteps: processState.steps.length > 0,
  runActive: runActive.value || wantFixture.value,
}))

async function reloadAfterCommit() {
  const seq = loadSeq
  const target = sessionId.value
  if (!target) return
  try {
    await loadHistory(seq, true)
  } catch (failure) {
    if (failure instanceof ApiFailure && (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED')) {
      await failed(failure)
    }
  }
}

function syncTextProjection(value: Snapshot, target: string) {
  const seeded = seedOpenPreviews(emptyTextProjectionState(), {
    streamId: value.streamId,
    watermark: value.watermark,
    cursor: value.cursor,
    openPreviews: (value.openPreviews ?? []).map(row => ({
      contentId: row.contentId,
      contentVersion: row.contentVersion,
      runId: row.runId,
      body: row.body,
      status: row.status,
    })),
  })
  Object.assign(textProjection, seeded)
  const active = (value.runs ?? []).some(run =>
    run.status === 'WAITING' || run.status === 'RUNNING'
    || run.processingStatus === 'WAITING' || run.processingStatus === 'RUNNING')
  if (active && seeded.cursor) {
    projectionSub.start(target, { ...seeded, previews: { ...seeded.previews } }, true)
  } else {
    projectionSub.stop()
    projectionSub.setRunActive(false)
  }
}

function applyFixturePreview() {
  let next = emptyProcessState()
  for (const batch of happyPathBatches as ProcessBatch[]) {
    next = applyProcessBatch(next, batch)
  }
  Object.assign(processState, next)
  processProtocolEnabled.value = true
}

function clearProcessPreview() {
  Object.assign(processState, emptyProcessState())
  processProtocolEnabled.value = false
}

function syncProcessFixture() {
  if (wantFixture.value) applyFixturePreview()
  else clearProcessPreview()
}

function assignProcessState(next: ProcessState) {
  Object.assign(processState, next)
}

function snapshotToProcessState(raw: Awaited<ReturnType<typeof processSnapshot>>): ProcessState {
  return {
    steps: stepsFromSnapshot(raw),
    cursor: typeof raw.cursor === 'string' ? raw.cursor : null,
    lastSeq: typeof raw.watermark === 'string' ? raw.watermark : '0',
    streamId: typeof raw.streamId === 'string' && raw.streamId.trim() ? raw.streamId : null,
    expanded: {},
  }
}

function processUnavailable(failure: unknown): boolean {
  return failure instanceof ApiFailure && (failure.status === 404 || failure.status === 409 || failure.status === 503)
}

function shouldResnapshotProcess(failure: unknown): boolean {
  if (failure instanceof ProcessBatchError) {
    return failure.message === 'SEQ_GAP' || failure.message === 'STREAM_MISMATCH'
  }
  if (!(failure instanceof ApiFailure)) return false
  return failure.status === 409
    || failure.status === 410
    || failure.code === 'STREAM_CHANGED'
    || failure.code === 'CURSOR_INVALID'
    || failure.code === 'RESET_REQUIRED'
}

async function loadProcessSnapshot(seq: number, target: string) {
  const raw = await processSnapshot(target)
  if (seq !== loadSeq || sessionId.value !== target) return
  if (!processHasProjection(raw)) {
    clearProcessPreview()
    return
  }
  processProtocolEnabled.value = true
  assignProcessState(snapshotToProcessState(raw))
}

async function catchUpProcess(seq: number, target: string) {
  const cursor = processState.cursor
  if (!cursor) {
    await loadProcessSnapshot(seq, target)
    return
  }
  try {
    const after = await processAfter(target, cursor)
    if (seq !== loadSeq || sessionId.value !== target) return
    let next: ProcessState = {
      steps: processState.steps.map(step => ({ ...step })),
      cursor: processState.cursor,
      lastSeq: processState.lastSeq,
      streamId: processState.streamId,
      expanded: { ...processState.expanded },
    }
    for (const batch of after.batches ?? []) {
      next = applyProcessBatch(next, batch as ProcessBatch)
    }
    if ((after.batches ?? []).length === 0) {
      next = {
        ...next,
        cursor: typeof after.cursor === 'string' ? after.cursor : next.cursor,
        lastSeq: typeof after.watermark === 'string' ? after.watermark : next.lastSeq,
        streamId: typeof after.streamId === 'string' && after.streamId.trim()
          ? after.streamId
          : next.streamId,
      }
    }
    processProtocolEnabled.value = true
    assignProcessState(next)
  } catch (failure) {
    if (shouldResnapshotProcess(failure)) {
      await loadProcessSnapshot(seq, target)
      return
    }
    throw failure
  }
}

/** Real process projection after history; fixture path stays synthetic-only. */
async function loadProcessProjection(seq: number, target: string) {
  if (wantFixture.value || !target) return
  try {
    if (processProtocolEnabled.value && processState.cursor) {
      await catchUpProcess(seq, target)
    } else {
      await loadProcessSnapshot(seq, target)
    }
  } catch (failure) {
    if (processUnavailable(failure)) {
      if (!processProtocolEnabled.value) clearProcessPreview()
      return
    }
    // Keep chat usable; process is best-effort beside formal history.
  }
}

function assistantIds() {
  return messages.value.filter(message => message.senderKind === 'ASSISTANT').map(message => message.messageId)
}

function toObserve(): RunWatchObserve | null {
  const value = snapshot.value
  const target = sessionId.value
  if (!value || !target || value.sessionId !== target) return null
  return {
    sessionId: value.sessionId,
    runs: (value.runs ?? []).map(run => ({
      runId: run.runId,
      status: run.status,
      processingStatus: run.processingStatus,
    })),
    assistantMessageIds: assistantIds(),
  }
}

async function reloadForWatch(): Promise<RunWatchObserve | null> {
  const seq = loadSeq
  const target = sessionId.value
  if (!target) return null
  try {
    // Poll path must not resume/re-queue; RunWatch.apply owns the transition.
    await loadHistory(seq, false)
    if (seq !== loadSeq || sessionId.value !== target) return null
    return toObserve()
  } catch (failure) {
    if (failure instanceof ApiFailure && (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED')) {
      runWatch.stop()
      await failed(failure)
      return null
    }
    throw failure
  }
}

function clear() {
  ++loadSeq
  runWatch.stop()
  navigation.clear()
  clearHistory()
  capabilities.value = []
}

function clearHistory() {
  runWatch.stop()
  projectionSub.stop()
  Object.assign(textProjection, emptyTextProjectionState())
  sseStatus.value = 'idle'
  snapshot.value = null
  messages.value = []
  nextPageCursor.value = null
  if (!wantFixture.value) clearProcessPreview()
}

function syncRunWatch() {
  const observe = toObserve()
  if (observe) runWatch.resume(observe)
  else if (!sessionId.value) runWatch.stop()
}

function reason(code: string | null | undefined) {
  return code === 'EMPLOYEE_USE_REVOKED'
    ? '使用授权已撤销。你仍可查看本人已有的文字历史，无法发起新执行。'
    : code === 'RELEASE_DISABLED'
      ? '数字员工暂不可用。你仍可查看本人已有的文字历史。'
      : '当前能力暂不可用，请稍后刷新。'
}

async function failed(failure: unknown) {
  clearHistory()
  if (failure instanceof ApiFailure && failure.status === 401) {
    clear()
    busy.value = false
    await auth.restore().catch(() => undefined)
    await router.replace('/login')
  } else if (failure instanceof ApiFailure && failure.code === 'PASSWORD_CHANGE_REQUIRED') {
    clear()
    busy.value = false
    await router.replace('/password/initial')
  } else if (failure instanceof ApiFailure && failure.status === 404) {
    error.value = '该历史会话或内容不存在，或当前账号无权读取。'
  } else {
    error.value = '读取尚未完成，请刷新重试。'
  }
}

async function refresh(keepView = false) {
  const seq = ++loadSeq
  busy.value = true
  error.value = ''
  if (!keepView) { clearHistory(); capabilities.value = [] }
  navigation.clear()
  listError.value = ''
  try {
    const previousUserId = auth.identity?.userId
    await auth.restore()
    if (seq !== loadSeq) return
    if (previousUserId && previousUserId !== auth.identity?.userId) clearHistory()
    if (!auth.identity) {
      await router.replace('/login')
      return
    }
    if (auth.identity.authStage !== 'READY') {
      await router.replace('/password/initial')
      return
    }
    const nextCapabilities = await Promise.all(
      employees.value.map((x) =>
        read<Capability>(`/api/employees/${encodeURIComponent(x.employeeId)}/capabilities`),
      ),
    )
    if (seq !== loadSeq) return
    capabilities.value = nextCapabilities
    if (!employees.value.some(x => x.employeeId === selectedEmployeeId.value)) {
      selectedEmployeeId.value = employees.value[0]?.employeeId ?? ''
    }
    await searchSessions()
    if (seq !== loadSeq) return
    await loadHistory(seq)
  } catch (failure) {
    if (seq !== loadSeq) return
    await failed(failure)
  } finally {
    if (seq === loadSeq) busy.value = false
  }
}

async function loadHistory(seq: number, syncWatch = true) {
    const target = sessionId.value
    if (target) {
      const value = await read<Snapshot>(
        `${basePath(target)}/snapshot?protocolVersion=${protocol}`,
      )
      if (seq !== loadSeq) return
      if (value.protocolVersion !== protocol || value.sessionId !== target) {
        throw new ApiFailure(0, 'CONTENT_INVALID')
      }
      const rendered = await displayMessages(target, value.messages)
      if (seq !== loadSeq) return
      snapshot.value = value
      messages.value = rendered
      nextPageCursor.value = value.messages.nextPageCursor
      if (selectedEmployeeId.value !== value.session.employeeId) {
        selectedEmployeeId.value = value.session.employeeId
        await searchSessions()
      }
      if (seq === loadSeq) syncTextProjection(value, target)
      if (seq === loadSeq) await loadProcessProjection(seq, target)
      if (syncWatch && seq === loadSeq) syncRunWatch()
    } else if (syncWatch) {
      runWatch.stop()
      projectionSub.stop()
    }
}

async function selectHistory() {
  if (!capabilities.value.length) { await refresh(); return }
  const seq = ++loadSeq
  busy.value = true
  error.value = ''
  clearHistory()
  try {
    await loadHistory(seq)
  } catch (failure) {
    if (seq !== loadSeq) return
    await failed(failure)
  } finally {
    if (seq === loadSeq) busy.value = false
  }
}

async function older() {
  if (busy.value || !nextPageCursor.value) return
  busy.value = true
  error.value = ''
  const seq = loadSeq
  const target = sessionId.value
  try {
    const page = await read<Page>(
      `${basePath(target)}/messages?protocolVersion=${protocol}&pageCursor=${encodeURIComponent(nextPageCursor.value)}`,
    )
    const rendered = await displayMessages(target, page)
    if (seq !== loadSeq) return
    messages.value = [...rendered, ...messages.value]
    nextPageCursor.value = page.nextPageCursor
  } catch (failure) {
    if (seq !== loadSeq) return
    await failed(failure)
  } finally {
    if (seq === loadSeq) busy.value = false
  }
}

async function searchSessions(more = false) {
  listError.value = ''
  try {
    if (more) await navigation.more()
    else await navigation.search(employeeId.value, searchTitle.value)
  } catch (failure) {
    if (failure instanceof ApiFailure && (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED')) {
      await failed(failure)
    } else {
      listError.value = failure instanceof ApiFailure && failure.code === 'CURSOR_INVALID'
        ? '列表定位已失效，请重新搜索。' : '会话列表读取未完成，请重试。'
    }
  }
}

function guardComposition(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) event.preventDefault()
}

async function changeEmployee() {
  await router.replace({ path: '/workbench', query: {} })
  await searchSessions()
}

async function logout() {
  if (busy.value) return
  busy.value = true
  clear()
  try {
    await auth.logout()
    await router.replace('/login')
  } catch {
    error.value = '退出尚未确认，请稍后重试。'
  } finally {
    busy.value = false
  }
}

async function accepted(receipt: Receipt) {
  if (!receipt.result) return
  searchTitle.value = ''
  if (sessionId.value !== receipt.result.sessionId) {
    await router.replace({ path: '/workbench', query: { sessionId: receipt.result.sessionId } })
  }
  await refresh(true)
  runWatch.start(receipt.result.sessionId, receipt.result.runId, assistantIds())
}

function newChat() {
  if (sessionId.value) {
    void router.replace({ path: '/workbench', query: {} })
    return
  }
  error.value = ''
}

function openRename(target: RenameTarget) {
  headerMenuOpen.value = false
  renameTarget.value = target
  renameOpen.value = true
}

function openRenameFromHeader() {
  if (!sessionId.value || !snapshot.value) return
  const listed = navigation.items.find(item => item.sessionId === sessionId.value)
  const version = listed?.sessionVersion || snapshot.value.session.sessionVersion
  if (!version) {
    error.value = '当前会话版本尚未就绪，请刷新后再改名。'
    return
  }
  openRename({
    sessionId: sessionId.value,
    title: snapshot.value.session.title ?? listed?.title ?? null,
    sessionVersion: version,
  })
}

function openRenameFromList(session: SessionSummary, event: Event) {
  event.preventDefault()
  event.stopPropagation()
  openRename({
    sessionId: session.sessionId,
    title: session.title,
    sessionVersion: session.sessionVersion,
  })
}

function onRenamed(summary: SessionSummary) {
  navigation.applySummary(summary)
  if (snapshot.value && snapshot.value.sessionId === summary.sessionId) {
    snapshot.value = {
      ...snapshot.value,
      session: {
        ...snapshot.value.session,
        title: summary.title ?? snapshot.value.session.title,
        sessionVersion: summary.sessionVersion,
      },
    }
  }
}

function closeRename() {
  renameOpen.value = false
  renameTarget.value = null
}

function onVisibilityRefresh() {
  if (document.visibilityState === 'visible') void refresh(true)
}

onMounted(() => {
  syncProcessFixture()
  void refresh()
  document.addEventListener('visibilitychange', onVisibilityRefresh)
})
onUnmounted(() => {
  clear()
  document.removeEventListener('visibilitychange', onVisibilityRefresh)
})
watch(sessionId, () => {
  runWatch.stop()
  projectionSub.stop()
  void selectHistory()
})
watch(wantFixture, () => {
  syncProcessFixture()
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" aria-label="会话导航">
      <div class="brand">
        <div class="brand-icon" aria-hidden="true">✦</div>
        <div>
          <b>海卓智慧大脑</b>
          <small>HAIZHUO AI WORKBENCH</small>
        </div>
      </div>

      <button class="new-chat" type="button" :disabled="busy" @click="newChat">＋ 新建对话</button>
      <label v-if="employees.length > 1" class="employee-select">
        数字员工
        <select v-model="selectedEmployeeId" @change="changeEmployee">
          <option v-for="employee in employees" :key="employee.employeeId" :value="employee.employeeId">{{ employee.displayName }}</option>
        </select>
      </label>
      <form @submit.prevent="searchSessions()" @keydown.enter="guardComposition">
        <input
        v-model="searchTitle"
        class="search"
        type="search"
        placeholder="搜索会话标题"
        aria-label="搜索会话标题"
        maxlength="400"
      />
        <button class="quiet" type="submit" :disabled="!employeeId">搜索</button>
      </form>

      <p class="section-caption">最近会话</p>
      <div class="session-list" aria-live="polite">
        <p v-if="listError" class="empty-sessions" role="alert">{{ listError }}</p>
        <p v-if="navigation.busy" class="empty-sessions muted" role="status">正在读取会话…</p>
        <p v-else-if="!navigation.items.length && !listError" class="empty-sessions muted">{{ searchTitle.trim() ? '未找到匹配标题的会话。' : '暂无会话。' }}</p>
        <div
          v-for="session in navigation.items"
          :key="session.sessionId"
          class="session-row"
          :class="{ selected: sessionId === session.sessionId }"
        >
          <button
            type="button"
            class="session-item"
            :aria-current="sessionId === session.sessionId ? 'page' : undefined"
            :title="session.title || '未命名会话'"
            @click="router.push({ path: '/workbench', query: { sessionId: session.sessionId } })"
          >
            {{ session.title || '未命名会话' }}
          </button>
          <button
            type="button"
            class="session-rename"
            aria-label="重命名会话"
            title="重命名"
            @click="openRenameFromList(session, $event)"
          >
            ···
          </button>
        </div>
        <button v-if="navigation.nextPageCursor" type="button" class="quiet" :disabled="navigation.busy" @click="searchSessions(true)">加载更多会话</button>
        <button v-if="listError" type="button" class="quiet" @click="searchSessions()">重试列表</button>
      </div>

      <div class="sidebar-bottom">
        <span class="avatar" aria-hidden="true">{{ avatarText }}</span>
        <span class="user-meta">
          {{ auth.identity?.displayName }}
          <small class="muted">{{ employeeLabel }}</small>
        </span>
        <button class="quiet" type="button" :disabled="busy" @click="logout">退出登录</button>
      </div>
    </aside>

    <main class="workbench" aria-label="工作台">
      <header class="head">
        <div>
          <h1>{{ titleText }}</h1>
          <p>{{ subtitleText }}</p>
        </div>
        <div v-if="sessionId && snapshot" class="more-wrap">
          <button
            class="more"
            type="button"
            aria-label="当前会话更多操作"
            aria-haspopup="menu"
            :aria-expanded="headerMenuOpen"
            @click="headerMenuOpen = !headerMenuOpen"
          >
            ···
          </button>
          <div v-if="headerMenuOpen" class="more-menu" role="menu">
            <button type="button" role="menuitem" @click="openRenameFromHeader">重命名</button>
          </div>
        </div>
      </header>

      <div class="content">
        <p v-if="error" class="alert" role="alert">{{ error }}</p>
        <p v-if="busy" class="muted status-line" role="status">正在读取当前身份与历史…</p>

        <section v-if="snapshot" class="chat" aria-label="正式文字历史">
          <p class="history-note">
            正式文字记录
            <span
              v-if="sseStatus !== 'idle'"
              class="sse-badge"
              :data-sse="sseStatus"
              role="status"
            >SSE {{ sseStatusLabel }}</span>
          </p>
          <p v-if="!hideRunWatchBanner && (runWatch.notice || snapshotRunActive)" class="readonly-note" role="status">
            {{ runWatch.notice || (snapshotRunActive ? '消息已接纳，等待执行。' : '') }}
          </p>
          <p
            v-if="historyCapability?.unavailableReason"
            class="readonly-note"
            role="status"
          >
            {{ reason(historyCapability.unavailableReason) }}
          </p>
          <ElButton v-if="nextPageCursor" :disabled="busy" @click="older">查看更早消息</ElButton>
          <article
            v-for="message in messages"
            :key="message.messageId"
            class="formal-message"
            :class="message.senderKind.toLowerCase()"
          >
            <template v-for="(text, index) in message.texts" :key="index">
              <MarkdownBody
                v-if="message.senderKind === 'ASSISTANT' && (text.bodyFormat === 'markdown-v1' || text.bodyFormat === 'plain-text')"
                :source="text.body"
              />
              <p v-else class="message-text">{{ text.body }}</p>
            </template>
          </article>
          <!-- Variant A: user bubbles (formal) → process block → streaming formal-reply preview. -->
          <ProcessTimeline
            v-if="processProtocolEnabled && (runActive || processState.steps.length || wantFixture)"
            :steps="processState.steps"
            :synthetic="wantFixture"
            :placeholder="processPlaceholderNotice({
              hasProcessSteps: processState.steps.length > 0,
              runWatchNotice: runWatch.notice,
              snapshotWaiting: snapshotRunActive,
            })"
          />
          <article
            v-for="preview in previewList"
            :key="'preview-' + preview.contentId"
            class="formal-message assistant preview"
            aria-label="正式回复预览"
          >
            <span class="preview-live">生成中</span>
            <MarkdownBody :source="preview.body" />
            <span class="preview-caret" aria-hidden="true">▍</span>
          </article>
          <p v-if="!messages.length && !previewList.length" class="muted">此会话还没有可读取的正式文字消息。</p>
        </section>

        <div v-else-if="!busy && !error" class="workbench-welcome">
          <span class="welcome-mark" aria-hidden="true">✦</span>
          <h2>{{ auth.identity?.displayName }}，欢迎回来</h2>
          <p class="muted">
            从左侧新建对话开始整理思路。<br />
            从左侧选择已有会话，可查看文字历史。
          </p>
        </div>
      </div>
      <TextComposer v-if="auth.identity && employeeId"
        :user-id="auth.identity.userId" :employee-id="snapshot?.session.employeeId || employeeId"
        :session-id="sessionId || null" :available="textInputAvailable" :can-send="canSendText"
        :run-active="runActive"
        @accepted="accepted" @auth-required="failed" />
    </main>
    <RenameSessionDialog
      :open="renameOpen"
      :target="renameTarget"
      @close="closeRename"
      @renamed="onRenamed"
      @auth-required="failed"
    />
  </div>
</template>

<style scoped>
.app-shell {
  --brand: #6557db;
  --line: #e9e8f2;
  --muted: #9495aa;
  --soft: #f0edfc;
  height: 100dvh;
  display: flex;
  min-height: 0;
  color: #292c45;
  background: #f8f9fd;
  overflow: hidden;
}
.sidebar {
  width: 230px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  padding: 24px 14px 15px;
  background: #fcfcff;
  border-right: 1px solid var(--line);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 9px 24px;
}
.brand-icon {
  background: linear-gradient(135deg, #8978ef, #5847c9);
  color: white;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  font-size: 14px;
}
.brand b {
  display: block;
  font-size: 13px;
  font-weight: 650;
}
.brand small {
  display: block;
  margin-top: 3px;
  font-size: 9px;
  letter-spacing: 0.4px;
  color: var(--muted);
}
.new-chat {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: white;
  color: var(--brand);
  font-weight: 600;
  padding: 10px 12px;
  cursor: pointer;
}
.new-chat:hover:enabled {
  background: #f6f3ff;
  border-color: #bdb2f2;
}
.new-chat:disabled {
  opacity: 0.45;
  cursor: default;
}
.search {
  width: 100%;
  margin-top: 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 9px 11px;
  background: #f7f6fb;
  color: #292c45;
}
.employee-select { display: grid; gap: 5px; font-size: 12px; margin-top: 12px; }
.employee-select select { width: 100%; padding: 6px; }
.session-row {
  display: flex; align-items: stretch; gap: 2px; margin-bottom: 3px; border-radius: 8px;
}
.session-row.selected { background: #ece7fb; }
.session-row:hover { background: #f2effc; }
.session-item {
  flex: 1; min-width: 0; border: 0; border-radius: 8px; background: transparent;
  padding: 11px 9px; text-align: left; color: inherit;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer;
}
.session-row.selected .session-item { color: var(--brand); }
.session-item:focus-visible { outline: 2px solid var(--brand); outline-offset: -2px; }
.session-rename {
  flex-shrink: 0; width: 28px; border: 0; border-radius: 8px; background: transparent;
  color: var(--muted); cursor: pointer; font-size: 14px; line-height: 1;
}
.session-rename:hover { color: var(--brand); background: #e8e3fa; }
.section-caption {
  margin: 18px 9px 8px;
  font-size: 11px;
  color: var(--muted);
}
.session-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 0 2px;
}
.empty-sessions {
  margin: 8px 9px;
  font-size: 12px;
  line-height: 1.7;
}
.sidebar-bottom {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 6px 2px;
  border-top: 1px solid var(--line);
  margin-top: 10px;
}
.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--soft);
  color: var(--brand);
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 650;
  flex-shrink: 0;
}
.user-meta {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.35;
}
.user-meta small {
  display: block;
  margin-top: 3px;
  font-size: 10px;
}
.quiet {
  border: 0;
  background: transparent;
  color: #817b99;
  font-size: 11px;
  padding: 4px 6px;
  cursor: pointer;
  flex-shrink: 0;
}
.quiet:hover:enabled {
  color: var(--brand);
}
.quiet:disabled {
  opacity: 0.45;
  cursor: default;
}
.workbench {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #fdfdff, #f7f8fc);
}
.head {
  height: 66px;
  padding: 0 29px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
  border-bottom: 1px solid var(--line);
  background: #ffffffba;
}
.head h1 {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}
.head p {
  font-size: 10px;
  color: var(--muted);
  margin: 6px 0 0;
}
.more-wrap { position: relative; }
.more {
  border: 1px solid var(--line);
  border-radius: 9px;
  background: white;
  color: var(--muted);
  padding: 6px 10px;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.more-menu {
  position: absolute; right: 0; top: calc(100% + 4px); z-index: 5;
  min-width: 120px; padding: 4px; border: 1px solid var(--line); border-radius: 10px;
  background: #fff; box-shadow: 0 10px 24px rgba(50, 40, 90, 0.12);
}
.more-menu button {
  display: block; width: 100%; border: 0; border-radius: 7px; background: transparent;
  text-align: left; padding: 8px 10px; cursor: pointer; color: #292c45; font: inherit;
}
.more-menu button:hover { background: #f2effc; color: var(--brand); }
.content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 27px 34px 24px;
}
.status-line {
  margin: 0 0 16px;
}
.alert {
  font-size: 13px;
  line-height: 1.7;
  color: #9c3838;
  background: #fcf1ef;
  border-radius: 8px;
  padding: 10px 12px;
  margin: 0 0 16px;
}
.history-note {
  font-size: 12px;
  color: var(--muted);
  margin: 0 0 16px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.sse-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #e8e4ff;
  color: #4338ca;
}
.sse-badge[data-sse="live"] { background: #d1fae5; color: #047857; }
.sse-badge[data-sse="degraded_poll"] { background: #fef3c7; color: #b45309; }
.sse-badge[data-sse="paused"] { background: #fee2e2; color: #b91c1c; }
.formal-message.preview {
  position: relative;
  line-height: 1.85;
}
.preview-live {
  position: absolute;
  top: -6px;
  left: 0;
  font-size: 11px;
  color: #7c3aed;
}
.preview-caret {
  animation: preview-blink 1s step-end infinite;
}
@keyframes preview-blink { 50% { opacity: 0; } }
.readonly-note {
  background: #fff5e0;
  color: #795d21;
  border-radius: 8px;
  padding: 14px;
  line-height: 1.8;
  font-size: 13px;
  margin-bottom: 16px;
}
.chat {
  max-width: 720px;
  width: 100%;
  margin-inline: auto;
}
.formal-message {
  margin: 22px 0;
}
.formal-message.user {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.message-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 15px;
  line-height: 1.9;
  margin: 0;
}
.user .message-text {
  width: fit-content;
  max-width: 70%;
  background: #ece8ff;
  border: 1px solid #d9d2f8;
  border-radius: 18px;
  padding: 10px 16px;
  color: #292c45;
}
.formal-message.assistant .message-text {
  background: transparent;
  border: 0;
  border-radius: 0;
  padding: 0;
}
.workbench-welcome {
  text-align: center;
  padding: 100px 12px;
}
.welcome-mark {
  display: inline-grid;
  place-items: center;
  background: linear-gradient(135deg, #8978ef, #5847c9);
  color: white;
  width: 64px;
  height: 64px;
  border-radius: 20px;
  font-size: 28px;
}
.workbench-welcome h2 {
  font-size: 24px;
  font-weight: 500;
  margin-top: 28px;
}
.muted {
  color: var(--muted);
  font-size: 14px;
  line-height: 1.9;
}
@media (max-width: 760px) {
  .app-shell {
    flex-direction: column;
    height: auto;
    min-height: 100dvh;
    overflow: auto;
  }
  .sidebar {
    width: 100%;
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
  .session-list {
    max-height: 120px;
  }
  .content {
    padding: 20px 16px 24px;
  }
  .head {
    padding: 0 16px;
  }
  .user .message-text {
    max-width: min(70%, calc(100% - 12px));
  }
  .workbench-welcome {
    padding: 48px 0;
  }
}
</style>
