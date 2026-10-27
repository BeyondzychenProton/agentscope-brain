<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import { useAuth } from '../features/auth/store'
import { ApiFailure, read } from '../transport/http'
import {
  basePath,
  displayMessages,
  protocol,
  type Capability,
  type DisplayMessage,
  type Page,
  type Snapshot,
} from '../features/workbench/history'

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
const sessionId = computed(() => (typeof route.query.sessionId === 'string' ? route.query.sessionId : ''))
const employees = computed(() => auth.identity?.employees ?? [])
const historyCapability = computed(() =>
  capabilities.value.find((x) => x.employeeId === snapshot.value?.session.employeeId),
)
const employeeLabel = computed(() => employees.value[0]?.displayName ?? '数字员工')
const titleText = computed(() => (sessionId.value ? snapshot.value?.session.title || '会话' : '新对话'))
const subtitleText = computed(() =>
  sessionId.value
    ? `${employeeLabel.value} · 只读文字历史`
    : `${employeeLabel.value} · 可以开始整理思路`,
)
const avatarText = computed(() => (auth.identity?.displayName ?? '用').slice(0, 1))

function clear() {
  capabilities.value = []
  snapshot.value = null
  messages.value = []
  nextPageCursor.value = null
}

function reason(code: string | null | undefined) {
  return code === 'EMPLOYEE_USE_REVOKED'
    ? '使用授权已撤销。你仍可查看本人已有的文字历史，无法发起新执行。'
    : code === 'RELEASE_DISABLED'
      ? '数字员工暂不可用。你仍可查看本人已有的文字历史。'
      : '当前能力暂不可用，请稍后刷新。'
}

async function failed(failure: unknown) {
  clear()
  if (failure instanceof ApiFailure && failure.status === 401) {
    await auth.restore().catch(() => undefined)
    await router.replace('/login')
  } else if (failure instanceof ApiFailure && failure.status === 404) {
    error.value = '该历史会话或内容不存在，或当前账号无权读取。'
  } else {
    error.value = '读取尚未完成，请刷新重试。'
  }
}

async function refresh() {
  const seq = ++loadSeq
  busy.value = true
  error.value = ''
  clear()
  try {
    await auth.restore()
    if (seq !== loadSeq) return
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
    if (sessionId.value) {
      const value = await read<Snapshot>(
        `${basePath(sessionId.value)}/snapshot?protocolVersion=${protocol}`,
      )
      if (seq !== loadSeq) return
      if (value.protocolVersion !== protocol || value.sessionId !== sessionId.value) {
        throw new ApiFailure(0, 'CONTENT_INVALID')
      }
      const rendered = await displayMessages(sessionId.value, value.messages)
      if (seq !== loadSeq) return
      snapshot.value = value
      messages.value = rendered
      nextPageCursor.value = value.messages.nextPageCursor
    }
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
  try {
    const page = await read<Page>(
      `${basePath(sessionId.value)}/messages?protocolVersion=${protocol}&pageCursor=${encodeURIComponent(nextPageCursor.value)}`,
    )
    const rendered = await displayMessages(sessionId.value, page)
    messages.value = [...rendered, ...messages.value]
    nextPageCursor.value = page.nextPageCursor
  } catch (failure) {
    await failed(failure)
  } finally {
    busy.value = false
  }
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

function newChat() {
  if (sessionId.value) {
    void router.replace({ path: '/workbench', query: {} })
    return
  }
  error.value = ''
}

onMounted(() => {
  void refresh()
  window.addEventListener('focus', refresh)
})
onUnmounted(() => {
  clear()
  window.removeEventListener('focus', refresh)
})
watch(sessionId, () => {
  void refresh()
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
      <input
        class="search"
        type="search"
        disabled
        placeholder="搜索会话标题"
        aria-label="按会话标题搜索（即将开放）"
        title="会话搜索将逐步开放"
      />

      <p class="section-caption">最近会话</p>
      <div class="session-list" aria-live="polite">
        <p class="empty-sessions muted">暂无会话。正式列表将逐步开放。</p>
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
        <button
          class="more"
          type="button"
          disabled
          aria-label="当前会话更多操作（即将开放）"
          title="改名等操作将逐步开放"
        >
          ···
        </button>
      </header>

      <div class="content">
        <p v-if="error" class="alert" role="alert">{{ error }}</p>
        <p v-if="busy" class="muted status-line" role="status">正在读取当前身份与历史…</p>

        <section v-if="snapshot" class="chat" aria-label="正式文字历史">
          <p class="history-note">已有文字历史 · 只读查看</p>
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
            <p class="message-author">
              {{ message.senderKind === 'USER' ? '我' : employeeLabel }}
            </p>
            <p v-for="(text, index) in message.texts" :key="index" class="message-text">
              {{ text }}
            </p>
          </article>
          <p v-if="!messages.length" class="muted">此会话还没有可读取的正式文字消息。</p>
        </section>

        <div v-else-if="!busy && !error" class="workbench-welcome">
          <span class="welcome-mark" aria-hidden="true">✦</span>
          <h2>{{ auth.identity?.displayName }}，欢迎回来</h2>
          <p class="muted">
            从左侧新建对话开始整理思路。<br />
            已有文字历史可通过会话链接打开。
          </p>
        </div>
      </div>
    </main>
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
  color: var(--muted);
}
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
.more {
  border: 1px solid var(--line);
  border-radius: 9px;
  background: white;
  color: var(--muted);
  padding: 6px 10px;
  font-size: 14px;
  line-height: 1;
}
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
}
.readonly-note {
  background: #fff5e0;
  color: #795d21;
  border-radius: 8px;
  padding: 14px;
  line-height: 1.8;
  font-size: 13px;
  margin-bottom: 16px;
}
.formal-message {
  margin: 22px 0;
  max-width: 760px;
}
.formal-message.user {
  margin-left: auto;
}
.message-author {
  font-size: 12px;
  font-weight: 600;
  color: #817b99;
  margin: 0 0 6px;
}
.user .message-author {
  text-align: right;
}
.message-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 15px;
  line-height: 1.9;
  background: white;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 14px 18px;
  margin: 0;
}
.user .message-text {
  background: #ece8ff;
  border-color: #d9d2f8;
  color: #292c45;
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
  .formal-message.user {
    margin-left: 12px;
  }
  .workbench-welcome {
    padding: 48px 0;
  }
}
</style>
