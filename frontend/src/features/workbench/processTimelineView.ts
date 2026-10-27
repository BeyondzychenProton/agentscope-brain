export function shouldHideRunWatchBanner(input: {
  processProtocolEnabled: boolean
  hasProcessSteps: boolean
  runActive: boolean
}): boolean {
  return input.processProtocolEnabled && input.runActive
}

export function processPlaceholderNotice(input: {
  hasProcessSteps: boolean
  runWatchNotice: string
  snapshotWaiting: boolean
}): string | null {
  if (input.hasProcessSteps) return null
  if (input.runWatchNotice) return input.runWatchNotice
  if (input.snapshotWaiting) return '执行中…'
  return null
}
