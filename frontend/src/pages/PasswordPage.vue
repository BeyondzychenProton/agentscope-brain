<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import { useAuth } from '../features/auth/store'
const auth = useAuth()
const router = useRouter()
const error = ref('')
const busy = ref(false)
async function logout() {
  busy.value = true
  try { await auth.logout(); await router.replace('/login') }
  catch { error.value = '退出尚未确认，请稍后重试。' }
  finally { busy.value = false }
}
</script>
<template>
  <main class="restricted-layout"><header class="topbar"><div class="brand"><span class="brand-mark">A</span> AgentScope</div><ElButton :loading="busy" @click="logout">退出登录</ElButton></header>
    <section class="restricted-card" aria-labelledby="password-title"><span class="lock-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/></svg></span><p class="eyebrow">ACCOUNT SECURITY</p><h1 id="password-title">先保护好你的账号</h1><p>{{ auth.identity?.displayName }}，欢迎加入。</p><p class="muted">这是你第一次登录。修改初始密码后，<br>就可以进入数字员工工作台。</p><div class="stage-note"><span class="status-dot"></span>当前仅可访问账号安全页面</div><ElButton type="primary" size="large" disabled>修改初始密码 · 暂未开放</ElButton><p class="footnote">当前登录 {{ auth.identity?.maskedPhone }}</p><p v-if="error" class="alert" role="alert">{{ error }}</p></section>
  </main>
</template>
