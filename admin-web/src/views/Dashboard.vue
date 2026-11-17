<template>
  <section>
    <div class="page-head"><div><span class="eyebrow">BUSINESS OVERVIEW</span><h1>今天，也让好服务准时抵达。</h1><p>{{ dateText }} · 欢迎回来，{{ nickname }}。在这里掌握同城业务的每一步。</p></div><el-button :loading="loading" @click="load"><span class="button-content"><AppIcon name="refresh" :size="16" />刷新数据</span></el-button></div>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="data-error" />
    <div class="overview-cards" :aria-busy="loading">
      <router-link v-for="card in cards" :key="card.label" :to="card.path" custom v-slot="{ href, navigate }">
        <component :is="canOpen(card) ? 'a' : 'article'" class="stat-card" :class="{ 'is-link': canOpen(card) }" :href="canOpen(card) ? href : undefined" :title="canOpen(card) ? `进入${card.moduleName}` : undefined" @click="openCard(card, $event, navigate)">
          <div class="stat-top"><span>{{ card.label }}</span><span class="stat-icon"><AppIcon :name="card.icon" :size="18" /></span></div>
          <div class="stat-value">{{ card.value }}<small v-if="card.unit">{{ card.unit }}</small></div>
          <p>{{ card.note }}</p>
        </component>
      </router-link>
    </div>
    <div class="dashboard-grid">
      <div class="panel work-panel"><div class="panel-heading"><div><h2>运营，从这里开始</h2><p>把常用工作放在触手可及的位置</p></div><span class="pill">快捷入口</span></div>
        <div class="shortcuts"><router-link v-for="item in shortcuts" :key="item.path" :to="item.path" class="shortcut"><span class="shortcut-icon"><AppIcon :name="item.icon" :size="23" /></span><div><strong>{{ item.name }}</strong><p>{{ item.desc }}</p></div><AppIcon name="arrow" :size="17" class="shortcut-arrow" /></router-link></div>
      </div>
      <aside class="brand-panel"><span class="brand-kicker">FEILV BUSINESS</span><h2>连接一座城，<br />照顾每一份需要。</h2><p>从一份文件到一束鲜花，让每次配送都有迹可循，让每份托付都被认真对待。</p><div class="brand-route" aria-hidden="true"><span class="route-point">取</span><span class="route-line" /><span class="route-point end">达</span></div><div class="brand-footnote"><AppIcon name="orders" :size="18" /><span>同城跑腿 · 用户 / 骑手 / 运营协同</span></div></aside>
    </div>
    <div class="panel briefing"><div class="panel-heading"><div><h2>运营小贴士</h2><p>服务细节，决定体验</p></div><AppIcon name="spark" :size="21" /></div><div class="briefing-grid"><div><span class="brief-number">01</span><h3>及时跟进订单</h3><p>关注待接单和配送中订单，必要时在订单管理中处理异常。</p></div><div><span class="brief-number">02</span><h3>妥善处理售后</h3><p>受理与办结投诉时填写清晰的处理结果，确保用户反馈有回应。</p></div><div><span class="brief-number">03</span><h3>核对账务口径</h3><p>平台收入按接口返回的分账记录展示。当前支付为 MOCK 演示，不代表真实资金到账。</p></div></div></div>
    <details class="metric-details"><summary>指标口径与数据明细</summary><table><caption>经营指标当前值</caption><thead><tr><th>指标</th><th>数值</th><th>口径</th></tr></thead><tbody><tr v-for="card in cards" :key="card.label"><td>{{ card.label }}</td><td>{{ card.value }} {{ card.unit }}</td><td>{{ card.note }}</td></tr></tbody></table><p v-if="updatedAt">最近更新：{{ updatedAt }}</p></details>
  </section>
</template>
<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { adminApi } from '../api'
import AppIcon from '../components/AppIcon.vue'
const menuCodes = inject('adminMenuCodes', ref([]))
const canOpen = card => menuCodes.value.includes(card.menuCode)
function openCard(card, event, navigate) { if (!canOpen(card)) { event.preventDefault(); return } navigate(event) }
const stats = ref({})
const loading = ref(false)
const error = ref('')
const updatedAt = ref('')
const nickname = localStorage.getItem('feilv_admin_nickname') || '管理员'
const dateText = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
const number = value => value == null ? '—' : new Intl.NumberFormat('zh-CN').format(value)
const cards = computed(() => [
  { label: '注册用户', value: number(stats.value.userCount), icon: 'users', note: '平台用户总量（不含内置管理员）', path: '/users', menuCode: 'users', moduleName: '用户管理' },
  { label: '注册骑手', value: number(stats.value.riderCount), icon: 'riders', note: '已登记的骑手档案总量', path: '/riders', menuCode: 'riders', moduleName: '运力管理' },
  { label: '今日订单', value: number(stats.value.orderCountToday), icon: 'orders', note: '今日创建的全部订单', path: '/orders', menuCode: 'orders', moduleName: '订单管理' },
  { label: '累计订单', value: number(stats.value.orderCountTotal), icon: 'grid', note: '平台历史订单总量', path: '/orders', menuCode: 'orders', moduleName: '订单管理' },
  { label: '今日平台收入', value: stats.value.incomeTodayFen == null ? '—' : (stats.value.incomeTodayFen / 100).toFixed(2), unit: '元', icon: 'wallet', note: 'MOCK 分账记录的当日平台份额', path: '/settlements', menuCode: 'settlements', moduleName: '结算中心' }
])
const shortcuts = [
  { name: '订单管理', desc: '查看订单状态与干预处理', path: '/orders', icon: 'orders' },
  { name: '运力管理', desc: '查看骑手接单模式与信用', path: '/riders', icon: 'riders' },
  { name: '结算与工单', desc: '核对分账、跟进用户反馈', path: '/settlements', icon: 'wallet' },
  { name: '系统管理', desc: '维护用户、角色和菜单', path: '/system', icon: 'settings' }
]
async function load() { if (loading.value) return; loading.value = true; error.value = ''; try { stats.value = await adminApi.stats(); updatedAt.value = new Date().toLocaleString('zh-CN') } catch { error.value = '暂时未能获取经营数据，请检查服务连接后重试。' } finally { loading.value = false } }
onMounted(load)
</script>
<style scoped>
.data-error { margin-bottom: 20px; }.overview-cards { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 16px; margin-bottom: 28px; }.overview-cards[aria-busy="true"] { opacity: .65; }
.stat-card { display: block; background: white; border: 1px solid var(--line); border-radius: 16px; padding: 21px 20px; box-shadow: var(--shadow); min-width: 0; }.stat-card.is-link { cursor: pointer; transition: background .15s, border-color .15s; }.stat-card.is-link:hover,.stat-card.is-link:focus-visible { background: #fff9f5; border-color: #f1c8bb; }.stat-top { display: flex; align-items: center; justify-content: space-between; gap: 5px; color: #667085; font-size: 12px; }.stat-icon { display: flex; align-items: center; justify-content: center; width: 32px; height: 32px; background: #fff3ed; color: #c94320; border-radius: 9px; flex-shrink: 0; }.stat-value { font-size: clamp(28px, 2.5vw, 38px); color: #202939; font-weight: 700; letter-spacing: -1.2px; line-height: 1.4; margin: 13px 0 10px; overflow-wrap: anywhere; }.stat-value small { font-size: 12px; letter-spacing: 0; font-weight: 400; color: #667085; margin-left: 7px; }.stat-card > p { color: #667085; font-size: 10px; line-height: 1.6; }
.dashboard-grid { display: grid; grid-template-columns: 1.55fr 1fr; gap: 24px; margin-bottom: 26px; }.shortcuts { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }.shortcut { display: flex; align-items: center; gap: 12px; border: 1px solid #eaecf0; border-radius: 12px; padding: 23px 15px; transition: background .15s, border-color .15s; }.shortcut:hover { background: #fff9f5; border-color: #f1c8bb; }.shortcut-icon { display: flex; color: #475467; flex-shrink: 0; }.shortcut strong { font-size: 12px; font-weight: 600; }.shortcut p { font-size: 10px; color: #667085; margin-top: 7px; line-height: 1.5; }.shortcut-arrow { margin-left: auto; flex-shrink: 0; color: #667085; }
.brand-panel { position: relative; border-radius: 18px; background: #ffe8d7; padding: 30px; overflow: hidden; color: #202939; }.brand-panel::after { content: ''; position: absolute; height: 190px; width: 190px; border: 35px solid #ffd9bc; border-radius: 50%; top: -80px; right: -95px; pointer-events: none; }.brand-kicker { font-size: 9px; font-weight: 750; letter-spacing: 2px; color: #94451e; }.brand-panel h2 { font-size: 25px; line-height: 1.55; letter-spacing: -.8px; margin: 16px 0 12px; position: relative; z-index: 1; }.brand-panel > p { font-size: 11px; line-height: 1.9; color: #76523d; max-width: 310px; }.brand-route { display: flex; align-items: center; width: 180px; margin: 22px 0; }.route-point { background: white; border-radius: 50%; width: 27px; height: 27px; font-size: 10px; display: flex; align-items: center; justify-content: center; }.route-point.end { background: #c94320; color: white; }.route-line { flex: 1; height: 1px; background: #c9764b; }.brand-footnote { display: flex; gap: 7px; align-items: center; color: #76523d; font-size: 10px; }
.briefing-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 32px; }.brief-number { font-size: 10px; color: #667085; }.briefing-grid h3 { margin: 10px 0; font-size: 13px; font-weight: 600; }.briefing-grid p { color: #667085; font-size: 11px; line-height: 1.8; }.metric-details { margin-top: 22px; color: #667085; font-size: 11px; }.metric-details summary { cursor: pointer; }.metric-details table { border-collapse: collapse; background: white; width: 100%; margin: 15px 0; text-align: left; }.metric-details caption { padding: 10px; text-align: left; }.metric-details th,.metric-details td { padding: 12px; border-bottom: 1px solid var(--line); }.metric-details td:nth-child(2) { font-variant-numeric: tabular-nums; }
@media (max-width: 1200px) { .overview-cards { grid-template-columns: repeat(3, minmax(0, 1fr)); }.dashboard-grid { grid-template-columns: 1fr; }.brand-panel { display: none; } }
@media (max-width: 600px) { .overview-cards { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.stat-card { padding: 16px; }.stat-card:last-child { grid-column: 1 / -1; }.shortcuts { grid-template-columns: 1fr; }.shortcut { padding: 18px 14px; }.briefing-grid { grid-template-columns: 1fr; gap: 20px; }.metric-details th:last-child,.metric-details td:last-child { display: none; } }
</style>
