import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '../features/auth/store'
import LoginPage from '../pages/LoginPage.vue'
import PasswordPage from '../pages/PasswordPage.vue'
import WorkbenchPage from '../pages/WorkbenchPage.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginPage },
    { path: '/password/initial', component: PasswordPage },
    { path: '/workbench', component: WorkbenchPage },
    { path: '/:pathMatch(.*)*', redirect: '/workbench' },
  ],
})

router.beforeEach(async (to) => {
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
