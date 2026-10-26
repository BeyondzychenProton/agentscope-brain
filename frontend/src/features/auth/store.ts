import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ApiFailure, read, write } from '../../transport/http'

export interface Identity {
  authStage: 'PASSWORD_CHANGE_REQUIRED' | 'READY'
  userId: string
  displayName: string
  authenticatedAt: string
  absoluteExpiresAt: string
  maskedPhone?: string
}

export const useAuth = defineStore('auth', () => {
  const identity = ref<Identity | null>(null)
  const unavailable = ref(false)
  async function restore() {
    unavailable.value = false
    try { identity.value = await read<Identity>('/api/me') }
    catch (error) {
      identity.value = null
      if (!(error instanceof ApiFailure && error.status === 401)) { unavailable.value = true; throw error }
    }
  }
  async function login(phone: string, password: string) {
    identity.value = await write<Identity>('/api/auth/login', { phone, password })
  }
  async function logout() {
    await write<void>('/api/auth/logout')
    identity.value = null
  }
  return { identity, unavailable, restore, login, logout }
})
