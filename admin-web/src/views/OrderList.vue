<template>
  <div>
    <div class="page-head"><div><span class="eyebrow">ORDERS</span><h1>订单管理</h1><p>查看订单与下单账户。下单用户信息独立于寄件、收件联系人，手机号仅展示脱敏信息。</p></div></div>
    <div class="panel">
      <el-form inline class="filter-row" @submit.prevent="load(1)">
        <el-form-item label="订单状态">
          <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 160px" @change="load(1)">
            <el-option v-for="s in statuses" :key="s.code" :label="s.label" :value="s.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="下单用户 ID"><el-input v-model="query.userId" clearable placeholder="全部用户" style="width: 180px" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form>
      <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="error-alert"><el-button link type="primary" @click="load()">重试</el-button></el-alert>
      <el-table :data="rows" row-key="id" stripe v-loading="loading">
        <el-table-column prop="orderNo" label="订单号" width="210" />
        <el-table-column label="下单用户（非寄收联系人）" min-width="250">
          <template #default="{ row }">
            <div class="customer-cell">
              <el-avatar :size="38" :src="row.userAvatarUrl || ''">{{ (row.userNickname || '用').slice(0, 1) }}</el-avatar>
              <div class="customer-info">
                <el-button v-if="canViewUsers && row.userId != null" link type="primary" class="customer-link" @click="viewUser(row)">{{ row.userNickname || '未设置昵称' }}</el-button>
                <strong v-else>{{ row.userNickname || (row.userId != null ? '未设置昵称' : '未关联用户') }}</strong>
                <small>ID：{{ row.userId ?? '—' }}</small>
                <small>手机号：{{ row.userMobile || '—' }} · {{ row.userMobileBound === true ? '已绑定' : row.userMobileBound === false ? '未绑定' : '绑定信息未提供' }}</small>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="orderType" label="类型" width="120"><template #default="{ row }"><div>{{ typeLabel(row.orderType) }}</div><el-tag v-if="isServiceTask(row)" size="small">{{ taskModeLabel(row.taskMode) }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="tagType(row.status)">{{ row.statusLabel || statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="取件地址 / 服务地点" min-width="180" show-overflow-tooltip>
          <template #default="{ row }"><div>{{ orderLocation(row) }}</div><small v-if="orderContact(row)" class="text-muted">{{ isServiceTask(row) ? '任务联系人' : '寄件联系人' }}：{{ orderContact(row) }}</small></template>
        </el-table-column>
        <el-table-column label="送达地址 / 服务时间" min-width="180" show-overflow-tooltip>
          <template #default="{ row }"><div>{{ orderDestination(row) }}</div><small v-if="!isServiceTask(row) && row.toContact" class="text-muted">收件联系人：{{ row.toContact }}</small></template>
        </el-table-column>
        <el-table-column label="服务费(元)" width="110"><template #default="{ row }">{{ row.realFeeFen == null ? '—' : (row.realFeeFen / 100).toFixed(2) }}</template></el-table-column>
        <el-table-column label="商品采购预算(元)" min-width="190"><template #default="{ row }"><template v-if="row.orderType === 'BUY'"><div>{{ goodsBudgetText(row) }}</div><small class="text-muted">商品款另行结算，不计服务费</small></template><span v-else>—</span></template></el-table-column>
        <el-table-column prop="createdTime" label="下单时间" width="180" />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <div class="order-actions">
              <el-button size="small" type="danger" plain :disabled="actionBusy !== null || row.status === 'CANCELED' || row.status === 'FINISHED'" :loading="actionBusy === row.id" @click="cancel(row)">干预取消</el-button>
              <el-button v-if="row.status === 'DELIVERED'" size="small" type="success" plain :disabled="actionBusy !== null" @click="complete(row)">强制完成</el-button>
              <el-button size="small" plain @click="openTransfers(row)">转派记录</el-button>
              <el-button v-if="canViewComplaints" size="small" type="primary" plain @click="viewComplaints(row)">投诉管理</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="15" :current-page="page" @current-change="load" />
    </div>
    <el-dialog v-model="transfersOpen" title="订单转派记录" width="820px" @close="closeTransfers">
      <el-alert title="仅未取件／未开始服务时可由原骑手发起，目标骑手确认后才交接。后台仅查看，不代替对方接受；收入和投诉责任归最终承接者。" type="info" :closable="false" class="error-alert" />
      <el-alert v-if="transfersError" :title="transfersError" type="error" :closable="false"><el-button link @click="loadTransfers()">重试</el-button></el-alert>
      <el-table :data="transferRows" v-loading="transfersLoading" row-key="id" stripe>
        <el-table-column prop="id" label="申请ID" width="85" />
        <el-table-column label="转出骑手" min-width="125"><template #default="{ row }">{{ row.fromRiderName || '骑手' }}（{{ row.fromRiderId }}）</template></el-table-column>
        <el-table-column label="接收骑手" min-width="125"><template #default="{ row }">{{ row.toRiderName || '骑手' }}（{{ row.toRiderId }}）</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag>{{ transferStatus(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="reason" label="发起原因" min-width="170" show-overflow-tooltip />
        <el-table-column prop="responseReason" label="处理说明" min-width="170" show-overflow-tooltip />
        <el-table-column prop="createdTime" label="发起时间" width="180" />
        <el-table-column prop="handledTime" label="处理时间" width="180" />
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="transferTotal" :page-size="15" :current-page="transferPage" @current-change="loadTransfers" />
      <template #footer><el-button @click="transfersOpen = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'

const route = useRoute()
const router = useRouter()
const menuCodes = inject('adminMenuCodes', ref([]))
const canViewUsers = computed(() => menuCodes.value.includes('users'))
const canViewComplaints = computed(() => menuCodes.value.includes('complaints'))
const statuses = [
  { code: 'WAIT_PAY', label: '待支付' }, { code: 'WAIT_GRAB', label: '待接单' },
  { code: 'ACCEPTED', label: '已接单' }, { code: 'PICKING', label: '取件中' },
  { code: 'DELIVERING', label: '配送中' }, { code: 'DELIVERED', label: '已送达' },
  { code: 'FINISHED', label: '已完成' }, { code: 'CANCELED', label: '已取消' }
]
const query = reactive({ status: '', userId: '' })
const rows = ref([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const listError = ref('')
const actionBusy = ref(null)
let listRequest = 0
let disposed = false
const transfersOpen = ref(false), transfersLoading = ref(false), transfersError = ref('')
const transferRows = ref([]), transferTotal = ref(0), transferPage = ref(1), transferOrderId = ref(null)
let transferRequest = 0
const transferStatus = status => ({ PENDING: '待对方确认', ACCEPTED: '已转派', REJECTED: '已拒绝', CANCELED: '已撤销', EXPIRED: '已失效' }[status] || status)
function openTransfers(row) {
  if (disposed || row.id == null) return
  transferOrderId.value = row.id; transfersOpen.value = true; transferRows.value = []; transferTotal.value = 0
  return loadTransfers(1)
}
async function loadTransfers(p = transferPage.value) {
  if (disposed || !transfersOpen.value || transferOrderId.value == null) return
  const request = ++transferRequest
  transfersLoading.value = true; transfersError.value = ''; transferPage.value = p
  try {
    const data = await adminApi.orderTransfers({ page: p - 1, orderId: String(transferOrderId.value) })
    if (disposed || request !== transferRequest) return
    if (!data || !Array.isArray(data.content)) throw new Error('Invalid transfer response')
    transferRows.value = data.content; transferTotal.value = data.totalElements || 0; transferPage.value = (data.number ?? p - 1) + 1
  } catch {
    if (request === transferRequest && !disposed) { transferRows.value = []; transferTotal.value = 0; transfersError.value = '转派记录读取失败，请确认订单权限并重试。' }
  } finally { if (request === transferRequest) transfersLoading.value = false }
}
function closeTransfers() { ++transferRequest; transfersOpen.value = false; transfersLoading.value = false; transferRows.value = []; transfersError.value = '' }

const goodsBudgetText = row => row.goodsBudgetFen == null ? '未记录' : Number.isSafeInteger(row.goodsBudgetFen) && row.goodsBudgetFen >= 1 && row.goodsBudgetFen <= 100000000 ? Math.floor(row.goodsBudgetFen / 100) + '.' + String(row.goodsBudgetFen % 100).padStart(2, '0') : '数据异常'
const typeLabel = type => ({ SEND: '跑腿送', BUY: '跑腿买', ERRAND: '跑腿办', CUSTOM: '自定义任务' }[type] || type)
const taskModeLabel = mode => ({ ONLINE: '线上任务', ONSITE: '上门服务', PART_TIME: '兼职劳务' }[mode] || mode)
const isServiceTask = row => row.orderType === 'CUSTOM' && ['ONLINE', 'ONSITE', 'PART_TIME'].includes(row.taskMode)
// 旧后台订单视图可能只补充 taskMode；上门/兼职的兼容地址仍是单一服务地点，线上绝不读取占位地址。
const orderLocation = row => !isServiceTask(row) ? row.fromAddress || '—' : row.taskMode === 'ONLINE' ? '线上任务（无需地址）' : `${row.taskMode === 'PART_TIME' ? '工作地点' : '服务地点'}：${row.locationAddress || row.fromAddress || '—'}`
const orderDestination = row => !isServiceTask(row) ? row.toAddress || '—' : row.taskMode === 'ONLINE' ? '—' : `${row.taskMode === 'PART_TIME' ? '工作时间' : '服务时间'}：${row.serviceTime || '—'}`
const orderContact = row => isServiceTask(row) ? row.contact : row.fromContact
const statusLabel = status => statuses.find(item => item.code === status)?.label || status
const tagType = status => ({ FINISHED: 'success', CANCELED: 'info', DELIVERING: 'primary', WAIT_GRAB: 'warning' }[status] || '')

async function load(p = page.value) {
  const requestId = ++listRequest
  page.value = p
  listError.value = ''
  const userId = query.userId.trim()
  if (userId && !/^[1-9]\d*$/.test(userId)) {
    rows.value = []
    total.value = 0
    loading.value = false
    listError.value = '请输入有效的下单用户 ID（正整数）。'
    return
  }
  loading.value = true
  try {
    const data = await adminApi.orders({ page: p - 1, status: query.status || undefined, userId: userId || undefined })
    if (requestId !== listRequest) return
    rows.value = data.content || []
    total.value = data.totalElements || 0
    page.value = (data.number ?? p - 1) + 1
  } catch {
    if (requestId !== listRequest) return
    rows.value = []
    total.value = 0
    listError.value = '订单列表加载失败，请重试。'
  } finally {
    if (requestId === listRequest) loading.value = false
  }
}

function resetFilters() {
  Object.assign(query, { status: '', userId: '' })
  if (route.query.userId !== undefined) {
    const { userId, ...query } = route.query
    return router.replace({ path: route.path, query })
  }
  return load(1)
}

function viewUser(row) {
  if (canViewUsers.value && row.userId != null) router.push({ path: '/users', query: { userId: String(row.userId) } })
}
function viewComplaints(row) {
  if (canViewComplaints.value && row.id != null) router.push({ path: '/complaints', query: { orderId: String(row.id) } })
}

async function intervene(row, action) {
  if (disposed || actionBusy.value !== null || ['CANCELED', 'FINISHED'].includes(row.status)) return
  if (action === 'complete' && row.status !== 'DELIVERED') return
  const completing = action === 'complete'
  actionBusy.value = row.id
  try {
    const { value } = await ElMessageBox.prompt(completing ? '请输入强制完成原因' : '请输入干预取消原因（必填并落审计日志）', '订单干预', {
      confirmButtonText: completing ? '确认完成' : '确认取消', cancelButtonText: '关闭',
      inputValidator: value => !!value?.trim() || '原因必填'
    })
    if (disposed) return
    if (completing) await adminApi.completeOrder(row.id, value.trim())
    else await adminApi.cancelOrder(row.id, value.trim())
    if (disposed) return
    ElMessage.success(completing ? '订单已完成' : '订单已取消')
    await load()
  } catch (error) {
    // API 失败由统一请求封装提示；取消输入不发送请求。
    if (error !== 'cancel' && error !== 'close') listError.value = '订单干预未完成，请刷新确认订单状态后重试。'
  } finally {
    actionBusy.value = null
  }
}
const complete = row => intervene(row, 'complete')
const cancel = row => intervene(row, 'cancel')

watch(() => route.query.userId, id => {
  // 数组、空值等非法链接不能悄悄退化成查询全部用户。
  query.userId = id === undefined ? '' : typeof id === 'string' && id.trim() ? id : '无效 ID'
  return load(1)
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; ++listRequest; closeTransfers() })
</script>

<style scoped>
.customer-cell { display: flex; align-items: center; gap: 12px; }
.customer-cell .el-avatar { flex-shrink: 0; background: var(--brand-soft); color: var(--brand); }
.customer-info { min-width: 0; overflow-wrap: anywhere; }
.customer-info strong { font-size: 13px; font-weight: 550; }
.customer-info small { display: block; color: var(--ink-secondary); margin-top: 4px; font-size: 11px; }
.customer-link { min-height: 0; white-space: normal; text-align: left; }
.order-actions { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.order-actions .el-button { margin: 0; }
.error-alert { margin-bottom: 18px; }
</style>
