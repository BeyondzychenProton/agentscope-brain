<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiFailure } from '../../transport/http'
import {
  renameFailureMessage,
  renameSession,
  validateTitle,
  type RenameTarget,
} from './sessionTitle'
import type { SessionSummary } from './sessions'

const props = defineProps<{ open: boolean; target: RenameTarget | null }>()
const emit = defineEmits<{
  close: []
  renamed: [summary: SessionSummary]
  authRequired: [failure: ApiFailure]
}>()

const draft = ref('')
const notice = ref('')
const busy = ref(false)
let requestKey = ''

watch(() => props.open, (open) => {
  if (open && props.target) {
    draft.value = props.target.title ?? ''
    notice.value = ''
    busy.value = false
    requestKey = crypto.randomUUID()
  }
})

async function submit() {
  if (!props.target || busy.value) return
  const invalid = validateTitle(draft.value)
  if (invalid) {
    notice.value = invalid
    return
  }
  busy.value = true
  notice.value = ''
  try {
    const summary = await renameSession(props.target, draft.value, requestKey)
    emit('renamed', summary)
    emit('close')
  } catch (failure) {
    if (failure instanceof ApiFailure && (failure.status === 401 || failure.code === 'PASSWORD_CHANGE_REQUIRED')) {
      emit('authRequired', failure)
      emit('close')
      return
    }
    notice.value = renameFailureMessage(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div v-if="open && target" class="rename-backdrop" role="presentation" @click.self="!busy && emit('close')">
    <div class="rename-dialog" role="dialog" aria-modal="true" aria-labelledby="rename-title">
      <h2 id="rename-title">重命名会话</h2>
      <p class="hint">标题最多 200 字；列表里过长的标题会截断显示，不改变已保存内容。</p>
      <label class="field">
        会话标题
        <input
          v-model="draft"
          type="text"
          maxlength="200"
          :disabled="busy"
          aria-label="会话标题"
          @keydown.enter.prevent="submit"
        />
      </label>
      <p v-if="notice" class="error" role="alert">{{ notice }}</p>
      <div class="actions">
        <button type="button" class="ghost" :disabled="busy" @click="emit('close')">取消</button>
        <button type="button" class="primary" :disabled="busy" @click="submit">
          {{ busy ? '保存中…' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rename-backdrop {
  position: fixed; inset: 0; z-index: 40;
  display: grid; place-items: center;
  background: rgba(28, 24, 48, 0.35);
  padding: 16px;
}
.rename-dialog {
  width: min(420px, 100%);
  background: #fff;
  border: 1px solid #e4dff4;
  border-radius: 14px;
  padding: 20px 20px 16px;
  box-shadow: 0 18px 40px rgba(60, 48, 110, 0.18);
}
h2 { margin: 0 0 6px; font-size: 18px; font-weight: 600; color: #292c45; }
.hint { margin: 0 0 14px; font-size: 13px; line-height: 1.5; color: #8a879e; }
.field { display: grid; gap: 6px; font-size: 13px; color: #5c5f78; }
input {
  border: 1px solid #d9d2f8; border-radius: 8px; padding: 9px 11px;
  font: inherit; color: #292c45;
}
input:focus-visible { outline: 2px solid #8172dc; outline-offset: 1px; }
.error { margin: 10px 0 0; font-size: 13px; color: #a13d3d; }
.actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
button {
  border-radius: 8px; padding: 7px 14px; font: inherit; cursor: pointer;
}
button:disabled { opacity: 0.45; cursor: default; }
.ghost { border: 1px solid #ddd8ec; background: #fff; color: #5c5f78; }
.primary { border: 1px solid #6557db; background: #6557db; color: #fff; }
</style>
