/**
 * PROTOTYPE ONLY — in-memory fake of #43 end-state (process + TEXT_APPEND + SSE).
 * Not production projection. Wipe / delete with the throwaway route.
 */

export type DemoPhase =
  | 'idle'
  | 'process_running'
  | 'tool_running'
  | 'reply_streaming'
  | 'committed'
  | 'done'

export type DemoStep = {
  stepId: string
  kind: 'STAGE' | 'TOOL_STEP'
  title: string
  status: 'RUNNING' | 'DONE'
  summary: string | null
}

export type DemoState = {
  phase: DemoPhase
  sse: 'connecting' | 'live' | 'degraded_poll'
  processSteps: DemoStep[]
  previewText: string
  committed: boolean
  seq: number
  note: string
}

const REPLY =
  '已按你的要求改到 **2026-11-16（周一）19:00**，楼层 **18F**。\n\n' +
  '19:00 已超出常规工时（08:30–17:00），系统仍可预约，但参会人共同空闲可能较难。\n\n' +
  '还差：会议时长（≥15 分钟且为 5 的倍数）、参会人名单与主题（默认「工作会议」）。'

export function emptyDemo(): DemoState {
  return {
    phase: 'idle',
    sse: 'connecting',
    processSteps: [],
    previewText: '',
    committed: false,
    seq: 0,
    note: '点击「播放完整过程」模拟 #43 完成后的观感（内存态，无后端）。',
  }
}

/** Drive the fake scenario; caller owns timers. Returns next delay ms or null when finished. */
export function advanceDemo(state: DemoState): number | null {
  switch (state.phase) {
    case 'idle':
      state.phase = 'process_running'
      state.sse = 'live'
      state.seq = 1
      state.processSteps = [{
        stepId: 's1', kind: 'STAGE', title: '理解预约需求', status: 'RUNNING', summary: null,
      }]
      state.note = 'SSE live · PROCESS_STEP STAGE RUNNING（中途可见，不是收尾才出现）'
      return 900
    case 'process_running':
      state.phase = 'tool_running'
      state.seq = 2
      state.processSteps = [
        { stepId: 's1', kind: 'STAGE', title: '理解预约需求', status: 'DONE', summary: '已对齐日期与楼层' },
        { stepId: 's2', kind: 'TOOL_STEP', title: '查询会议室空闲', status: 'RUNNING', summary: '18F · 19:00' },
      ]
      state.note = 'SSE live · TOOL_STEP RUNNING（有界摘要，非原始报文）'
      return 1100
    case 'tool_running':
      state.phase = 'reply_streaming'
      state.seq = 3
      state.processSteps = state.processSteps.map(s =>
        s.stepId === 's2' ? { ...s, status: 'DONE' as const, summary: '找到候选时段，待补全时长与参会人' } : s,
      )
      state.previewText = ''
      state.note = 'SSE live · TEXT_APPEND 预览气泡开始增长（持久投影增量，非定时假动画语义）'
      return 80
    case 'reply_streaming': {
      const nextLen = Math.min(state.previewText.length + 3, REPLY.length)
      state.previewText = REPLY.slice(0, nextLen)
      state.seq = 3 + Math.floor(nextLen / 24)
      if (nextLen < REPLY.length) return 28
      state.phase = 'committed'
      return 500
    }
    case 'committed':
      state.phase = 'done'
      state.committed = true
      state.seq += 1
      state.note = 'MESSAGE_COMMIT · 预览提升为唯一正式 ASSISTANT 消息；过程块仍保留，不升格为正式消息'
      return null
    default:
      return null
  }
}
