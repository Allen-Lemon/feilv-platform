import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { title: '登录' } },
  { path: '/', component: () => import('../layout/Layout.vue'), redirect: '/dashboard', children: [
    { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '经营看板', menu: 'dashboard' } },
    { path: 'orders', name: 'orders', component: () => import('../views/OrderList.vue'), meta: { title: '订单管理', menu: 'orders' } },
    { path: 'riders', name: 'riders', component: () => import('../views/RiderList.vue'), meta: { title: '运力管理', menu: 'riders' } },
    { path: 'settlements', name: 'settlements', component: () => import('../views/Settlement.vue'), meta: { title: '结算中心', menu: 'settlements' } },
    { path: 'marketing', name: 'marketing', component: () => import('../views/Placeholder.vue'), meta: { title: '营销管理', menu: 'marketing' } },
    { path: 'content', name: 'content', component: () => import('../views/Placeholder.vue'), meta: { title: '内容与客服', menu: 'content' } },
    { path: 'risk', name: 'risk', component: () => import('../views/Placeholder.vue'), meta: { title: '风控与合规', menu: 'risk' } },
    { path: 'system', name: 'system', component: () => import('../views/SystemManagement.vue'), meta: { title: '系统管理', menu: 'system' } },
    { path: 'wechat-settings', name: 'wechat-settings', component: () => import('../views/WechatSettings.vue'), meta: { title: '微信配置', menu: 'wechat-settings' } }
  ] }
]
const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach((to) => {
  const token = localStorage.getItem('feilv_admin_token')
  if (to.path !== '/login' && !token) return '/login'
  let menus = []
  try { menus = JSON.parse(localStorage.getItem('feilv_admin_menus') || '[]') } catch { /* 失效的菜单缓存交给 profile 刷新。 */ }
  // 微信配置页使用最新 profile 判权，拒绝时留在页面显示错误，不依赖旧缓存回退。
  if (to.meta?.menu && to.meta.menu !== 'wechat-settings' && menus.length && !menus.includes(to.meta.menu)) return '/dashboard'
  if (to.meta?.title) document.title = to.meta.title + ' · 飞毛腿管理后台'
  return true
})
export default router
