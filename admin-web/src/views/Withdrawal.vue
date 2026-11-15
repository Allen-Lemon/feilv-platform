<template>
  <div>
    <div class="page-head"><div><span class="eyebrow">WITHDRAWAL REVIEW</span><h1>提现审核</h1><p>核查提现申请与审核留痕。批准和驳回均为模拟账务，不触发真实出款。</p></div><el-button v-if="canManage && canViewWallets" @click="viewWallet()">用户钱包</el-button></div>
    <el-alert title="仅 MOCK 审核：批准只记模拟提现，不会实际出款。驳回将冻结金额返还可用余额；如有欠款，先抵欠款。" type="warning" show-icon :closable="false" class="notice" />
    <el-alert v-if="!canManage" :title="accessDenied ? '提现审核访问已被拒绝或登录失效，请重新登录或联系管理员确认授权。' : '正在确认权限，或当前账户未获提现审核权限。'" type="warning" :closable="false" show-icon />
    <div v-else class="panel">
      <el-form inline class="filter-row" :disabled="reviewBusy" @submit.prevent="load(1)">
        <el-form-item label="审核状态"><el-select v-model="query.status" placeholder="全部状态" style="width:160px" @change="load(1)"><el-option label="全部" value="" /><el-option v-for="status in statuses" :key="status.code" :label="status.label" :value="status.code" /></el-select></el-form-item>
        <el-form-item label="用户 ID"><el-input v-model="query.userId" clearable placeholder="输入完整用户 ID" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="loading">查询</el-button><el-button @click="resetFilters">重置</el-button>
      </el-form>
      <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="notice"><el-button link :disabled="reviewBusy" @click="load()">重试</el-button></el-alert>
      <el-table :data="rows" row-key="id" stripe v-loading="loading">
        <el-table-column prop="id" label="提现 ID" width="100" /><el-table-column prop="userId" label="用户 ID" width="100" />
        <el-table-column label="申请金额(元)" width="155" align="right"><template #default="{ row }"><span class="money">{{ formatFen(row.amountFen) }}</span></template></el-table-column>
        <el-table-column label="渠道" width="105"><template #default="{ row }"><el-tag type="info">{{ row.channel || '未知' }}</el-tag></template></el-table-column>
        <el-table-column label="审核状态" width="115"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="requestId" label="请求编号" min-width="210" show-overflow-tooltip /><el-table-column prop="createdTime" label="创建时间" width="180" />
        <el-table-column label="审核时间" width="180"><template #default="{ row }">{{ row.reviewedTime || '—' }}</template></el-table-column>
        <el-table-column label="审核原因" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ row.reason || '—' }}</template></el-table-column>
        <el-table-column label="操作" width="115" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="reviewBusy || loading" @click="openDetail(row)">{{ isReviewable(row) ? '详情 / 审核' : '详情' }}</el-button></template></el-table-column>
        <template #empty>{{ loading ? '正在加载提现申请…' : listError ? '未能取得提现数据' : '暂无符合条件的提现申请' }}</template>
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="15" :current-page="page" :disabled="reviewBusy" @current-change="load" />
      <p class="table-note">仅待审核且渠道为 MOCK 的有效记录可审核。终态不可重复操作；账务处理与欠款抵扣以服务端流水为准。</p>
    </div>

    <el-dialog v-model="detailOpen" title="提现详情与审核" width="min(720px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!reviewBusy" :show-close="!reviewBusy" :before-close="beforeCloseDetail" @closed="clearClosedDetail">
      <template v-if="detail && canManage">
        <div class="review-heading"><span class="review-icon"><AppIcon name="withdrawal" :size="24" /></span><div><h3>提现申请 #{{ detail.id }}</h3><p>用户 ID {{ detail.userId }} · {{ detail.channel || '未知渠道' }}</p></div><el-tag :type="statusType(detail.status)">{{ statusLabel(detail.status) }}</el-tag></div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="申请金额">¥{{ formatFen(detail.amountFen) }}</el-descriptions-item><el-descriptions-item label="渠道">{{ detail.channel || '—' }}</el-descriptions-item>
          <el-descriptions-item label="请求编号" :span="2"><span class="multiline">{{ detail.requestId || '—' }}</span></el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createdTime || '—' }}</el-descriptions-item><el-descriptions-item label="审核时间">{{ detail.reviewedTime || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审核人 ID" :span="2">{{ detail.reviewedBy ?? '—' }}</el-descriptions-item><el-descriptions-item label="审核原因" :span="2"><div class="multiline">{{ detail.reason || '—' }}</div></el-descriptions-item>
        </el-descriptions>
        <div v-if="canViewWallets" class="wallet-link"><el-button link type="primary" :disabled="reviewBusy" @click="viewWallet(detail.userId)">查看该用户钱包与流水</el-button></div>
        <el-alert v-if="reviewError" :title="reviewError" type="error" :closable="false" class="review-notice"><el-button v-if="needsRefresh" link :disabled="reviewBusy || loading" @click="refreshDetail">刷新并核对状态</el-button></el-alert>
        <template v-if="isReviewable(detail)">
          <el-alert title="批准仅记录 MOCK 提现，不会实际出款；驳回释放冻结金额，有欠款先抵欠款，余款返还可用余额。" type="warning" :closable="false" show-icon class="review-notice" />
          <el-form label-position="top" :disabled="reviewBusy || needsRefresh || loading">
            <el-form-item label="审核决定" required><el-radio-group v-model="reviewForm.decision"><el-radio value="APPROVED">批准（仅 MOCK，不实际出款）</el-radio><el-radio value="REJECTED">驳回</el-radio></el-radio-group></el-form-item>
            <el-form-item label="审核原因（1–255 字）" required><el-input v-model="reviewForm.reason" type="textarea" :rows="3" maxlength="255" show-word-limit placeholder="请记录核查依据；批准与驳回均需填写" /></el-form-item>
          </el-form>
        </template>
        <el-alert v-else title="该申请已进入终态，或金额、对象、渠道信息不完整，不可审核。" type="info" :closable="false" class="review-notice" />
      </template>
      <template #footer><el-button :disabled="reviewBusy" @click="detailOpen = false">关闭</el-button><el-button v-if="detail && canManage && isReviewable(detail)" type="primary" :loading="reviewBusy" :disabled="needsRefresh || loading" @click="submitReview">确认审核</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
import { formatFen, isFen } from '../utils/money'
import AppIcon from '../components/AppIcon.vue'

const route = useRoute(), router = useRouter()
const menuCodes = inject('adminMenuCodes', ref([])), accessDenied = ref(false)
const canManage = computed(() => !accessDenied.value && menuCodes.value.includes('withdrawals'))
const canViewWallets = computed(() => menuCodes.value.includes('wallets'))
const statuses = [{ code: 'PENDING', label: '待审核' }, { code: 'APPROVED', label: '已批准' }, { code: 'REJECTED', label: '已驳回' }]
const statusLabel = status => statuses.find(item => item.code === status)?.label || status || '未知状态'
const statusType = status => ({ PENDING: 'warning', APPROVED: 'success', REJECTED: 'info' }[status] || 'info')
const validId = id => (typeof id === 'string' || Number.isSafeInteger(id)) && /^[1-9]\d*$/.test(String(id))
const query = reactive({ status: '', userId: '' })
const rows = ref([]), page = ref(1), total = ref(0), loading = ref(false), listError = ref('')
const detail = ref(null), detailOpen = ref(false), needsRefresh = ref(false), reviewError = ref(''), reviewBusy = ref(false)
const reviewForm = reactive({ decision: 'APPROVED', reason: '' })
// 不确定的请求必须重新读取服务端状态；关闭后重新打开同一旧行也不能绕过。
const uncertainIds = new Set(), terminalIds = new Set()
let disposed = false, listRequest = 0, detailSession = 0
const isReviewable = row => row?.status === 'PENDING' && !terminalIds.has(String(row.id)) && validId(row.id) && validId(row.userId) && isFen(row.amountFen) && row.amountFen > 0 && row.channel === 'MOCK' && typeof row.requestId === 'string' && !!row.requestId.trim()
function denyIfForbidden(error) {
  const code = Number(error?.response?.data?.code ?? error?.code), status = error?.response?.status
  if ([401, 403].includes(code) || [401, 403].includes(status)) accessDenied.value = true
}
function readPage(data) {
  if (!Array.isArray(data?.content) || data.content.length > 15 || !Number.isSafeInteger(data.totalElements) || data.totalElements < data.content.length || !Number.isSafeInteger(data.number) || data.number < 0 || data.content.some(row => !validId(row?.id) || !validId(row?.userId))) throw new Error('Invalid page')
  return data
}
async function load(p = page.value) {
  if (disposed || !canManage.value) return false
  const requestId = ++listRequest
  rows.value = []; total.value = 0; loading.value = false; listError.value = ''
  if (detailOpen.value) { ++detailSession; needsRefresh.value = true; reviewError.value = '列表或筛选已变化，请刷新并核对当前申请状态后再审核。' }
  const userId = query.userId.trim()
  if ((userId && !validId(userId)) || (query.status && !statuses.some(item => item.code === query.status)) || !Number.isSafeInteger(p) || p < 1) {
    listError.value = '请填写正整数用户 ID、有效状态和页码。'; return false
  }
  page.value = p; loading.value = true
  try {
    const data = readPage(await adminApi.withdrawals({ status: query.status || undefined, userId: userId || undefined, page: p - 1, size: 15 }))
    if (disposed || requestId !== listRequest || !canManage.value) return false
    rows.value = data.content; total.value = data.totalElements; page.value = data.number + 1
    for (const row of data.content) {
      if (!reviewBusy.value) uncertainIds.delete(String(row.id))
      if (['APPROVED', 'REJECTED'].includes(row.status)) terminalIds.add(String(row.id))
    }
    if (detailOpen.value && detail.value && !reviewBusy.value) {
      const current = rows.value.find(row => String(row.id) === String(detail.value.id))
      if (current) { detail.value = { ...current }; needsRefresh.value = false; reviewError.value = ''; resetReviewForm() }
      else { needsRefresh.value = true; reviewError.value = '该申请不在当前筛选页，请重新查询并打开详情后核对。' }
    }
    return true
  } catch (error) {
    if (!disposed && requestId === listRequest) {
      listError.value = '提现列表加载失败或返回数据无效，请重试。'
      if (detailOpen.value) { needsRefresh.value = true; reviewError.value = '列表读取失败，必须刷新核对最新状态后才能审核。' }
      denyIfForbidden(error)
    }
    return false
  } finally { if (requestId === listRequest) loading.value = false }
}
function resetFilters() {
  if (reviewBusy.value) return
  Object.assign(query, { status: '', userId: '' })
  if (route.query.userId !== undefined) { const { userId, ...rest } = route.query; return router.replace({ path: route.path, query: rest }) }
  return load(1)
}
function resetReviewForm() { Object.assign(reviewForm, { decision: 'APPROVED', reason: '' }) }
function openDetail(row) {
  if (disposed || !canManage.value || reviewBusy.value || loading.value) return
  const current = rows.value.find(item => String(item.id) === String(row?.id))
  if (!current) return
  ++detailSession; detail.value = { ...current }; detailOpen.value = true; needsRefresh.value = uncertainIds.has(String(current.id)); resetReviewForm()
  reviewError.value = needsRefresh.value ? '上次审核结果未确认，请刷新核对服务端状态，勿重复提交。' : ''
}
function beforeCloseDetail(done) { if (!reviewBusy.value) done() }
function clearClosedDetail() { if (!detailOpen.value) detail.value = null }
const refreshDetail = () => !reviewBusy.value && load()
function buildReview() {
  if (!detail.value || !isReviewable(detail.value)) throw new Error('仅可审核信息完整且渠道为 MOCK 的待审核申请。')
  const { decision } = reviewForm, reason = reviewForm.reason.trim()
  if (!['APPROVED', 'REJECTED'].includes(decision)) throw new Error('请选择批准或驳回。')
  if (!reason || reason.length > 255) throw new Error('请填写 1–255 字的审核原因。')
  return { decision, reason }
}
async function submitReview() {
  if (disposed || !canManage.value || reviewBusy.value || needsRefresh.value || loading.value || !detailOpen.value || !isReviewable(detail.value)) return
  reviewBusy.value = true; reviewError.value = ''
  const row = { ...detail.value }, session = detailSession
  let sent = false, completed = false
  try {
    const payload = buildReview()
    const effect = payload.decision === 'APPROVED' ? '批准仅记 MOCK 提现，不会实际出款。' : '驳回后冻结金额先抵欠款，余款返还可用余额，不会出款。'
    await ElMessageBox.confirm(`提现 ID ${row.id} / 用户 ID ${row.userId} / 请求编号 ${row.requestId}，金额 ¥${formatFen(row.amountFen)}，渠道 MOCK。${effect}审核原因：${payload.reason}。确认${statusLabel(payload.decision)}？`, '确认提现审核（MOCK）', { type: 'warning', confirmButtonText: payload.decision === 'APPROVED' ? '批准（不实际出款）' : '确认驳回', cancelButtonText: '取消' })
    if (disposed || !canManage.value || !detailOpen.value || session !== detailSession || loading.value || needsRefresh.value || !isReviewable(detail.value)) return
    if (JSON.stringify(buildReview()) !== JSON.stringify(payload) || JSON.stringify(detail.value) !== JSON.stringify(row)) throw new Error('审核信息已变化，请重新核对并确认。')
    sent = true; uncertainIds.add(String(row.id))
    const result = await adminApi.reviewWithdrawal(row.id, payload)
    if (String(result?.id) !== String(row.id) || String(result.userId) !== String(row.userId) || result.amountFen !== row.amountFen || result.channel !== 'MOCK' || result.status !== payload.decision || result.requestId !== row.requestId) throw new Error('Invalid review result')
    terminalIds.add(String(row.id)); uncertainIds.delete(String(row.id))
    if (disposed || !canManage.value || session !== detailSession) return
    detailOpen.value = false; detail.value = null; completed = true
    ElMessage.success(payload.decision === 'APPROVED' ? '已批准 MOCK 提现，仅记模拟账，未实际出款。' : '已驳回；冻结金额已按规则先抵欠款，余款返还可用余额，未出款。')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close' && !disposed && canManage.value && session === detailSession) {
      reviewError.value = sent ? '审核结果未确认（可能已由其他管理员处理），请刷新核对最新状态，勿重复操作。' : error instanceof Error ? error.message : '审核未完成，请重试。'
      if (sent) needsRefresh.value = true
    }
    if (error !== 'cancel' && error !== 'close' && !disposed) denyIfForbidden(error)
  } finally { reviewBusy.value = false }
  if (completed) await load()
}
function viewWallet(userId) {
  if (!disposed && canManage.value && canViewWallets.value && !reviewBusy.value && (userId === undefined || validId(userId))) router.push({ path: '/wallets', query: userId === undefined ? {} : { userId: String(userId) } })
}
watch(detailOpen, open => { if (!open) ++detailSession }, { flush: 'sync' })
watch([() => route.query.userId, canManage], ([id, allowed]) => {
  query.userId = id === undefined ? '' : typeof id === 'string' && id.trim() ? id : '无效 ID'
  if (allowed) load(1)
  else { ++listRequest; ++detailSession; rows.value = []; total.value = 0; loading.value = false; detailOpen.value = false; detail.value = null }
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; ++listRequest; ++detailSession; detailOpen.value = false; detail.value = null })
</script>

<style scoped>
.notice { margin-bottom: 20px; }.money { font-variant-numeric: tabular-nums; font-weight: 550; }.table-note { margin-top: 18px; font-size: 12px; color: var(--ink-secondary); line-height: 1.8; }
.review-heading { display: flex; align-items: center; gap: 13px; margin-bottom: 24px; }.review-icon { display: inline-flex; align-items: center; justify-content: center; width: 48px; height: 48px; background: var(--brand-soft); color: #a44220; border-radius: 14px; flex-shrink: 0; }.review-heading h3 { font-size: 16px; }.review-heading p { font-size: 12px; color: var(--ink-secondary); margin-top: 6px; }.review-heading > .el-tag { margin-left: auto; }
.wallet-link { margin: 14px 0; }.review-notice { margin: 20px 0; }.multiline { white-space: pre-wrap; overflow-wrap: anywhere; }.el-radio-group { gap: 8px; }.el-radio { margin-right: 16px; height: auto; min-height: 32px; }
@media(max-width:600px) { .review-heading { gap: 10px; }.review-heading h3 { font-size: 14px; } }
</style>
