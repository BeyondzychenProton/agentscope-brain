<script setup lang="ts">
import { computed, nextTick, onUpdated, ref, watch } from 'vue'
import { renderMarkdownV1 } from './markdown'

const props = defineProps<{ source: string }>()
const root = ref<HTMLElement | null>(null)
const copyHint = ref('')
const rendered = computed(() => renderMarkdownV1(props.source))

function enhanceCodeBlocks() {
  const el = root.value
  if (!el || rendered.value.kind !== 'html') return
  el.querySelectorAll('pre').forEach((pre) => {
    if (pre.parentElement?.classList.contains('md-code')) return
    const wrap = document.createElement('div')
    wrap.className = 'md-code'
    pre.replaceWith(wrap)
    wrap.appendChild(pre)
    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = 'md-copy'
    btn.textContent = '复制'
    btn.addEventListener('click', () => {
      void (async () => {
        try {
          await navigator.clipboard.writeText(pre.textContent ?? '')
          btn.textContent = '已复制'
          copyHint.value = ''
        } catch {
          btn.textContent = '复制失败'
          copyHint.value = '代码复制未成功，请手动选择复制。'
        }
      })()
    })
    wrap.appendChild(btn)
  })
}

watch(rendered, async () => {
  copyHint.value = ''
  await nextTick()
  enhanceCodeBlocks()
}, { immediate: true })
onUpdated(() => { enhanceCodeBlocks() })
</script>

<template>
  <div class="markdown-body message-text">
    <div v-if="rendered.kind === 'html'" ref="root" class="md-html" v-html="rendered.value" />
    <p v-else class="md-plain">{{ rendered.value }}</p>
    <p v-if="copyHint" class="md-copy-hint" role="status">{{ copyHint }}</p>
  </div>
</template>

<style scoped>
/*
 * .message-text on this root (from the bubble chrome) sets pre-wrap + line-height 1.9.
 * pre-wrap makes HTML inter-tag newlines visible and blows open list/paragraph gaps.
 */
.markdown-body.message-text {
  min-width: 0;
  white-space: normal;
  line-height: 1.55;
}
.md-html :deep(p) { margin: 0 0 0.45em; }
.md-html :deep(p:last-child) { margin-bottom: 0; }
.md-html :deep(ul),
.md-html :deep(ol) {
  margin: 0.2em 0 0.45em;
  padding-left: 1.35em;
}
.md-html :deep(li) { margin: 0.1em 0; }
/* markdown-it wraps loose list items in <p>; zero those so ol/ul nesting stays tight */
.md-html :deep(li > p) { margin: 0; }
.md-html :deep(li > ul),
.md-html :deep(li > ol) { margin: 0.15em 0 0.15em; }
.md-html :deep(blockquote) {
  margin: 0.35em 0; padding: 0.15em 0.75em; border-left: 3px solid #cfc7ef; color: #5c5f78;
}
.md-html :deep(table) {
  width: 100%; max-width: 100%; border-collapse: collapse;
  margin: 0.4em 0; font-size: 0.95em;
}
.md-html :deep(th), .md-html :deep(td) {
  border: 1px solid #e0dced; padding: 5px 8px; text-align: left; vertical-align: top;
}
.md-html :deep(th) { background: #f3f0fb; font-weight: 600; }
.md-html :deep(a) { color: #4b3fb8; text-decoration: underline; word-break: break-all; }
.md-html :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.92em; background: #f3f0fb; padding: 0.1em 0.35em; border-radius: 4px;
}
.md-html :deep(pre) {
  margin: 0; padding: 10px 12px; overflow: auto; background: #f3f0fb; border-radius: 8px;
}
.md-html :deep(pre code) { background: transparent; padding: 0; }
.md-html :deep(.md-code) { position: relative; margin: 0.4em 0; }
.md-html :deep(.md-copy) {
  position: absolute; top: 6px; right: 6px; border: 1px solid #d9d2f8; border-radius: 6px;
  padding: 2px 8px; background: #fff; color: #6557db; font-size: 12px; cursor: pointer;
}
.md-plain { margin: 0; white-space: pre-wrap; line-height: 1.55; }
.md-copy-hint { margin: 6px 0 0; font-size: 12px; color: #8c5709; }
</style>
