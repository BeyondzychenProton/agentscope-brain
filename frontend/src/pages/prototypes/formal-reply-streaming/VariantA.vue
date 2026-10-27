<script setup lang="ts">
/** PROTOTYPE variant A — Classic chat column: user → live process block → growing reply. */
import type { DemoState } from './demoStream'

defineProps<{ state: DemoState; onPlay: () => void; onReset: () => void }>()
</script>

<template>
  <div class="va">
    <header class="va-bar">
      <strong>会议室助手</strong>
      <span class="va-sse" :data-sse="state.sse">SSE {{ state.sse }}</span>
    </header>
    <div class="va-chat">
      <article class="va-user">帮我约 11 月 15 日的会议室，改到第一个工作日晚上 7 点，18 楼。</article>

      <section v-if="state.processSteps.length" class="va-process">
        <div class="va-process-h">执行过程</div>
        <ol>
          <li v-for="s in state.processSteps" :key="s.stepId">
            <span class="chip">{{ s.kind === 'STAGE' ? '阶段' : '工具' }}</span>
            {{ s.title }}
            <em :data-st="s.status">{{ s.status === 'RUNNING' ? '进行中' : '完成' }}</em>
            <small v-if="s.summary">{{ s.summary }}</small>
          </li>
        </ol>
      </section>
      <p v-else-if="state.phase !== 'idle'" class="va-ph">执行中…</p>

      <article v-if="state.previewText" class="va-as" :data-committed="state.committed">
        <span v-if="!state.committed" class="live">生成中</span>
        <div class="body">{{ state.previewText }}<span v-if="!state.committed" class="caret">▍</span></div>
      </article>
    </div>
    <footer class="va-actions">
      <button type="button" @click="onPlay">播放完整过程</button>
      <button type="button" class="ghost" @click="onReset">重置</button>
    </footer>
    <pre class="va-state">{{ JSON.stringify(state, null, 2) }}</pre>
  </div>
</template>

<style scoped>
.va { max-width: 720px; margin: 0 auto; padding: 16px 16px 96px; font-family: "Segoe UI", system-ui, sans-serif; color: #292c45; }
.va-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.va-sse { font-size: 12px; padding: 4px 10px; border-radius: 999px; background: #e8e4ff; }
.va-sse[data-sse="live"] { background: #d1fae5; }
.va-chat { display: flex; flex-direction: column; gap: 14px; min-height: 360px; }
.va-user { align-self: flex-end; max-width: 70%; background: #ece8ff; border: 1px solid #d9d2f8; border-radius: 18px; padding: 10px 16px; line-height: 1.6; }
.va-process { border: 1px solid #ddd6fe; border-radius: 12px; padding: 12px 14px; background: #faf8ff; }
.va-process-h { font-weight: 600; margin-bottom: 8px; }
.va-process ol { margin: 0; padding-left: 0; list-style: none; display: grid; gap: 8px; }
.va-process li { display: grid; grid-template-columns: auto 1fr auto; gap: 6px 8px; font-size: 14px; align-items: baseline; }
.va-process small { grid-column: 2 / -1; color: #6b7280; }
.chip { font-size: 11px; background: #ede9fe; padding: 2px 6px; border-radius: 6px; }
em { font-style: normal; font-size: 12px; color: #059669; }
em[data-st="RUNNING"] { color: #d97706; }
.va-ph { color: #6b7280; font-size: 14px; }
.va-as { position: relative; line-height: 1.85; white-space: pre-wrap; }
.va-as .live { position: absolute; top: -8px; left: 0; font-size: 11px; color: #7c3aed; }
.va-as[data-committed="true"] .live { display: none; }
.caret { animation: blink 1s step-end infinite; }
@keyframes blink { 50% { opacity: 0; } }
.va-actions { display: flex; gap: 8px; margin-top: 18px; }
.va-actions button { border: 0; background: #5847c9; color: #fff; padding: 8px 14px; border-radius: 10px; cursor: pointer; }
.va-actions .ghost { background: #e5e7eb; color: #111; }
.va-state { margin-top: 16px; font-size: 11px; background: #111827; color: #e5e7eb; padding: 12px; border-radius: 10px; overflow: auto; max-height: 180px; }
</style>
