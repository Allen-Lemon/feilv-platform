<template>
  <div class="app-shell">
    <div v-if="mobileOpen" class="sidebar-scrim" @click="mobileOpen = false" />
    <aside class="sidebar" :class="{ 'is-open': mobileOpen }">
      <router-link to="/dashboard" class="brand-lockup" @click="mobileOpen = false"><span class="brand-mark">F<span>↗</span></span><div><strong>飞毛腿</strong><small>FEILV BUSINESS</small></div></router-link>
      <div class="workspace-tag"><span class="workspace-dot" /> 德江同城 · 运营空间 <span>⌄</span></div>
      <p class="nav-label">工作空间</p>
      <el-menu :default-active="$route.path" router class="main-menu" @select="mobileOpen = false">
        <el-menu-item v-for="item in visibleMenus" :key="item.code" :index="item.path"><AppIcon :name="iconFor(item.code)" :size="19" /><span>{{ item.name }}</span><span v-if="$route.path === item.path" class="active-mark" /></el-menu-item>
      </el-menu>
      <div class="sidebar-bottom"><div class="sidebar-note"><AppIcon name="spark" :size="18" /><div><strong>把每一单，服务好。</strong><p>让同城生意流动起来</p></div></div><div class="workspace-version">飞毛腿运营平台 <span>v1.0</span></div></div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <div class="breadcrumb"><button class="icon-button mobile-toggle" aria-label="打开导航" @click="mobileOpen = true"><AppIcon name="menu" /></button><span class="breadcrumb-root">工作空间</span><span class="breadcrumb-separator">/</span><span>{{ $route.meta?.title || '运营平台' }}</span></div>
        <div class="header-actions"><span class="environment">演示环境</span><span class="header-divider" /><span class="user-avatar">{{ nickname.slice(0, 1) }}</span><div class="user-meta"><strong>{{ nickname }}</strong><small>管理账户</small></div><button class="icon-button" aria-label="退出登录" title="退出登录" @click="logout"><AppIcon name="logout" :size="18" /></button></div>
      </header>
      <main class="workspace-main"><router-view /></main>
      <footer class="workspace-footer"><span>FEILV · 同城生活的连接者</span><span>每一份托付，都认真对待</span></footer>
    </div>
  </div>
</template>

<script setup>
import { provide, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '../api'
import AppIcon from '../components/AppIcon.vue'
const router = useRouter()
const mobileOpen = ref(false)
const nickname = ref(localStorage.getItem('feilv_admin_nickname') || '管理员')
function storedMenus() { try { return JSON.parse(localStorage.getItem('feilv_admin_menus_data') || '[]') } catch { return [] } }
const visibleMenus = ref(storedMenus())
// 跨页面入口只使用最新 profile 的授权，加载中或失败时不开放跳转。
const authorizedMenuCodes = ref([])
provide('adminMenuCodes', authorizedMenuCodes)
const icons = { dashboard: 'grid', orders: 'orders', tasks: 'orders', complaints: 'shield', users: 'riders', riders: 'riders', settlements: 'wallet', marketing: 'marketing', content: 'content', risk: 'shield', system: 'settings', 'wechat-settings': 'shield' }
const iconFor = (code) => icons[code] || 'grid'
adminApi.profile().then((data) => {
  nickname.value = data.nickname || '管理员'
  visibleMenus.value = (data.menus || []).filter(m => m.path)
  if ((data.roles || []).includes('SUPER_ADMIN')) {
    // 超级管理员兼容尚未下发新菜单数据的环境；普通角色仍只使用 profile 授权。
    for (const menu of [
      { code: 'tasks', name: '自定义任务', path: '/tasks' },
      { code: 'complaints', name: '投诉审核', path: '/complaints' },
      { code: 'wechat-settings', name: '微信配置', path: '/wechat-settings' }
    ]) if (!visibleMenus.value.some(m => m.code === menu.code)) visibleMenus.value.push(menu)
  }
  authorizedMenuCodes.value = visibleMenus.value.map(m => m.code)
  localStorage.setItem('feilv_admin_nickname', nickname.value)
  localStorage.setItem('feilv_admin_menus', JSON.stringify(visibleMenus.value.map(m => m.code)))
  localStorage.setItem('feilv_admin_menus_data', JSON.stringify(visibleMenus.value))
}).catch(() => {})
const logout = () => {
  ;['feilv_admin_token', 'feilv_admin_menus', 'feilv_admin_menus_data', 'feilv_admin_nickname'].forEach(k => localStorage.removeItem(k))
  router.push('/login')
}
</script>

<style scoped>
.app-shell { min-height: 100vh; }
.sidebar { position: fixed; inset: 0 auto 0 0; width: 242px; display: flex; flex-direction: column; padding: 30px 18px 16px; background: white; border-right: 1px solid var(--line); z-index: 100; }
.brand-lockup { display: flex; align-items: center; gap: 12px; padding: 0 12px 27px; }
.brand-mark { width: 42px; height: 42px; border-radius: 13px; background: #ff7547; color: #202939; display: inline-flex; align-items: center; justify-content: center; font-size: 29px; font-weight: 850; font-style: italic; position: relative; }
.brand-mark span { font-size: 18px; margin: -12px 0 0 0; }
.brand-lockup strong { font-size: 23px; letter-spacing: 2px; }
.brand-lockup small { display: block; font-size: 9px; letter-spacing: 1.3px; margin-top: 2px; color: var(--ink-secondary); }
.workspace-tag { display: flex; align-items: center; gap: 8px; background: #f8f9fb; border: 1px solid var(--line); border-radius: 10px; padding: 12px 10px; font-size: 11px; color: #475467; margin: 0 5px 28px; }
.workspace-tag > span:last-child { margin-left: auto; }
.workspace-dot { width: 7px; height: 7px; background: #c94320; border-radius: 50%; }
.nav-label { font-size: 10px; color: var(--ink-secondary); padding: 0 17px 12px; letter-spacing: 1px; }
.main-menu { border: 0; --el-menu-hover-bg-color: #f8f9fb; --el-menu-active-color: #b83d1c; }
.main-menu .el-menu-item { height: 48px; margin-bottom: 5px; border-radius: 10px; padding: 0 16px !important; color: #667085; font-size: 13px; gap: 12px; }
.main-menu .el-menu-item.is-active { background: var(--brand-soft); color: #b83d1c; font-weight: 650; }
.active-mark { width: 5px; height: 5px; background: #c94320; border-radius: 50%; margin-left: auto; }
.sidebar-bottom { margin-top: auto; padding-top: 24px; }
.sidebar-note { display: flex; gap: 10px; background: #fff7ef; border-radius: 12px; padding: 16px 12px; color: #a35022; }
.sidebar-note strong { font-size: 11px; }.sidebar-note p { font-size: 10px; margin-top: 6px; color: #826c5d; }
.workspace-version { display: flex; justify-content: space-between; padding: 20px 9px 0; font-size: 10px; color: #667085; }
.workspace { margin-left: 242px; min-height: 100vh; display: flex; flex-direction: column; }
.topbar { height: 80px; background: white; border-bottom: 1px solid var(--line); padding: 0 36px; display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.breadcrumb { display: flex; align-items: center; gap: 15px; font-size: 12px; }.breadcrumb-root, .breadcrumb-separator { color: #667085; }
.header-actions { display: flex; align-items: center; gap: 12px; }.environment { font-size: 10px; color: #a45a20; background: #fff5e4; border: 1px solid #f3e5c9; border-radius: 6px; padding: 5px 9px; }.header-divider { width: 1px; height: 25px; background: var(--line); margin: 0 6px; }
.user-avatar { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; border-radius: 11px; background: #ffe7d5; color: #9c3d18; font-weight: 650; }.user-meta strong { display: block; font-size: 11px; font-weight: 600; }.user-meta small { display: block; font-size: 9px; color: var(--ink-secondary); margin-top: 3px; }
.workspace-main { flex: 1; padding: 34px 36px; width: 100%; max-width: 1680px; margin: 0 auto; min-width: 0; }
.workspace-footer { display: flex; justify-content: space-between; padding: 0 36px 22px; font-size: 10px; color: #667085; }
.mobile-toggle { display: none; }.sidebar-scrim { position: fixed; inset: 0; background: #20293966; z-index: 99; }
@media (min-width: 1800px) { .workspace-main { padding-top: 42px; } }
@media (max-width: 1024px) { .sidebar { width: 216px; }.workspace { margin-left: 216px; }.workspace-main { padding: 26px 22px; }.topbar { padding: 0 22px; } }
@media (max-width: 768px) { .sidebar { transform: translateX(-100%); transition: transform .2s; width: 242px; }.sidebar.is-open { transform: translateX(0); }.workspace { margin-left: 0; }.mobile-toggle { display: inline-flex; }.topbar { height: 68px; padding: 0 18px; }.breadcrumb-root,.breadcrumb-separator,.environment,.header-divider,.user-meta { display: none; }.workspace-main { padding: 26px 16px; }.workspace-footer { padding: 0 18px 20px; gap: 20px; line-height: 1.6; }.header-actions { gap: 8px; } }
</style>
