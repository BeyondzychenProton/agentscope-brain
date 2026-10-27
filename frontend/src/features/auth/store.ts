import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ApiFailure, read, write } from '../../transport/http'
import { clearCurrentTabRecovery } from './tabRecovery'

export interface Identity {
  authStage: 'PASSWORD_CHANGE_REQUIRED' | 'READY'
  userId: string
  displayName: string
  authenticatedAt: string
  absoluteExpiresAt: string
  maskedPhone?: string
  employees?: Array<{ employeeId: string; displayName: string; canStartRun: boolean; readOnly: boolean;
    unavailableReason: string | null; capabilities: import('../workbench/history').Capability }>
}

export const useAuth = defineStore('auth', () => {
  const identity = ref<Identity | null>(null)
  const unavailable = ref(false)
  const passwordNotice = ref('')
  async function restore() {
    unavailable.value = false
    try {
      const restored = await read<Identity>('/api/me')
      if (identity.value && identity.value.userId !== restored.userId) clearCurrentTabRecovery()
      identity.value = restored
    }
    catch (error) {
      identity.value = null
      if (error instanceof ApiFailure && error.status === 401) {
        clearCurrentTabRecovery()
        return
      }
      unavailable.value = true
      throw error
    }
  }
  async function login(phone: string, password: string) {
    identity.value = await write<Identity>('/api/auth/login', { phone, password })
    passwordNotice.value = ''
  }
  async function changeInitialPassword(currentPassword: string, newPassword: string) {
    try {
      identity.value = await write<Identity>('/api/auth/password/initial', { currentPassword, newPassword })
    } catch (failure) {
      if (failure instanceof ApiFailure && failure.code === 'PASSWORD_CHANGED_RELOGIN_REQUIRED') {
        passwordNotice.value = '密码已修改，但登录状态未能保存。请使用新密码重新登录，无需再次修改。'
      } else if (!(failure instanceof ApiFailure) || failure.status === 0 || failure.code === 'AUTH_OUTCOME_UNCERTAIN') {
        passwordNotice.value = '本次改密结果尚未确认，请先尝试使用新密码登录；若新密码无效，再尝试原密码。请勿直接重复提交改密。'
      } else if (failure.status === 401) {
        passwordNotice.value = '登录状态已失效，请重新登录后确认当前账号状态。'
      }
      if (passwordNotice.value) {
        identity.value = null
        clearCurrentTabRecovery()
      }
      throw failure
    }
  }
  async function logout() {
    await write<void>('/api/auth/logout')
    identity.value = null
    clearCurrentTabRecovery()
  }
  return { identity, unavailable, passwordNotice, restore, login, logout, changeInitialPassword }
})
