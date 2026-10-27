/** Tab-scoped recovery keys. sessionStorage is already per-tab; clear only our prefix. */
export const RECOVERY_PREFIX = 'agentscope.recovery.'

export function writeTabRecovery(key: string, value: string): void {
  sessionStorage.setItem(RECOVERY_PREFIX + key, value)
}

export function readTabRecovery(key: string): string | null {
  return sessionStorage.getItem(RECOVERY_PREFIX + key)
}

/** Clear this tab's application recovery only. Never localStorage.clear or sessionStorage.clear. */
export function clearCurrentTabRecovery(): void {
  const keys: string[] = []
  for (let index = 0; index < sessionStorage.length; index += 1) {
    const key = sessionStorage.key(index)
    if (key?.startsWith(RECOVERY_PREFIX)) { keys.push(key) }
  }
  for (const key of keys) { sessionStorage.removeItem(key) }
}
