<script setup lang="ts">
/** PROTOTYPE variant C — Compact process chips + sticky streaming strip at bottom of transcript. */
import type { DemoState } from './demoStream'

defineProps<{ state: DemoState; onPlay: () => void; onReset: () => void }>()
</script>

<template>
  <div class="vc">
    <div class="vc-shell">
      <header class="vc-head">
        <div>
          <p class="eyebrow">#43 目标观感 · 变体 C</p>
          <h1>紧凑芯片过程 + 底部流式条</h1>
        </div>
        <div class="vc-ctrl">
          <button type="button" @click="onPlay">▶ 播放</button>
          <button type="button" class="ghost" @click="onReset">重置</button>
        </div>
      </header>

      <div class="vc-thread">
        <div class="msg user">帮我约 11 月 15 日的会议室，改到第一个工作日晚上 7 点，18 楼。</div>

        <div class="chips" aria-label="过程芯片">
          <span
            v-for="s in state.processSteps"
            :key="s.stepId"
            class="chip"
            :data-st="s.status"
            :title="s.summary || s.title"
          >
            {{ s.kind === 'STAGE' ? '◎' : '◷' }} {{ s.title }}
          </span>
          <span v-if="!state.processSteps.length" class="chip muted">尚无过程投影</span>
        </div>

        <div v-if="state.committed" class="msg bot final">
          <div class="label">正式回复</div>
          <div class="body">{{ state.previewText }}</div>
        </div>
      </div>

      <div class="vc-strip" :data-phase="state.phase">
        <div class="strip-meta">
          <span class="sse" :data-sse="state.sse">{{ state.sse }}</span>
          <span>{{ state.committed ? '已提交正式消息' : '预览流' }}</span>
          <span class="seq">seq {{ state.seq }}</span>
        </div>
        <div class="strip-body">
          <template v-if="!state.committed && state.previewText">{{ state.previewText }}</template>
          <template v-else-if="!state.committed">{{ state.note }}</template>
          <template v-else>预览已提升；底部条可收起（本原型保持可见以便对照状态）。</template>
        </div>
      </div>
    </div>
    <pre class="vc-state">{{ JSON.stringify(state, null, 2) }}</pre>
  </div>
</template>

<style scoped>
.vc { min-height: 100vh; background: #0b1020; color: #e8eaf2; font-family: "IBM Plex Sans", "Segoe UI", sans-serif; padding: 24px 16px 120px; }
.vc-shell { max-width: 640px; margin: 0 auto; }
.vc-head { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
.eyebrow { margin: 0; font-size: 11px; letter-spacing: 0.08em; text-transform: uppercase; color: #8b93b0; }
h1 { margin: 4px 0 0; font-size: 20px; font-weight: 600; }
.vc-ctrl { display: flex; gap: 8px; }
.vc-ctrl button { border: 0; background: #7c5cff; color: #fff; padding: 8px 12px; border-radius: 999px; cursor: pointer; font-weight: 600; }
.vc-ctrl .ghost { background: #1c2438; color: #c9d0e8; }
.vc-thread { display: grid; gap: 14px; min-height: 320px; }
.msg { padding: 12px 14px; border-radius: 14px; line-height: 1.65; white-space: pre-wrap; }
.msg.user { justify-self: end; max-width: 80%; background: #1a2238; border: 1px solid #2a3555; }
.msg.bot.final { background: #12182a; border: 1px solid #3d2f7a; }
.msg .label { font-size: 11px; color: #a78bfa; margin-bottom: 6px; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip { font-size: 12px; padding: 6px 10px; border-radius: 999px; background: #151c30; border: 1px solid #2b3554; }
.chip[data-st="RUNNING"] { border-color: #f59e0b; color: #fcd34d; }
.chip[data-st="DONE"] { border-color: #10b981; color: #6ee7b7; }
.chip.muted { opacity: 0.5; }
.vc-strip {
  position: sticky; bottom: 72px; margin-top: 18px;
  border-radius: 16px; padding: 12px 14px;
  background: rgba(24, 30, 52, 0.92); border: 1px solid #3b4568;
  backdrop-filter: blur(8px);
}
.vc-strip[data-phase="reply_streaming"] { border-color: #7c5cff; box-shadow: 0 0 0 1px rgba(124, 92, 255, 0.35); }
.strip-meta { display: flex; gap: 10px; align-items: center; font-size: 11px; color: #9aa3c2; margin-bottom: 8px; }
.sse[data-sse="live"] { color: #34d399; }
.seq { margin-left: auto; font-variant-numeric: tabular-nums; }
.strip-body { font-size: 14px; line-height: 1.7; white-space: pre-wrap; max-height: 160px; overflow: auto; }
.vc-state { max-width: 640px; margin: 18px auto 0; font-size: 11px; background: #05070f; color: #9aa3c2; padding: 12px; border-radius: 12px; overflow: auto; max-height: 150px; }
</style>
