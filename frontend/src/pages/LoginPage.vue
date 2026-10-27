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
  <main class="auth-layout login-page">
    <section class="intro" aria-label="平台介绍">
      <div class="brand"><span class="brand-mark">H</span><div class="brand-copy"><div>海卓智慧大脑</div><p class="eyebrow">ENTERPRISE AI WORKSPACE</p></div></div>
      <div class="intro-content"><h1>让复杂工作，<br>从容交给 AI。</h1><p>统一连接 Agent、知识库与业务系统<br>让每一次提问，都更接近结果。</p></div>
      <div class="intro-values" aria-label="智能协作 · 安全可控 · 系统互联">
        <span><i aria-hidden="true">✦</i><span class="value-copy"><span>智能协作</span><small>从自然语言到任务执行</small></span></span>
        <span><i aria-hidden="true">⌘</i><span class="value-copy"><span>安全可控</span><small>企业权限与审计全程可追溯</small></span></span>
        <span><i aria-hidden="true">◇</i><span class="value-copy"><span>系统互联</span><small>连接模型、工具与业务数据</small></span></span>
      </div>
      <p class="intro-footer">© 2026 海卓智慧大脑 · Enterprise AI Platform</p>
    </section>
    <section class="auth-panel" aria-labelledby="login-title">
      <div class="auth-card"><p class="eyebrow">WELCOME BACK</p><h2 id="login-title">登录工作台</h2><p class="muted">使用管理员为你开通的手机号和密码。</p>
        <p v-if="auth.passwordNotice" class="alert" role="alert">{{ auth.passwordNotice }}</p>
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

<style src="./login.css"></style>
