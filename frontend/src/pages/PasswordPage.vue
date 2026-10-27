<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElInput } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'
import { useAuth } from '../features/auth/store'
import { ApiFailure } from '../transport/http'
const auth = useAuth()
const router = useRouter()
const error = ref('')
const busy = ref(false)
const currentPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
function clearPasswords() { currentPassword.value = ''; newPassword.value = ''; confirmation.value = '' }
onBeforeUnmount(clearPasswords)
async function submit() {
  if (busy.value || auth.passwordNotice) return
  error.value = ''
  if ([...newPassword.value].length < 12 || new TextEncoder().encode(newPassword.value).length > 72) {
    error.value = '新密码至少 12 个字符，UTF-8 编码不超过 72 字节。'; return
  }
  if (newPassword.value !== confirmation.value) { error.value = '两次新密码不一致。'; return }
  busy.value = true
  try {
    await auth.changeInitialPassword(currentPassword.value, newPassword.value)
    clearPasswords()
    await router.replace('/workbench')
  } catch (failure) {
    if (auth.passwordNotice) { error.value = auth.passwordNotice }
    else if (failure instanceof ApiFailure && failure.code === 'CURRENT_PASSWORD_INVALID') {
      error.value = '当前密码不正确，请核对后重新填写。'
    } else if (failure instanceof ApiFailure && failure.code === 'NEW_PASSWORD_INVALID') {
      error.value = '新密码不符合要求：至少 12 个字符，UTF-8 编码不超过 72 字节。'
    } else if (failure instanceof ApiFailure && failure.code === 'CSRF_REJECTED') {
      error.value = '页面安全校验已失效，请刷新后重新填写。'
    } else { error.value = '改密服务暂时不可用，请稍后确认登录状态。' }
  } finally { clearPasswords(); busy.value = false }
}
async function logout() {
  if (busy.value) return
  busy.value = true
  try { await auth.logout(); await router.replace('/login') }
  catch { error.value = '退出尚未确认，请稍后重试。' }
  finally { clearPasswords(); busy.value = false }
}
</script>
<template>
  <main class="restricted-layout"><header class="topbar"><div class="brand"><span class="brand-mark">A</span> AgentScope</div><ElButton :disabled="busy" @click="logout">退出登录</ElButton></header>
    <section class="restricted-card password-card" aria-labelledby="password-title">
      <p class="eyebrow">ACCOUNT SECURITY</p><h1 id="password-title">先保护好你的账号</h1>
      <p>{{ auth.identity?.displayName }}，欢迎加入。</p><p class="muted">修改初始密码后，进入数字员工工作台。</p>
      <div class="stage-note">当前仅可访问账号安全页面</div>
      <form v-if="!auth.passwordNotice" @submit.prevent="submit">
        <label for="current-password">当前密码</label><ElInput id="current-password" v-model="currentPassword" type="password" autocomplete="current-password" :disabled="busy" required />
        <label for="new-password">新密码</label><ElInput id="new-password" v-model="newPassword" type="password" autocomplete="new-password" aria-describedby="password-help" :disabled="busy" required />
        <p id="password-help" class="footnote">至少 12 个字符，UTF-8 编码不超过 72 字节。中文和表情通常占用多个字节。</p>
        <label for="confirm-password">确认新密码</label><ElInput id="confirm-password" v-model="confirmation" type="password" autocomplete="new-password" :disabled="busy" required />
        <p v-if="error" class="alert" role="alert">{{ error }}</p>
        <ElButton class="submit" type="primary" size="large" native-type="submit" :loading="busy">{{ busy ? '正在修改…' : '修改密码并进入工作台' }}</ElButton>
      </form>
      <template v-else><p class="alert" role="alert">{{ auth.passwordNotice }}</p><ElButton type="primary" @click="router.replace('/login')">返回登录</ElButton></template>
      <p class="footnote">当前登录 {{ auth.identity?.maskedPhone }}</p>
    </section>
  </main>
</template>
