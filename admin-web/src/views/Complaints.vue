<template>
  <div>
    <div class="page-head"><div><span class="eyebrow">COMPLAINT REVIEW</span><h1>投诉审核</h1><p>按订单核查投诉；审核成立从骑手本单分账扣款，并等额赔付发布者。</p></div></div>
    <el-alert title="仅 MOCK 账务：扣款与赔付只记模拟账，不会真实转账、退款或从骑手其他订单扣钱。" type="warning" show-icon :closable="false" class="notice" />
    <el-alert v-if="!canManage" title="正在确认权限，或当前账户未获投诉审核权限。" type="warning" :closable="false" />
    <div v-else class="panel">
      <el-form inline class="filter-row" @submit.prevent="load(1)">
        <el-form-item label="投诉状态"><el-select v-model="query.status" clearable placeholder="全部状态" style="width:160px" @change="load(1)"><el-option v-for="s in statuses" :key="s.code" :label="s.label" :value="s.code" /></el-select></el-form-item>
        <el-form-item label="订单 ID"><el-input v-model="query.orderId" clearable placeholder="输入订单 ID" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading">查询</el-button><el-button @click="resetFilters">重置</el-button>
      </el-form>
      <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="notice"><el-button link @click="load()">重试</el-button></el-alert>
      <el-table :data="rows" row-key="id" stripe v-loading="loading">
        <el-table-column prop="id" label="投诉 ID" width="95" /><el-table-column prop="orderNo" label="订单号" min-width="200" /><el-table-column prop="orderId" label="订单 ID" width="105" /><el-table-column prop="riderId" label="骑手 ID" width="105" />
        <el-table-column label="类型" width="100"><template #default="{ row }">{{ typeLabel(row.type) }}</template></el-table-column><el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="赔付(元)" width="110"><template #default="{ row }">{{ formatFen(row.compensationFen) }}</template></el-table-column><el-table-column prop="createdTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="reviewBusy" @click="openDetail(row)">{{ isReviewable(row) ? '详情 / 审核' : '详情' }}</el-button></template></el-table-column>
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="15" :current-page="page" @current-change="load" />
    </div>

    <el-dialog v-model="detailOpen" title="投诉详情与审核" width="800px" :close-on-click-modal="false" :show-close="!reviewBusy" :close-on-press-escape="!reviewBusy" :before-close="beforeCloseDetail">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="投诉 ID">{{ detail.id }}</el-descriptions-item><el-descriptions-item label="状态"><el-tag :type="statusType(detail.status)">{{ statusLabel(detail.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="订单号">{{ detail.orderNo || '—' }}</el-descriptions-item><el-descriptions-item label="订单 ID">{{ detail.orderId }}</el-descriptions-item>
          <el-descriptions-item label="发布者 ID">{{ detail.userId ?? '—' }}</el-descriptions-item><el-descriptions-item label="骑手 ID">{{ detail.riderId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="投诉类型">{{ typeLabel(detail.type) }}</el-descriptions-item><el-descriptions-item label="支付渠道">{{ detail.paymentChannel || '—' }}</el-descriptions-item>
          <el-descriptions-item label="描述" :span="2"><div class="multiline">{{ detail.description }}</div></el-descriptions-item><el-descriptions-item label="处理结果" :span="2"><div class="multiline">{{ detail.result || '—' }}</div></el-descriptions-item>
          <el-descriptions-item label="本投诉已扣">¥{{ formatFen(detail.deductedFen) }}</el-descriptions-item><el-descriptions-item label="本投诉赔付">¥{{ formatFen(detail.compensationFen) }}</el-descriptions-item>
          <el-descriptions-item label="本单累计已扣">¥{{ formatFen(detail.orderDeductedFen) }}</el-descriptions-item><el-descriptions-item label="本单剩余可扣上限">¥{{ formatFen(detail.maxDeductibleFen) }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createdTime || '—' }}</el-descriptions-item><el-descriptions-item label="处理时间">{{ detail.handledTime || '—' }}</el-descriptions-item><el-descriptions-item label="处理人 ID">{{ detail.handledBy ?? '—' }}</el-descriptions-item>
        </el-descriptions>
        <h3 class="section-title">审核历史</h3>
        <el-table :data="detail.actions || []" size="small" border><el-table-column prop="action" label="操作" width="120" /><el-table-column prop="detail" label="记录" min-width="200" /><el-table-column prop="operatorId" label="操作人 ID" width="110" /><el-table-column prop="createdTime" label="时间" width="180" /><template #empty>暂无审核记录</template></el-table>
        <el-alert title="只做 MOCK 账务。审核成立即从该骑手本单分账扣款，等额赔付此订单发布者；不进行真实转账。" type="warning" :closable="false" show-icon class="review-notice" />
        <el-alert v-if="reviewError" :title="reviewError" type="error" :closable="false" class="notice"><el-button v-if="needsRefresh" link :disabled="reviewBusy || loading" @click="refreshDetail">刷新后核对</el-button></el-alert>
        <el-form v-if="isReviewable(detail)" label-position="top" :disabled="reviewBusy || needsRefresh || !canManage">
          <el-form-item label="审核决定" required><el-select v-model="reviewForm.decision" style="width:100%"><el-option v-if="detail.status === 'OPEN'" label="受理，转处理中（不扣款）" value="PROCESSING" /><el-option label="投诉成立（扣款并赔付发布者）" value="APPROVED" /><el-option label="投诉驳回（0 元，不扣款）" value="REJECTED" /></el-select></el-form-item>
          <el-form-item label="审核原因" required><el-input v-model="reviewForm.reason" type="textarea" :rows="3" maxlength="255" show-word-limit /></el-form-item>
          <el-form-item label="扣款 / 赔付金额（元，最多 2 位小数）" required><el-input v-model="reviewForm.amountYuan" :disabled="reviewForm.decision !== 'APPROVED'" inputmode="decimal" placeholder="例如 12.34"><template #prefix>¥</template></el-input><p class="field-hint">上限 ¥{{ formatFen(detail.maxDeductibleFen) }}；受理或驳回均为 0 元。只允许整数分，不四舍五入。</p></el-form-item>
        </el-form>
        <el-alert v-else title="该投诉已进入终态或不可审核状态，不可重复审核。" type="info" :closable="false" class="review-notice" />
      </template>
      <template #footer><el-button :disabled="reviewBusy" @click="detailOpen = false">关闭</el-button><el-button v-if="detail && isReviewable(detail)" type="primary" :loading="reviewBusy" :disabled="!canManage || needsRefresh || loading" @click="submitReview">确认审核</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
import { formatFen, isFen, yuanToFen } from '../utils/money'

const route = useRoute(), router = useRouter()
const menuCodes = inject('adminMenuCodes', ref([]))
const canManage = computed(() => menuCodes.value.includes('complaints'))
const statuses = [{ code: 'OPEN', label: '待受理' }, { code: 'PROCESSING', label: '处理中' }, { code: 'APPROVED', label: '审核成立' }, { code: 'REJECTED', label: '已驳回' }, { code: 'DONE', label: '已办结' }]
const statusLabel = status => statuses.find(s => s.code === status)?.label || status
const statusType = status => ({ OPEN: 'warning', PROCESSING: 'primary', APPROVED: 'success', REJECTED: 'info', DONE: 'info' }[status] || 'info')
const typeLabel = type => ({ LOST: '丢件', DAMAGE: '损坏', OVERTIME: '超时', ATTITUDE: '态度', OTHER: '其他' }[type] || type)
const query = reactive({ status: '', orderId: '' })
const rows = ref([]), page = ref(1), total = ref(0), loading = ref(false), listError = ref('')
const detail = ref(null), detailOpen = ref(false), reviewBusy = ref(false), reviewError = ref(''), needsRefresh = ref(false)
const reviewForm = reactive({ decision: 'PROCESSING', reason: '', amountYuan: '0.00' })
const terminalIds = new Set()
// 网络结果不确定的投诉必须重新从服务端取数，不能关闭再打开旧行绕过保护。
const uncertainIds = new Set()
const isReviewable = row => ['OPEN', 'PROCESSING'].includes(row.status) && !terminalIds.has(String(row.id))
let disposed = false, listRequest = 0

async function load(p = page.value) {
  if (!canManage.value || disposed) return false
  const requestId = ++listRequest
  page.value = p; listError.value = ''
  const orderId = query.orderId.trim()
  if (orderId && !/^[1-9]\d*$/.test(orderId)) {
    rows.value = []; total.value = 0; loading.value = false; listError.value = '订单 ID 无效，必须为正整数。'; return false
  }
  loading.value = true
  try {
    const data = await adminApi.complaints({ status: query.status || undefined, orderId: orderId || undefined, page: p - 1 })
    if (requestId !== listRequest) return false
    rows.value = data.content || []; total.value = data.totalElements || 0; page.value = (data.number ?? p - 1) + 1
    for (const row of rows.value) uncertainIds.delete(String(row.id))
    if (detailOpen.value && detail.value && !reviewBusy.value) {
      const current = rows.value.find(row => String(row.id) === String(detail.value.id))
      if (current) { detail.value = current; needsRefresh.value = false; reviewError.value = ''; resetReviewForm() }
      else { needsRefresh.value = true; reviewError.value = '此投诉不在当前筛选页，请重新查询并打开详情后审核。' }
    }
    return true
  } catch {
    if (requestId === listRequest) { rows.value = []; total.value = 0; listError.value = '投诉列表加载失败，请重试。'; if (detailOpen.value) { needsRefresh.value = true; reviewError.value = '列表加载失败，请刷新核对状态与可扣余额后再审核。' } }
    return false
  } finally { if (requestId === listRequest) loading.value = false }
}
function resetFilters() {
  Object.assign(query, { status: '', orderId: '' })
  if (route.query.orderId !== undefined) { const { orderId, ...rest } = route.query; return router.replace({ path: route.path, query: rest }) }
  return load(1)
}
function resetReviewForm() { Object.assign(reviewForm, { decision: detail.value?.status === 'OPEN' ? 'PROCESSING' : 'APPROVED', reason: '', amountYuan: '0.00' }) }
function openDetail(row) {
  if (!canManage.value || reviewBusy.value || loading.value || disposed) return
  detail.value = row; detailOpen.value = true; needsRefresh.value = uncertainIds.has(String(row.id))
  reviewError.value = needsRefresh.value ? '上次审核结果未确认，请刷新核对服务端状态后再操作。' : ''
  resetReviewForm()
}
function beforeCloseDetail(done) { if (!reviewBusy.value) done() }
const refreshDetail = () => load()
function buildReview() {
  if (!detail.value || !isReviewable(detail.value)) throw new Error('该投诉已进入终态，不可重复审核。')
  const { decision } = reviewForm, reason = reviewForm.reason.trim()
  if (!['PROCESSING', 'APPROVED', 'REJECTED'].includes(decision) || (decision === 'PROCESSING' && detail.value.status !== 'OPEN')) throw new Error('审核决定无效，请刷新后核对状态。')
  if (!reason || reason.length > 255) throw new Error('请填写 1–255 字的审核原因。')
  const amountFen = decision === 'APPROVED' ? yuanToFen(reviewForm.amountYuan) : 0
  if (amountFen === null) throw new Error('金额必须为非负数，最多 2 位小数且不能包含小数分。')
  if (decision === 'APPROVED') {
    if (!isFen(detail.value.maxDeductibleFen)) throw new Error('可扣余额缺失或无效，请刷新后审核。')
    if (amountFen > detail.value.maxDeductibleFen) throw new Error('扣款金额不能超过本单剩余可扣上限。')
    if (detail.value.riderId == null || detail.value.userId == null) throw new Error('骑手或发布者信息缺失，不能确认扣款对象。')
  }
  return { decision, reason, amountFen }
}
async function submitReview() {
  if (disposed || !canManage.value || reviewBusy.value || needsRefresh.value || loading.value || !detailOpen.value || !detail.value || !isReviewable(detail.value)) return
  reviewBusy.value = true; reviewError.value = ''
  let sent = false
  try {
    const payload = buildReview(), row = detail.value
    const action = statusLabel(payload.decision)
    await ElMessageBox.confirm(`投诉 ${row.id} / 订单 ${row.orderNo || row.orderId}：${action}。从骑手 ID ${row.riderId ?? '—'} 本单分账扣 ¥${formatFen(payload.amountFen)}，等额赔付发布者 ID ${row.userId ?? '—'}；本单剩余可扣上限 ¥${formatFen(row.maxDeductibleFen)}。${payload.decision === 'APPROVED' ? '成立后扣款即赔付。' : '本次不扣款、不赔付。'}只做 MOCK 账务，不会真实转账。`, '确认投诉审核（MOCK）', { type: 'warning', confirmButtonText: '确认审核', cancelButtonText: '取消' })
    if (disposed || !canManage.value || !detailOpen.value || detail.value !== row || !isReviewable(row)) return
    if (JSON.stringify(buildReview()) !== JSON.stringify(payload)) throw new Error('审核信息已变化，请重新核对并确认。')
    sent = true
    await adminApi.reviewComplaint(row.id, payload)
    if (disposed) return
    if (['APPROVED', 'REJECTED'].includes(payload.decision)) terminalIds.add(String(row.id))
    detailOpen.value = false; detail.value = null
    ElMessage.success(payload.decision === 'APPROVED' ? '投诉成立，已记录 MOCK 扣款及等额赔付，未真实转账。' : `${action}，未扣款。`)
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      reviewError.value = sent ? '审核结果未确认，请刷新核对最新状态、历史与可扣余额，勿重复操作。' : error instanceof Error ? error.message : '审核未完成，请重试。'
      if (sent) { needsRefresh.value = true; if (detail.value) uncertainIds.add(String(detail.value.id)) }
    }
  } finally { reviewBusy.value = false }
}
watch(() => reviewForm.decision, decision => { if (decision !== 'APPROVED') reviewForm.amountYuan = '0.00' }, { flush: 'sync' })
watch([() => route.query.orderId, canManage], ([id, allowed]) => {
  query.orderId = id === undefined ? '' : typeof id === 'string' && id.trim() ? id : '无效 ID'
  if (allowed) load(1)
  else { ++listRequest; rows.value = []; total.value = 0; loading.value = false; detailOpen.value = false; detail.value = null }
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; ++listRequest })
</script>

<style scoped>
.notice { margin-bottom: 18px; }
.review-notice { margin: 20px 0; }
.section-title { margin: 24px 0 12px; font-size: 15px; }
.multiline { white-space: pre-wrap; overflow-wrap: anywhere; }
.field-hint { margin-top: 8px; color: var(--ink-secondary); font-size: 12px; line-height: 1.7; }
</style>
