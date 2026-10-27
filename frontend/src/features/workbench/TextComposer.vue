<script setup lang="ts">
import { onUnmounted, reactive, watch } from 'vue'
import { ApiFailure } from '../../transport/http'
import { TextComposerState, type Receipt } from './composer'

const props = defineProps<{ userId: string; employeeId: string; sessionId: string | null; available: boolean; canSend: boolean }>()
const emit = defineEmits<{ accepted: [receipt: Receipt]; authRequired: [failure: ApiFailure]; pending: [value: boolean] }>()
const state = reactive(new TextComposerState())
let composing = false
let compositionEndedAt = 0
async function operate(action: () => Promise<Receipt | undefined>) {
  try {
    const receipt = await action()
    if (receipt) emit('accepted', receipt)
  } catch (failure) {
    if (failure instanceof ApiFailure && (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED')) { state.dispose(); emit('authRequired', failure) }
  }
}
watch(() => [props.userId, props.employeeId, props.sessionId] as const, () => {
  if (!props.userId || !props.employeeId) { state.dispose(); return }
  void operate(() => state.open({ userId: props.userId, employeeId: props.employeeId, sessionId: props.sessionId }))
}, { immediate: true })
watch(() => state.pending, value => emit('pending', value))
onUnmounted(() => state.dispose())
function compositionEnd() { composing = false; compositionEndedAt = performance.now() }
function send() { if (props.canSend) void operate(() => state.send()) }
function keydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey) return
  if (composing || event.isComposing || event.keyCode === 229 || performance.now() - compositionEndedAt < 50) return
  event.preventDefault()
  send()
}
</script>

<template>
  <section v-if="state.draft && (available || state.pending || state.draft.text)" class="composer" aria-label="文字发送">
    <p v-if="state.storageWarning" class="composer-warning" role="status">{{ state.storageWarning }}</p>
    <p v-if="state.notice" class="composer-notice" role="status">{{ state.notice }}</p>
    <textarea v-model="state.draft.text" aria-label="输入消息" rows="3" placeholder="输入文字，Enter 发送，Shift+Enter 换行"
      :readonly="state.pending || state.busy"
      @input="state.persist()" @keydown="keydown" @compositionstart="composing = true"
      @compositionend="compositionEnd" />
    <div class="composer-actions">
      <template v-if="state.pending">
        <button type="button" :disabled="state.busy" @click="operate(() => state.lookup())">回查原提交</button>
        <button type="button" :disabled="state.busy" @click="operate(() => state.close())">封闭原提交后编辑</button>
      </template>
      <button v-else type="button" :disabled="!canSend || state.busy || !state.draft.text.trim()" @click="send">
        {{ state.busy ? '正在接纳…' : '发送' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.composer { flex-shrink: 0; margin: 0 28px 22px; padding: 12px; background: white; border: 1px solid #ded9ef; border-radius: 12px; }
textarea { box-sizing: border-box; width: 100%; resize: vertical; max-height: 240px; border: 0; outline: none; font: inherit; line-height: 1.7; color: #292c45; background: transparent; }
textarea:focus-visible { outline: 2px solid #8172dc; outline-offset: 2px; border-radius: 3px; }
.composer-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
button { border: 1px solid #d9d2f8; border-radius: 7px; padding: 7px 14px; background: #6557db; color: white; cursor: pointer; }
button:disabled { opacity: .45; cursor: default; }
.composer-notice, .composer-warning { margin: 0 0 8px; font-size: 13px; line-height: 1.6; }
.composer-warning { color: #8c5709; }
@media(max-width:760px) { .composer { margin: 0 16px 18px; } }
</style>
