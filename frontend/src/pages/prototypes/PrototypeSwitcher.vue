<script setup lang="ts">
/**
 * PROTOTYPE ONLY — floating variant bar. Hidden in production builds.
 * Do not reuse in product UI.
 */
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const props = defineProps<{
  variants: Array<{ key: string; name: string }>
}>()

const route = useRoute()
const router = useRouter()
const isDev = import.meta.env.DEV

const currentKey = computed(() => {
  const raw = typeof route.query.variant === 'string' ? route.query.variant : 'A'
  return props.variants.some(v => v.key === raw) ? raw : props.variants[0]?.key ?? 'A'
})

const currentLabel = computed(() => {
  const hit = props.variants.find(v => v.key === currentKey.value)
  return hit ? `${hit.key} (${hit.name})` : currentKey.value
})

function go(delta: number) {
  const list = props.variants
  if (!list.length) return
  const idx = list.findIndex(v => v.key === currentKey.value)
  const next = list[(idx + delta + list.length) % list.length]!
  void router.replace({ query: { ...route.query, variant: next.key } })
}

function onKey(e: KeyboardEvent) {
  const t = e.target as HTMLElement | null
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
  if (e.key === 'ArrowLeft') go(-1)
  if (e.key === 'ArrowRight') go(1)
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div v-if="isDev" class="proto-switcher" role="toolbar" aria-label="原型变体切换">
    <button type="button" class="proto-btn" aria-label="上一变体" @click="go(-1)">←</button>
    <span class="proto-label">{{ currentLabel }}</span>
    <button type="button" class="proto-btn" aria-label="下一变体" @click="go(1)">→</button>
  </div>
</template>

<style scoped>
.proto-switcher {
  position: fixed;
  z-index: 9999;
  left: 50%;
  bottom: 20px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-radius: 999px;
  background: #111827;
  color: #f9fafb;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.35);
  font-size: 13px;
  user-select: none;
}
.proto-btn {
  border: 0;
  background: #374151;
  color: inherit;
  width: 32px;
  height: 32px;
  border-radius: 999px;
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
}
.proto-btn:hover { background: #4b5563; }
.proto-label {
  min-width: 11rem;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
</style>
