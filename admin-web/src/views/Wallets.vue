<template>
  <div>
    <div class="page-head"><div><span class="eyebrow">USER WALLETS</span><h1>用户钱包</h1><p>核对用户余额与资金流水。这里只读查账，不提供充值或手动修改余额。</p></div><el-button v-if="canManage && canViewWithdrawals" @click="viewWithdrawals()">提现审核</el-button></div>
    <div class="wallet-note"><AppIcon name="wallet" :size="22" /><div><strong>账实分明 · 仅 MOCK 账务</strong><p>骑手收入为原订单 70% 分账减投诉扣款；投诉成立等额赔付发布者。余额不足形成欠款，后续入账先抵欠款，不代表真实资金到账。</p></div></div>
    <el-alert v-if="!canManage" :title="accessDenied ? '钱包访问已被拒绝或登录失效，请重新登录或联系管理员确认授权。' : '正在确认权限，或当前账户未获用户钱包权限。'" type="warning" :closable="false" show-icon />
    <div v-else class="panel">
      <el-form inline class="filter-row" @submit.prevent="load(1)">
        <el-form-item label="用户 ID"><el-input v-model="query.userId" clearable placeholder="输入完整用户 ID 精确查询" style="width:260px" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading">查询</el-button><el-button @click="resetFilters">重置</el-button>
      </el-form>
      <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="notice"><el-button link @click="load()">重试</el-button></el-alert>
      <el-table :data="rows" row-key="userId" stripe v-loading="loading">
        <el-table-column prop="userId" label="用户 ID" width="100" />
        <el-table-column label="昵称" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ row.nickname || '未设置昵称' }}</template></el-table-column>
        <el-table-column label="手机号（脱敏）" width="150"><template #default="{ row }">{{ maskedMobile(row.mobile) }}</template></el-table-column>
        <el-table-column prop="role" label="角色" width="110" />
        <el-table-column label="账户状态" width="105"><template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="可用余额(元)" width="145" align="right"><template #default="{ row }"><span class="money">{{ formatFen(row.availableFen) }}</span></template></el-table-column>
        <el-table-column label="冻结金额(元)" width="145" align="right"><template #default="{ row }"><span class="money">{{ formatFen(row.frozenFen) }}</span></template></el-table-column>
        <el-table-column label="待抵欠款(元)" width="145" align="right"><template #default="{ row }"><span class="money" :class="{ debt: row.debtFen > 0 }">{{ formatFen(row.debtFen) }}</span></template></el-table-column>
        <el-table-column label="累计收入(元)" width="145" align="right"><template #default="{ row }"><span class="money">{{ formatFen(row.totalIncomeFen) }}</span></template></el-table-column>
        <el-table-column label="累计提现(元)" width="145" align="right"><template #default="{ row }"><span class="money">{{ formatFen(row.totalWithdrawnFen) }}</span></template></el-table-column>
        <el-table-column label="渠道" width="100"><template #default="{ row }"><el-tag type="info">{{ row.channel || '未知' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" :width="canViewUsers ? 185 : 100" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openEntries(row.userId)">查看流水</el-button><el-button v-if="canViewUsers" link @click="viewUser(row.userId)">用户详情</el-button></template></el-table-column>
        <template #empty>{{ loading ? '正在加载钱包…' : listError ? '未能取得钱包数据' : '暂无符合条件的钱包' }}</template>
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="15" :current-page="page" @current-change="load" />
      <p class="table-note">金额单位为元，缺失或无效金额显示「—」，不补零。冻结金额包含待审核提现，欠款将由后续入账优先抵扣。</p>
    </div>

    <el-dialog v-model="entriesOpen" :title="`钱包流水 · 用户 ID ${entriesUserId}`" width="min(1280px, 94vw)" @close="closeEntries">
      <template v-if="canManage">
        <div class="ledger-heading"><p>按发生时间核对每笔变动，正数为增加，负数为扣减；后余额均以服务端记录为准。</p><div><el-button v-if="canViewWithdrawals" @click="viewWithdrawals(entriesUserId)">该用户提现</el-button><el-button :loading="entriesLoading" @click="loadEntries()">刷新流水</el-button></div></div>
        <el-alert v-if="entriesError" :title="entriesError" type="error" :closable="false" class="notice"><el-button link @click="loadEntries()">重试</el-button></el-alert>
        <el-table :data="entries" row-key="id" stripe v-loading="entriesLoading">
          <el-table-column prop="id" label="流水 ID" width="95" /><el-table-column prop="type" label="类型" min-width="175" />
          <el-table-column label="变动金额(元)" width="150" align="right"><template #default="{ row }"><span class="money" :class="{ debt: row.amountFen < 0 }">{{ signedFen(row.amountFen) }}</span></template></el-table-column>
          <el-table-column label="变动后可用(元)" width="155" align="right"><template #default="{ row }">{{ formatFen(row.availableAfterFen) }}</template></el-table-column>
          <el-table-column label="变动后冻结(元)" width="155" align="right"><template #default="{ row }">{{ formatFen(row.frozenAfterFen) }}</template></el-table-column>
          <el-table-column label="变动后欠款(元)" width="155" align="right"><template #default="{ row }">{{ formatFen(row.debtAfterFen) }}</template></el-table-column>
          <el-table-column prop="orderId" label="订单 ID" width="100" /><el-table-column prop="complaintId" label="投诉 ID" width="100" /><el-table-column prop="withdrawalId" label="提现 ID" width="100" />
          <el-table-column prop="remark" label="备注" min-width="220" show-overflow-tooltip /><el-table-column prop="createdTime" label="发生时间" width="180" />
          <template #empty>{{ entriesLoading ? '正在加载流水…' : entriesError ? '未能取得流水数据' : '暂无钱包流水' }}</template>
        </el-table>
        <el-pagination layout="prev, pager, next, total" :total="entriesTotal" :page-size="15" :current-page="entriesPage" @current-change="loadEntries" />
      </template>
      <template #footer><el-button @click="entriesOpen = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi } from '../api'
import { formatFen } from '../utils/money'
import AppIcon from '../components/AppIcon.vue'

const route = useRoute(), router = useRouter()
const menuCodes = inject('adminMenuCodes', ref([])), accessDenied = ref(false)
const canManage = computed(() => !accessDenied.value && menuCodes.value.includes('wallets'))
const canViewUsers = computed(() => menuCodes.value.includes('users'))
const canViewWithdrawals = computed(() => menuCodes.value.includes('withdrawals'))
const query = reactive({ userId: '' })
const rows = ref([]), page = ref(1), total = ref(0), loading = ref(false), listError = ref('')
const entriesOpen = ref(false), entriesUserId = ref(''), entries = ref([]), entriesPage = ref(1), entriesTotal = ref(0), entriesLoading = ref(false), entriesError = ref('')
let disposed = false, listRequest = 0, entriesRequest = 0
const validId = id => (typeof id === 'string' || Number.isSafeInteger(id)) && /^[1-9]\d*$/.test(String(id))
const statusLabel = status => ({ 0: '已冻结', 1: '正常' }[status] || '未知状态')
const signedFen = value => `${Number.isSafeInteger(value) && value >= 0 ? '+' : ''}${formatFen(value)}`
function maskedMobile(value) {
  if (typeof value !== 'string') return '—'
  if (/^1\d{10}$/.test(value)) return `${value.slice(0, 3)}****${value.slice(-4)}`
  return /^1\d{2}\*{4}\d{4}$/.test(value) ? value : '—'
}
function readPage(data, idField) {
  if (!Array.isArray(data?.content) || data.content.length > 15 || !Number.isSafeInteger(data.totalElements) || data.totalElements < data.content.length || !Number.isSafeInteger(data.number) || data.number < 0 || data.content.some(row => !validId(row?.[idField]))) throw new Error('Invalid page')
  return data
}
function denyIfForbidden(error) {
  const code = Number(error?.response?.data?.code ?? error?.code), status = error?.response?.status
  if ([401, 403].includes(code) || [401, 403].includes(status)) accessDenied.value = true
}
async function load(p = page.value) {
  if (disposed || !canManage.value) return
  const requestId = ++listRequest
  rows.value = []; total.value = 0; listError.value = ''; loading.value = false
  const userId = query.userId.trim()
  if ((userId && !validId(userId)) || !Number.isSafeInteger(p) || p < 1) { listError.value = '用户 ID 和页码必须为正整数。'; return }
  page.value = p; loading.value = true
  try {
    const data = readPage(await adminApi.wallets({ userId: userId || undefined, page: p - 1, size: 15 }), 'userId')
    if (disposed || requestId !== listRequest || !canManage.value) return
    rows.value = data.content; total.value = data.totalElements; page.value = data.number + 1
  } catch (error) {
    if (!disposed && requestId === listRequest) { listError.value = '钱包加载失败或返回数据无效，请重试。'; denyIfForbidden(error) }
  } finally { if (requestId === listRequest) loading.value = false }
}
function resetFilters() {
  query.userId = ''
  if (route.query.userId !== undefined) { const { userId, ...rest } = route.query; return router.replace({ path: route.path, query: rest }) }
  return load(1)
}
function openEntries(userId) {
  if (disposed || !canManage.value || !validId(userId)) return
  entriesUserId.value = String(userId); entriesOpen.value = true
  return loadEntries(1)
}
async function loadEntries(p = entriesPage.value) {
  if (disposed || !canManage.value || !entriesOpen.value) return
  const requestId = ++entriesRequest, userId = entriesUserId.value
  entries.value = []; entriesTotal.value = 0; entriesError.value = ''; entriesLoading.value = false
  if (!validId(userId) || !Number.isSafeInteger(p) || p < 1) { entriesError.value = '用户 ID 或页码无效。'; return }
  entriesPage.value = p; entriesLoading.value = true
  try {
    const data = readPage(await adminApi.walletEntries(userId, { page: p - 1, size: 15 }), 'id')
    if (disposed || requestId !== entriesRequest || !canManage.value || !entriesOpen.value) return
    entries.value = data.content; entriesTotal.value = data.totalElements; entriesPage.value = data.number + 1
  } catch (error) {
    if (!disposed && requestId === entriesRequest) { entriesError.value = '钱包流水加载失败或数据无效，请重试。'; denyIfForbidden(error) }
  } finally { if (requestId === entriesRequest) entriesLoading.value = false }
}
function closeEntries() { ++entriesRequest; entriesOpen.value = false; entries.value = []; entriesTotal.value = 0; entriesLoading.value = false }
function viewUser(userId) {
  if (!disposed && canManage.value && canViewUsers.value && validId(userId)) router.push({ path: '/users', query: { userId: String(userId) } })
}
function viewWithdrawals(userId) {
  if (!disposed && canManage.value && canViewWithdrawals.value && (userId === undefined || validId(userId))) router.push({ path: '/withdrawals', query: userId === undefined ? {} : { userId: String(userId) } })
}
watch(entriesOpen, open => { if (!open) closeEntries() }, { flush: 'sync' })
watch([() => route.query.userId, canManage], ([id, allowed]) => {
  query.userId = id === undefined ? '' : typeof id === 'string' && id.trim() ? id : '无效 ID'
  if (allowed) load(1)
  else { ++listRequest; rows.value = []; total.value = 0; loading.value = false; closeEntries() }
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; ++listRequest; closeEntries() })
</script>

<style scoped>
.wallet-note { display: flex; gap: 14px; padding: 20px 22px; margin-bottom: 22px; background: #fff8f2; border: 1px solid #f0e2d6; border-radius: 14px; color: #8c431f; }
.wallet-note svg { flex-shrink: 0; margin-top: 2px; }.wallet-note strong { font-size: 13px; }.wallet-note p { color: #756454; font-size: 12px; line-height: 1.9; margin-top: 7px; }
.notice { margin-bottom: 18px; }.money { font-variant-numeric: tabular-nums; font-weight: 550; }.debt { color: #b42318; }
.table-note, .ledger-heading p { color: var(--ink-secondary); font-size: 12px; line-height: 1.8; }.table-note { margin-top: 18px; }
.ledger-heading { display: flex; gap: 18px; justify-content: space-between; align-items: center; margin-bottom: 20px; }.ledger-heading > div { display: flex; flex-shrink: 0; }
@media(max-width:600px) { .wallet-note { padding: 16px; }.ledger-heading { flex-direction: column; align-items: flex-start; } }
</style>
