<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElInput } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'
import { useAuth } from '../features/auth/store'
import { ApiFailure } from '../transport/http'

const auth = useAuth()
const router = useRouter()
const phone = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await auth.login(phone.value.trim(), password.value)
    password.value = ''
    await router.replace('/workbench')
  } catch (failure) {
    error.value = failure instanceof ApiFailure && failure.code === 'AUTHENTICATION_FAILED'
      ? '手机号或密码不正确，请重新输入。'
      : '登录服务暂时不可用，请稍后重试。'
  } finally { password.value = ''; busy.value = false }
}
</script>

<template>
  <main class="auth-layout">
    <section class="intro" aria-label="平台介绍">
      <div class="brand"><span class="brand-mark">A</span> AgentScope <span class="brand-divider">/</span> 数字员工</div>
      <div class="intro-content"><p class="eyebrow">YOUR EVERYDAY WORK, CONNECTED</p><h1>让每一份工作，<br>都有得力的伙伴。</h1><p>从熟悉的任务开始，<br>与数字员工一起把事情做好。</p><div class="orbit" aria-hidden="true"><div class="orbit-center">A</div><span class="orbit-label">协作 · 有序 · 可信</span></div></div>
      <p class="intro-footer">企业数字员工工作台</p>
    </section>
    <section class="auth-panel" aria-labelledby="login-title">
      <div class="auth-card"><p class="eyebrow">WELCOME BACK</p><h2 id="login-title">登录工作台</h2><p class="muted">使用管理员为你开通的手机号和密码。</p>
        <p v-if="auth.unavailable" class="alert" role="alert">登录服务暂时不可用，请稍后重试。</p>
        <form @submit.prevent="submit">
          <label for="phone">登录手机号</label><ElInput id="phone" v-model="phone" name="phone" autocomplete="username" inputmode="tel" maxlength="32" size="large" placeholder="请输入登录手机号" :disabled="busy" required />
          <label for="password">密码</label><ElInput id="password" v-model="password" name="password" type="password" autocomplete="current-password" size="large" placeholder="请输入密码" :disabled="busy" required />
          <p v-if="error" class="alert" role="alert">{{ error }}</p>
          <ElButton class="submit" type="primary" size="large" native-type="submit" :loading="busy">{{ busy ? '正在登录…' : '登录' }}</ElButton>
        </form><p class="footnote">首次登录需要先修改初始密码。<br>如需更正登录手机号，请联系管理员。</p>
      </div><p class="panel-footer">AgentScope · 让协作自然发生</p>
    </section>
  </main>
</template>
