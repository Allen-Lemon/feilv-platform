import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { title: '登录' } },
  { path: '/', component: () => import('../layout/Layout.vue'), redirect: '/dashboard', children: [
    { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '经营看板', menu: 'dashboard' } },
    { path: 'orders', name: 'orders', component: () => import('../views/OrderList.vue'), meta: { title: '订单管理', menu: 'orders' } },
    { path: 'users', name: 'users', component: () => import('../views/UserList.vue'), meta: { title: '用户管理', menu: 'users' } },
    { path: 'tasks', name: 'tasks', component: () => import('../views/Tasks.vue'), meta: { title: '自定义任务', menu: 'tasks' } },
    { path: 'complaints', name: 'complaints', component: () => import('../views/Complaints.vue'), meta: { title: '投诉审核', menu: 'complaints' } },
    { path: 'riders', name: 'riders', component: () => import('../views/RiderList.vue'), meta: { title: '运力管理', menu: 'riders' } },
    { path: 'settlements', name: 'settlements', component: () => import('../views/Settlement.vue'), meta: { title: '结算中心', menu: 'settlements' } },
    { path: 'wallets', name: 'wallets', component: () => import('../views/Wallets.vue'), meta: { title: '用户钱包', menu: 'wallets' } },
    { path: 'withdrawals', name: 'withdrawals', component: () => import('../views/Withdrawal.vue'), meta: { title: '提现审核', menu: 'withdrawals' } },
    { path: 'system', name: 'system', component: () => import('../views/SystemManagement.vue'), meta: { title: '系统管理', menu: 'system' } },
    { path: 'wechat-settings', name: 'wechat-settings', component: () => import('../views/WechatSettings.vue'), meta: { title: '微信配置', menu: 'wechat-settings' } }
  ] },
  // 已下线菜单和未知旧链接统一回到看板，不再加载占位页面。
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]
const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach((to) => {
  const token = localStorage.getItem('feilv_admin_token')
  if (to.path !== '/login' && !token) return '/login'
  let menus = []
  try { menus = JSON.parse(localStorage.getItem('feilv_admin_menus') || '[]') } catch { /* 失效的菜单缓存交给 profile 刷新。 */ }
  // 看板也是无权限或旧链接的回退页，不能因旧菜单缓存反复重定向到自身。
  // 这些页面使用最新 profile 判权；旧缓存不授予权限，也不阻挡新授权，拒绝时留页提示。
  if (to.meta?.menu && !['dashboard', 'wechat-settings', 'wallets', 'withdrawals'].includes(to.meta.menu) && Array.isArray(menus) && menus.length && !menus.includes(to.meta.menu)) return '/dashboard'
  if (to.meta?.title) document.title = to.meta.title + ' · 飞毛腿管理后台'
  return true
})
export default router
