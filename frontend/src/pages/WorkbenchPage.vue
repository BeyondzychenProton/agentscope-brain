<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import { useAuth } from '../features/auth/store'
const auth = useAuth()
const router = useRouter()
const busy = ref(false)
const error = ref('')
async function logout() {
  if (busy.value) return
  busy.value = true
  try { await auth.logout(); await router.replace('/login') }
  catch { error.value = '退出尚未确认，请稍后重试。' }
  finally { busy.value = false }
}
</script>
<template><main class="restricted-layout"><header class="topbar"><div class="brand"><span class="brand-mark">A</span> AgentScope</div><ElButton :loading="busy" @click="logout">退出登录</ElButton></header><section class="restricted-card"><p class="eyebrow">WELCOME</p><h1>数字员工工作台</h1><p>{{ auth.identity?.displayName }}，欢迎回来。</p><p class="muted">账号已就绪，工作台功能将逐步开放。</p><p v-if="error" class="alert" role="alert">{{ error }}</p></section></main></template>
