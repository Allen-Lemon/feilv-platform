<template>
  <el-container style="height: 100%">
    <el-aside width="210px" style="background: #1d2b45">
      <div class="logo">🛵 飞毛腿 · 管理后台</div>
      <el-menu :default-active="$route.path" router background-color="#1d2b45" text-color="#cfd8e3"
               active-text-color="#409EFF" style="border: none">
        <el-menu-item v-for="item in visibleMenus" :key="item.code" :index="item.path">{{ item.name }}</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="crumb">{{ $route.meta?.title || '' }}</span>
        <span class="user-area">{{ nickname }} <el-button size="small" @click="logout">退出登录</el-button></span>
      </el-header>
      <el-main style="background: #f5f7fa"><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '../api'

const router = useRouter()
const nickname = ref(localStorage.getItem('feilv_admin_nickname') || '管理员')
const fallbackMenus = [
  { code: 'dashboard', name: '经营看板', path: '/dashboard' },
  { code: 'orders', name: '订单管理', path: '/orders' },
  { code: 'riders', name: '运力管理', path: '/riders' },
  { code: 'settlements', name: '结算中心', path: '/settlements' },
  { code: 'marketing', name: '营销管理', path: '/marketing' },
  { code: 'content', name: '内容与客服', path: '/content' },
  { code: 'risk', name: '风控与合规', path: '/risk' },
  { code: 'system', name: '系统管理', path: '/system' }
]
const visibleMenus = computed(() => JSON.parse(localStorage.getItem('feilv_admin_menus_data') || 'null') || fallbackMenus)
adminApi.profile().then((data) => {
  nickname.value = data.nickname
  localStorage.setItem('feilv_admin_nickname', data.nickname)
  localStorage.setItem('feilv_admin_menus', JSON.stringify(data.menus.map(m => m.code)))
  localStorage.setItem('feilv_admin_menus_data', JSON.stringify(data.menus))
}).catch(() => {})
const logout = () => {
  localStorage.removeItem('feilv_admin_token')
  localStorage.removeItem('feilv_admin_menus')
  localStorage.removeItem('feilv_admin_menus_data')
  router.push('/login')
}
</script>

<style scoped>
.logo { color: #fff; font-weight: 600; padding: 18px 16px; font-size: 16px; }
.header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fff; }
.crumb { font-weight: 600; }
.user-area { display: flex; gap: 12px; align-items: center; }
</style>
