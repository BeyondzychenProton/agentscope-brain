<script setup lang="ts">
import { reactive } from 'vue'
import type { ProcessStep, ProcessStepKind, ProcessStepStatus } from './processProtocol'

defineProps<{
  steps: ProcessStep[]
  synthetic?: boolean
  placeholder?: string | null
  runId?: string | null
}>()

/** Local expand overrides; absent key → use !collapsedByDefault. */
const expanded = reactive(new Map<string, boolean>())

function kindHint(kind: ProcessStepKind): string {
  if (kind === 'STAGE') return '阶段'
  if (kind === 'TOOL_STEP') return '工具'
  return '思考'
}

function statusLabel(status: ProcessStepStatus): string {
  switch (status) {
    case 'RUNNING': return '进行中'
    case 'DONE': return '完成'
    case 'FAILED': return '失败'
    case 'WAITING': return '等待'
    case 'CANCELLED': return '已取消'
    case 'INTERRUPTED': return '已中断'
    case 'UNKNOWN': return '未知'
    default: return '未知'
  }
}

function isExpanded(step: ProcessStep): boolean {
  if (expanded.has(step.stepId)) return expanded.get(step.stepId)!
  return !step.collapsedByDefault
}

function toggle(step: ProcessStep) {
  expanded.set(step.stepId, !isExpanded(step))
}

function canToggle(step: ProcessStep): boolean {
  return !!(step.summary || step.detail) || step.kind === 'REASONING'
}
</script>

<template>
  <section class="process-timeline" aria-label="执行过程" :data-run-id="runId || undefined">
    <header class="process-head">
      <h3 class="process-title">执行过程</h3>
      <span v-if="synthetic" class="process-synthetic" role="note">合成数据演示</span>
    </header>

    <p v-if="!steps.length && placeholder" class="process-placeholder" role="status">{{ placeholder }}</p>

    <ol v-if="steps.length" class="process-list">
      <li v-for="step in steps" :key="step.stepId" class="process-step" :data-kind="step.kind" :data-status="step.status">
        <div class="process-row">
          <span class="process-kind">{{ kindHint(step.kind) }}</span>
          <span class="process-step-title">{{ step.title }}</span>
          <span class="process-status" :data-status="step.status">{{ statusLabel(step.status) }}</span>
          <button
            v-if="canToggle(step)"
            type="button"
            class="process-toggle"
            :aria-expanded="isExpanded(step)"
            @click="toggle(step)"
          >
            {{ isExpanded(step) ? '收起' : '展开' }}
          </button>
        </div>
        <template v-if="isExpanded(step)">
          <p v-if="step.summary" class="process-summary">{{ step.summary }}</p>
          <pre v-if="step.detail" class="process-detail">{{ step.detail }}</pre>
        </template>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.process-timeline {
  margin: 12px 0;
  padding: 12px 14px;
  background: #faf9ff;
  border: 1px solid #ded9ef;
  border-radius: 12px;
  color: #292c45;
}
.process-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.process-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #292c45;
}
.process-synthetic {
  font-size: 12px;
  line-height: 1.4;
  color: #7a7d96;
  background: #ece8ff;
  border-radius: 6px;
  padding: 2px 8px;
}
.process-placeholder {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: #5c5f78;
}
.process-list {
  list-style: none;
  margin: 0;
  padding: 0;
  border-left: 2px solid #ece8ff;
}
.process-step {
  position: relative;
  padding: 6px 0 6px 14px;
}
.process-step::before {
  content: '';
  position: absolute;
  left: -5px;
  top: 14px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #8978ef;
  box-shadow: 0 0 0 2px #faf9ff;
}
.process-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 8px;
  font-size: 13px;
  line-height: 1.5;
}
.process-kind {
  flex-shrink: 0;
  font-size: 11px;
  color: #6557db;
  background: #ece8ff;
  border-radius: 4px;
  padding: 1px 6px;
}
.process-step-title {
  font-weight: 500;
  color: #292c45;
  min-width: 0;
}
.process-status {
  font-size: 12px;
  color: #7a7d96;
}
.process-status[data-status='RUNNING'] { color: #6557db; }
.process-status[data-status='DONE'] { color: #3d8b6e; }
.process-status[data-status='FAILED'] { color: #b54545; }
.process-status[data-status='WAITING'] { color: #8c5709; }
.process-status[data-status='CANCELLED'],
.process-status[data-status='INTERRUPTED'],
.process-status[data-status='UNKNOWN'] { color: #7a7d96; }
.process-toggle {
  margin-left: auto;
  border: 0;
  padding: 0;
  background: transparent;
  color: #6557db;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.process-toggle:hover { text-decoration: underline; }
.process-summary {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.55;
  color: #5c5f78;
  white-space: pre-wrap;
}
.process-detail {
  margin: 6px 0 0;
  padding: 8px 10px;
  max-height: 220px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
  color: #292c45;
  background: #ece8ff;
  border-radius: 8px;
}
</style>
