<template>
  <div>
    <div class="page-head"><div><span class="eyebrow">SETTLEMENTS</span><h1>结算中心</h1><p>骑手分账汇总，区分原始分账、投诉扣款与净分账。投诉统一到独立审核页处理。</p></div><div><el-button v-if="canViewWallets" @click="viewWallet">钱包余额</el-button><el-button v-if="canViewComplaints" @click="viewComplaints">投诉审核</el-button><el-button :loading="loading" @click="load">刷新</el-button></div></div>
    <el-alert type="warning" show-icon :closable="false" class="notice" title="仅 MOCK 账务演示，不是真实转账：投诉成立从骑手本单分账扣款并等额赔付发布者。此页不发起真实打款、退款或资金划转。" />
    <div class="panel">
      <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="notice"><el-button link @click="load">重试</el-button></el-alert>
      <el-table :data="settlements" row-key="riderId" stripe v-loading="loading">
        <el-table-column prop="riderId" label="骑手 ID" width="100" /><el-table-column prop="name" label="姓名" min-width="130" />
        <el-table-column prop="totalShareFen" label="累计原始分账(分)" min-width="155" />
        <el-table-column label="原始分账(元)" min-width="140"><template #default="{ row }">{{ grossYuan(row) }}</template></el-table-column>
        <el-table-column label="投诉扣款(元)" min-width="140"><template #default="{ row }">{{ formatFen(row.deductedFen) }}</template></el-table-column>
        <el-table-column label="净分账(元)" min-width="140"><template #default="{ row }">{{ formatFen(row.netShareFen) }}</template></el-table-column>
        <el-table-column label="发布者赔付(元)" min-width="150"><template #default="{ row }">{{ formatFen(row.compensationFen) }}</template></el-table-column>
      </el-table>
      <p class="settlement-note">未返回的账务字段显示「—」，不假定为 0。生产资金流程需财务审核、持牌渠道打款与对账归档；本页数据不代表已真实到账。</p>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '../api'
import { formatFen } from '../utils/money'

const router = useRouter()
const menuCodes = inject('adminMenuCodes', ref([]))
const canViewComplaints = computed(() => menuCodes.value.includes('complaints'))
const canViewWallets = computed(() => menuCodes.value.includes('wallets'))
const settlements = ref([]), loading = ref(false), listError = ref('')
let disposed = false
const grossYuan = row => Number.isSafeInteger(row.totalShareFen) ? formatFen(row.totalShareFen) : row.totalShareYuan ?? '—'
function viewComplaints() { if (canViewComplaints.value) router.push('/complaints') }
function viewWallet() { if (!disposed && canViewWallets.value) router.push('/wallets') }
async function load() {
  if (loading.value || disposed) return
  loading.value = true; listError.value = ''
  try {
    const data = await adminApi.settlements()
    if (!disposed) { if (!Array.isArray(data)) throw new Error('Invalid settlements'); settlements.value = data }
  } catch { if (!disposed) { settlements.value = []; listError.value = '分账汇总加载失败，请重试。' } }
  finally { if (!disposed) loading.value = false }
}
onMounted(load)
onBeforeUnmount(() => { disposed = true })
</script>

<style scoped>
.notice { margin-bottom: 18px; }
.settlement-note { margin-top: 18px; font-size: 12px; color: var(--ink-secondary); line-height: 1.8; }
</style>
