<template>
  <div>
    <div class="page-head"><div><span class="eyebrow">CUSTOMERS</span><h1>用户管理</h1><p>查看下单用户信息，管理账户状态。手机号仅展示脱敏信息。</p></div></div>
    <div class="panel">
      <el-form inline class="filter-row" @submit.prevent="load(1)">
        <el-form-item label="昵称 / 手机号">
          <el-input v-model="query.keyword" clearable placeholder="昵称或完整手机号" style="width: 250px" />
        </el-form-item>
        <el-form-item label="账户状态">
          <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 140px" @change="load(1)">
            <el-option label="正常" :value="1" /><el-option label="已冻结" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="手机绑定">
          <el-select v-model="query.mobileBound" clearable placeholder="全部" style="width: 140px" @change="load(1)">
            <el-option label="已绑定" :value="true" /><el-option label="未绑定" :value="false" />
          </el-select>
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form>
      <p class="table-caption search-hint">昵称支持搜索；手机号请填写完整号码进行精准查询，不支持脱敏号码查询。</p>
      <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="error-alert">
        <el-button link type="primary" @click="load()">重试</el-button>
      </el-alert>
      <el-table :data="rows" row-key="userId" stripe v-loading="loading">
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }"><div class="customer-cell"><el-avatar :size="38" :src="row.avatarUrl || ''">{{ (row.nickname || '用').slice(0, 1) }}</el-avatar><span>{{ row.nickname || '未设置昵称' }}</span></div></template>
        </el-table-column>
        <el-table-column prop="userId" label="用户 ID" min-width="100" />
        <el-table-column prop="role" label="角色" min-width="110" />
        <el-table-column label="手机号（脱敏）" min-width="150"><template #default="{ row }">{{ row.mobile || '—' }}</template></el-table-column>
        <el-table-column label="手机绑定" width="110"><template #default="{ row }"><el-tag :type="row.mobileBound ? 'success' : 'info'">{{ row.mobileBound ? '已绑定' : '未绑定' }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="createdTime" label="注册时间" min-width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.userId)">详情</el-button>
            <el-button link :type="row.status === 1 ? 'danger' : 'success'" :disabled="statusBusy !== null || ![0, 1].includes(row.status)" :loading="statusBusy === row.userId" @click="changeStatus(row)">{{ row.status === 1 ? '冻结' : '解冻' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="15" :current-page="page" @current-change="load" />
    </div>

    <el-dialog v-model="detailOpen" title="用户详情" width="600px" @close="closeDetail">
      <div v-loading="detailLoading" class="detail-body">
        <el-alert v-if="detailError" :title="detailError" type="error" show-icon :closable="false">
          <el-button v-if="validUserId(detailId)" link type="primary" @click="openDetail(detailId)">重试</el-button>
        </el-alert>
        <template v-else-if="detail">
          <div class="customer-cell detail-identity"><el-avatar :size="52" :src="detail.avatarUrl || ''">{{ (detail.nickname || '用').slice(0, 1) }}</el-avatar><strong>{{ detail.nickname || '未设置昵称' }}</strong></div>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="用户 ID">{{ detail.userId }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ detail.role || '—' }}</el-descriptions-item>
            <el-descriptions-item label="手机号（脱敏）">{{ detail.mobile || '—' }}</el-descriptions-item>
            <el-descriptions-item label="手机绑定">{{ detail.mobileBound ? '已绑定' : '未绑定' }}</el-descriptions-item>
            <el-descriptions-item label="账户状态"><el-tag :type="detail.status === 1 ? 'success' : 'danger'">{{ statusLabel(detail.status) }}</el-tag></el-descriptions-item>
            <el-descriptions-item label="注册时间">{{ detail.createdTime || '—' }}</el-descriptions-item>
          </el-descriptions>
        </template>
      </div>
      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
        <el-button v-if="canViewOrders && detail" :disabled="detailLoading" @click="viewOrders">查看该用户订单</el-button>
        <el-button v-if="detail" :type="detail.status === 1 ? 'danger' : 'success'" :disabled="detailLoading || statusBusy !== null || ![0, 1].includes(detail.status)" :loading="statusBusy === detail.userId" @click="changeStatus(detail)">{{ detail.status === 1 ? '冻结账户' : '解冻账户' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'

const route = useRoute()
const router = useRouter()
const menuCodes = inject('adminMenuCodes', ref([]))
const canViewOrders = computed(() => menuCodes.value.includes('orders'))
const query = reactive({ keyword: '', status: '', mobileBound: '' })
const rows = ref([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const listError = ref('')
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detailId = ref('')
const detail = ref(null)
const statusBusy = ref(null)
let listRequest = 0
let detailRequest = 0
let disposed = false

const validUserId = id => typeof id === 'string' && /^[1-9]\d*$/.test(id)
const statusLabel = status => ({ 0: '已冻结', 1: '正常' }[status] || '未知状态')

async function load(p = page.value) {
  const requestId = ++listRequest
  page.value = p
  loading.value = true
  listError.value = ''
  try {
    const data = await adminApi.customerUsers({
      page: p - 1,
      keyword: query.keyword.trim() || undefined,
      status: query.status === 0 || query.status === 1 ? query.status : undefined,
      mobileBound: typeof query.mobileBound === 'boolean' ? query.mobileBound : undefined
    })
    if (requestId !== listRequest) return
    rows.value = data.content || []
    total.value = data.totalElements || 0
    page.value = (data.number ?? p - 1) + 1
  } catch {
    if (requestId !== listRequest) return
    rows.value = []
    total.value = 0
    listError.value = '用户列表加载失败，请重试。'
  } finally {
    if (requestId === listRequest) loading.value = false
  }
}

function resetFilters() {
  Object.assign(query, { keyword: '', status: '', mobileBound: '' })
  return load(1)
}

async function openDetail(id) {
  const requestId = ++detailRequest
  detailId.value = typeof id === 'number' ? String(id) : id
  detailOpen.value = true
  detail.value = null
  detailError.value = ''
  detailLoading.value = false
  if (!validUserId(detailId.value)) {
    detailError.value = '用户 ID 无效，请检查链接。'
    return
  }
  detailLoading.value = true
  try {
    const data = await adminApi.customerUser(detailId.value)
    if (requestId === detailRequest) {
      if (!data?.userId) throw new Error('Invalid user detail')
      detail.value = data
    }
  } catch {
    if (requestId === detailRequest) detailError.value = '用户详情加载失败，用户可能不存在或无访问权限，请重试。'
  } finally {
    if (requestId === detailRequest) detailLoading.value = false
  }
}

function closeDetail() {
  ++detailRequest
  detailOpen.value = false
  detailLoading.value = false
  detail.value = null
  if (route.query.userId !== undefined) {
    const { userId, ...query } = route.query
    router.replace({ path: route.path, query })
  }
}

async function changeStatus(user) {
  if (disposed || statusBusy.value !== null || ![0, 1].includes(user.status)) return
  const status = user.status === 1 ? 0 : 1
  const action = status === 0 ? '冻结' : '解冻'
  statusBusy.value = user.userId
  try {
    await ElMessageBox.confirm(`确认${action}用户「${user.nickname || user.userId}」（ID：${user.userId}）的账户？`, `${action}账户`, {
      type: 'warning', confirmButtonText: `确认${action}`, cancelButtonText: '取消'
    })
    if (disposed) return
    await adminApi.changeCustomerStatus(user.userId, status)
    if (disposed) return
    if (String(detail.value?.userId) === String(user.userId)) detail.value = { ...detail.value, status }
    ElMessage.success(`账户已${action}`)
    await load()
  } catch (error) {
    // API 失败由统一请求封装提示；取消确认不发送请求。
    if (error !== 'cancel' && error !== 'close') listError.value = `${action}未完成，请刷新确认账户状态后重试。`
  } finally {
    statusBusy.value = null
  }
}

function viewOrders() {
  if (canViewOrders.value && detail.value && !detailLoading.value) {
    router.push({ path: '/orders', query: { userId: String(detail.value.userId) } })
  }
}

watch(() => route.query.userId, id => {
  if (id !== undefined) openDetail(id)
  else if (detailOpen.value) closeDetail()
}, { immediate: true })
onMounted(() => load(1))
onBeforeUnmount(() => { disposed = true; ++listRequest; ++detailRequest })
</script>

<style scoped>
.customer-cell { display: flex; align-items: center; gap: 12px; overflow-wrap: anywhere; }
.customer-cell .el-avatar { flex-shrink: 0; background: var(--brand-soft); color: var(--brand); }
.search-hint, .error-alert { margin-bottom: 18px; }
.detail-body { min-height: 150px; }
.detail-identity { margin-bottom: 22px; }
</style>
