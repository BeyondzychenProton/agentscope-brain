<script setup lang="ts">
/** PROTOTYPE variant B — Split rail: live process dock left, streaming reply right. */
import type { DemoState } from './demoStream'

defineProps<{ state: DemoState; onPlay: () => void; onReset: () => void }>()
</script>

<template>
  <div class="vb">
    <div class="vb-top">
      <div>
        <h1>流式工作台 · 分栏</h1>
        <p class="sub">过程轨与正文轨分离，SSE 状态挂在过程轨顶。</p>
      </div>
      <div class="vb-actions">
        <button type="button" @click="onPlay">播放</button>
        <button type="button" class="ghost" @click="onReset">重置</button>
      </div>
    </div>

    <div class="vb-grid">
      <aside class="vb-rail">
        <div class="pipe" :data-sse="state.sse">
          <span class="dot" />
          {{ state.sse === 'live' ? '投影流已连接' : state.sse === 'connecting' ? '连接中…' : '已降级轮询' }}
          <code>seq {{ state.seq }}</code>
        </div>
        <h2>本轮过程</h2>
        <ul>
          <li v-for="s in state.processSteps" :key="s.stepId" :data-st="s.status">
            <strong>{{ s.title }}</strong>
            <span>{{ s.kind }} · {{ s.status }}</span>
            <p v-if="s.summary">{{ s.summary }}</p>
          </li>
          <li v-if="!state.processSteps.length" class="empty">等待 Run 投影…</li>
        </ul>
      </aside>

      <main class="vb-main">
        <div class="bubble you">帮我约 11 月 15 日的会议室，改到第一个工作日晚上 7 点，18 楼。</div>
        <div v-if="state.previewText" class="bubble bot" :class="{ preview: !state.committed }">
          <header v-if="!state.committed">预览 · TEXT_APPEND</header>
          <header v-else>正式回复 · MESSAGE_COMMIT</header>
          <div class="md">{{ state.previewText }}</div>
        </div>
        <div v-else class="waiting">{{ state.note }}</div>
      </main>
    </div>

    <pre class="vb-state">{{ JSON.stringify(state, null, 2) }}</pre>
  </div>
</template>

<style scoped>
.vb { padding: 20px 20px 100px; font-family: Georgia, "Songti SC", serif; color: #1f2937; background: linear-gradient(180deg, #f8fafc, #eef2ff); min-height: 100vh; }
.vb-top { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; max-width: 1100px; margin: 0 auto 16px; }
h1 { font-size: 22px; margin: 0 0 4px; font-weight: 600; }
.sub { margin: 0; color: #6b7280; font-family: system-ui, sans-serif; font-size: 13px; }
.vb-actions { display: flex; gap: 8px; font-family: system-ui, sans-serif; }
.vb-actions button { border: 0; background: #0f172a; color: #fff; padding: 8px 12px; border-radius: 8px; cursor: pointer; }
.vb-actions .ghost { background: #cbd5e1; color: #0f172a; }
.vb-grid { max-width: 1100px; margin: 0 auto; display: grid; grid-template-columns: 280px 1fr; gap: 16px; min-height: 420px; }
.vb-rail { background: #0f172a; color: #e2e8f0; border-radius: 16px; padding: 14px; font-family: ui-monospace, monospace; font-size: 12px; }
.pipe { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; padding: 8px; border-radius: 10px; background: #1e293b; }
.pipe .dot { width: 8px; height: 8px; border-radius: 50%; background: #94a3b8; }
.pipe[data-sse="live"] .dot { background: #34d399; box-shadow: 0 0 0 4px rgba(52, 211, 153, 0.25); }
.pipe code { margin-left: auto; opacity: 0.7; }
.vb-rail h2 { font-size: 13px; margin: 0 0 10px; font-family: system-ui, sans-serif; letter-spacing: 0.04em; text-transform: uppercase; opacity: 0.7; }
.vb-rail ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
.vb-rail li { border-left: 2px solid #334155; padding-left: 10px; }
.vb-rail li[data-st="RUNNING"] { border-color: #fbbf24; }
.vb-rail li[data-st="DONE"] { border-color: #34d399; }
.vb-rail strong { display: block; font-family: system-ui, sans-serif; font-size: 13px; }
.vb-rail .empty { opacity: 0.5; }
.vb-main { background: #fff; border-radius: 16px; padding: 18px; border: 1px solid #e2e8f0; font-family: system-ui, sans-serif; }
.bubble { max-width: 85%; padding: 12px 14px; border-radius: 14px; margin-bottom: 14px; line-height: 1.7; white-space: pre-wrap; }
.bubble.you { margin-left: auto; background: #e0e7ff; }
.bubble.bot { background: #f8fafc; border: 1px dashed #cbd5e1; }
.bubble.bot:not(.preview) { border-style: solid; border-color: #c7d2fe; }
.bubble header { font-size: 11px; color: #6366f1; margin-bottom: 6px; letter-spacing: 0.03em; }
.waiting { color: #64748b; font-size: 14px; }
.vb-state { max-width: 1100px; margin: 16px auto 0; font-size: 11px; background: #020617; color: #cbd5e1; padding: 12px; border-radius: 12px; overflow: auto; max-height: 160px; font-family: ui-monospace, monospace; }
@media (max-width: 800px) { .vb-grid { grid-template-columns: 1fr; } }
</style>
