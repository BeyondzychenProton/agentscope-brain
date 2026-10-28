<script setup lang="ts">
/**
 * PROTOTYPE THROWWAY — #43「正式回复流式 + 只读 SSE」完成后观感。
 * Question: What should the completed streaming / SSE UX look like?
 * Three structurally different variants via ?variant=A|B|C
 * Run: npm run prototype:formal-reply-streaming → open URL below.
 * Not production. No real projection / auth.
 */
import { computed, onUnmounted, reactive } from 'vue'
import { useRoute } from 'vue-router'
import PrototypeSwitcher from '../PrototypeSwitcher.vue'
import VariantA from './VariantA.vue'
import VariantB from './VariantB.vue'
import VariantC from './VariantC.vue'
import { advanceDemo, emptyDemo, type DemoState } from './demoStream'

const route = useRoute()
const variants = [
  { key: 'A', name: '经典对话列' },
  { key: 'B', name: '过程分栏轨' },
  { key: 'C', name: '芯片+底部流' },
]

const variant = computed(() => {
  const raw = typeof route.query.variant === 'string' ? route.query.variant : 'A'
  return variants.some(v => v.key === raw) ? raw : 'A'
})

const state = reactive<DemoState>(emptyDemo())
let timer: ReturnType<typeof setTimeout> | null = null

function clearTimer() {
  if (timer != null) { clearTimeout(timer); timer = null }
}

function reset() {
  clearTimer()
  Object.assign(state, emptyDemo())
}

function tick() {
  const delay = advanceDemo(state)
  if (delay == null) { timer = null; return }
  timer = setTimeout(tick, delay)
}

function play() {
  reset()
  tick()
}

onUnmounted(clearTimer)
</script>

<template>
  <div class="proto-root">
    <p class="banner" role="note">
      PROTOTYPE · #43 完成后观感（抛弃式）· 内存模拟 TEXT_APPEND + 过程步 + SSE 徽标 ·
      非真实投影 · <code>?variant=A|B|C</code>
    </p>
    <VariantA v-if="variant === 'A'" :state="state" :on-play="play" :on-reset="reset" />
    <VariantB v-else-if="variant === 'B'" :state="state" :on-play="play" :on-reset="reset" />
    <VariantC v-else :state="state" :on-play="play" :on-reset="reset" />
    <PrototypeSwitcher :variants="variants" />
  </div>
</template>

<style scoped>
.proto-root { min-height: 100vh; }
.banner {
  margin: 0;
  padding: 8px 12px;
  background: #fef3c7;
  color: #92400e;
  font: 12px/1.4 system-ui, sans-serif;
  border-bottom: 1px solid #f59e0b;
}
.banner code { font-size: 11px; }
</style>
