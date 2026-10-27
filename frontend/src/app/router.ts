import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '../features/auth/store'
import LoginPage from '../pages/LoginPage.vue'
import PasswordPage from '../pages/PasswordPage.vue'
import WorkbenchPage from '../pages/WorkbenchPage.vue'
import PrototypeFormalReplyStreamingPage from '../pages/prototypes/formal-reply-streaming/PrototypeFormalReplyStreamingPage.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginPage },
    { path: '/password/initial', component: PasswordPage },
    { path: '/workbench', component: WorkbenchPage },
    // PROTOTYPE THROWWAY — #43 streaming/SSE look-and-feel; no auth; do not ship as product.
    { path: '/prototype/formal-reply-streaming', component: PrototypeFormalReplyStreamingPage },
    { path: '/:pathMatch(.*)*', redirect: '/workbench' },
  ],
})

router.beforeEach(async (to) => {
  // Throwaway UI prototypes are public and must not bounce through login.
  if (to.path.startsWith('/prototype/')) { return true }
  const auth = useAuth()
  if (to.path === '/login' && auth.passwordNotice) { return true }
  try { await auth.restore() }
  catch { return to.path === '/login' ? true : '/login' }
  const identity = auth.identity
  if (!identity) { return to.path === '/login' ? true : '/login' }
  if (identity.authStage === 'PASSWORD_CHANGE_REQUIRED') {
    return to.path === '/password/initial' ? true : '/password/initial'
  }
  return to.path === '/workbench' ? true : '/workbench'
})
