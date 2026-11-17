<template>
  <div>
    <div class="page-head"><div><span class="eyebrow">CUSTOM TASKS</span><h1>自定义任务</h1><p>代用户发布线上任务、上门服务、兼职劳务、接送或代驾，自定总价。接送、代驾仅限分别获平台授权的骑手承接。创建后由用户在小程序支付，后台不代付。</p></div><el-button v-if="canManage" type="primary" :disabled="createNeedsCheck" @click="openCreate">代用户发布</el-button></div>
    <el-alert v-if="!canManage" title="正在确认权限，或当前账户未获自定义任务权限。" type="warning" :closable="false" show-icon />
    <div v-else class="panel">
      <el-alert v-if="createNeedsCheck" title="上次创建结果未确认，请先关闭发布弹窗并刷新、核对任务列表，避免重复发布。" type="warning" :closable="false" class="notice"><el-button link @click="load(1)">刷新列表以核对</el-button></el-alert>
      <el-tabs v-model="tab">
        <el-tab-pane label="任务列表" name="tasks">
          <el-form inline class="filter-row" @submit.prevent="load(1)">
            <el-form-item label="状态"><el-select v-model="query.status" clearable placeholder="全部状态" style="width:150px" @change="load(1)"><el-option v-for="s in statuses" :key="s.code" :value="s.code" :label="s.label" /></el-select></el-form-item>
            <el-form-item label="发布用户 ID"><el-input v-model="query.userId" clearable placeholder="全部用户" /></el-form-item>
            <el-button native-type="submit" type="primary" :loading="loading">查询</el-button><el-button @click="resetFilters">重置</el-button>
          </el-form>
          <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="notice"><el-button link @click="load()">重试</el-button></el-alert>
          <el-table :data="rows" row-key="id" stripe v-loading="loading">
            <el-table-column prop="id" label="任务 ID" width="90" /><el-table-column prop="orderNo" label="订单号" min-width="200" />
            <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip /><el-table-column label="场景" width="110"><template #default="{ row }">{{ taskModeLabel(row.taskMode) }}</template></el-table-column><el-table-column prop="userId" label="发布用户 ID" width="115" />
            <el-table-column label="标签" min-width="160"><template #default="{ row }"><el-tag v-for="tag in row.tags || []" :key="tag.id" class="task-tag">{{ tag.name }}</el-tag></template></el-table-column>
            <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag>{{ row.statusLabel || statusLabel(row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="金额(元)" width="110"><template #default="{ row }">{{ formatFen(taskPriceFen(row)) }}</template></el-table-column>
            <el-table-column prop="createdTime" label="创建时间" width="180" /><el-table-column label="操作" width="85" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row.orderId)">详情</el-button></template></el-table-column>
          </el-table>
          <el-pagination layout="prev, pager, next, total" :total="total" :page-size="15" :current-page="page" @current-change="load" />
        </el-tab-pane>
        <el-tab-pane label="标签管理" name="tags">
          <div class="panel-heading"><p>为标签选择一个或多个适用场景；待分类或停用标签不在发布页展示，历史引用保留。</p><div><el-button :loading="tagsLoading" @click="loadTags">刷新</el-button><el-button type="primary" :disabled="tagBusy" @click="editTag()">新增标签</el-button></div></div>
          <el-alert v-if="tagsError" :title="tagsError" type="error" :closable="false" class="notice" />
          <el-table :data="tags" row-key="id" stripe v-loading="tagsLoading">
            <el-table-column prop="id" label="ID" width="90" /><el-table-column prop="name" label="名称" min-width="160" /><el-table-column prop="sort" label="排序" width="100" /><el-table-column prop="version" label="版本" width="100" />
            <el-table-column label="适用场景" min-width="210"><template #default="{ row }"><el-tag v-for="mode in tagModes(row)" :key="mode" class="task-tag">{{ taskModeLabel(mode) }}</el-tag><el-tag v-if="!tagModes(row).length" type="warning">待分类</el-tag></template></el-table-column>
            <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="160"><template #default="{ row }"><el-button link type="primary" :disabled="tagBusy" @click="editTag(row)">编辑</el-button><el-button link :disabled="tagBusy" @click="toggleTag(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="detailOpen" title="任务详情" width="700px" @close="closeDetail">
      <div v-loading="detailLoading" class="detail-body">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false"><el-button link @click="openDetail(detailOrderId)">重试</el-button></el-alert>
        <el-descriptions v-else-if="detail" :column="1" border>
          <el-descriptions-item label="任务 / 订单 ID">{{ detail.id }} / {{ detail.orderId }}</el-descriptions-item><el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="发布用户 ID">{{ detail.userId }}</el-descriptions-item><el-descriptions-item label="标题">{{ detail.title }}</el-descriptions-item><el-descriptions-item :label="isTransportMode(detail.taskMode) ? '备注 / 服务说明' : '内容'"><div class="multiline">{{ detail.content }}</div></el-descriptions-item>
          <el-descriptions-item label="标签"><el-tag v-for="tag in detail.tags || []" :key="tag.id" class="task-tag">{{ tag.name }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.statusLabel || statusLabel(detail.status) }}</el-descriptions-item><el-descriptions-item label="场景">{{ taskModeLabel(detail.taskMode) }}</el-descriptions-item><el-descriptions-item label="总价">¥{{ formatFen(taskPriceFen(detail)) }}</el-descriptions-item>
          <el-descriptions-item label="骑手预估收入（服务端）">{{ formatFen(detail.riderShareFen) }} 元</el-descriptions-item><el-descriptions-item label="平台分成（服务端）">{{ formatFen(detail.platformShareFen) }} 元</el-descriptions-item>
          <template v-if="isLegacyTask(detail)">
            <el-descriptions-item label="取件地址">{{ detail.fromAddress || '—' }}</el-descriptions-item><el-descriptions-item label="送达地址">{{ detail.toAddress || '—' }}</el-descriptions-item>
          </template>
          <template v-else>
            <el-descriptions-item label="联系人">{{ detail.contact || '—' }}</el-descriptions-item><el-descriptions-item label="联系电话">{{ detail.phone || '—' }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.taskMode !== 'ONLINE'" :label="locationLabel(detail.taskMode)">{{ detail.locationAddress || '—' }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.taskMode !== 'ONLINE'" :label="detail.taskMode === 'PART_TIME' ? '工作时间' : '服务时间'">{{ detail.serviceTime || '—' }}</el-descriptions-item>
          </template>
          <el-descriptions-item label="发布来源 / 管理员">{{ detail.publishSource || '—' }} / {{ detail.createdBy ?? '—' }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ detail.createdTime }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </el-dialog>

    <el-dialog v-model="createOpen" title="代用户发布自定义任务" width="780px" :close-on-click-modal="false" :close-on-press-escape="!createBusy" :show-close="!createBusy" :before-close="beforeCloseCreate">
      <el-alert title="必须指定真实用户；用户有效性由后端校验。任务创建后待支付，请通知用户在小程序支付，不会自动支付。" type="info" show-icon :closable="false" class="notice" />
      <el-form label-position="top" :model="form" :disabled="createBusy">
        <el-form-item label="发布用户 ID（必填）" required><el-input v-model="form.userId" placeholder="输入用户 ID，后端将校验用户是否有效" /></el-form-item>
        <el-form-item v-if="canSearchUsers" label="搜索用户并选择（昵称 / 完整手机号）">
          <el-select v-model="form.userId" filterable remote :remote-method="searchUsers" :loading="usersLoading" placeholder="输入昵称或完整手机号" style="width:100%"><el-option v-for="user in customerOptions" :key="user.userId" :value="String(user.userId)" :label="`${user.nickname || '未设置昵称'} · ID ${user.userId} · ${user.mobile || '未绑定手机号'}`" /></el-select>
          <p v-if="usersError" class="field-hint">{{ usersError }}</p>
        </el-form-item>
        <p v-else class="field-hint">当前无用户管理权限，不查询用户列表；可手填用户 ID，由后端校验。</p>
        <el-form-item label="任务场景" required><el-select v-model="form.taskMode" style="width:100%"><el-option v-for="mode in taskModes" :key="mode.code" :value="mode.code" :label="mode.label" /></el-select></el-form-item>
        <el-form-item v-if="!isTransportMode(form.taskMode)" label="标题（1–64 字）" required><el-input v-model="form.title" maxlength="128" /><span class="field-hint">{{ Array.from(form.title).length }} / 64 字（按 Unicode 码点）</span></el-form-item>
        <p v-else class="field-hint">标题由后端按服务分类生成，无需填写标题或选择标签。</p>
        <el-form-item :label="isTransportMode(form.taskMode) ? '备注（可选，最多 1000 字）' : '任务内容（1–1000 字）'" :required="!isTransportMode(form.taskMode)"><el-input v-model="form.content" type="textarea" :rows="4" maxlength="2000" /><span class="field-hint">{{ Array.from(form.content).length }} / 1000 字（按 Unicode 码点）</span></el-form-item>
        <el-form-item v-if="!isTransportMode(form.taskMode)" label="场景标签（可选，最多 5 个）"><el-select v-model="form.tagIds" multiple :multiple-limit="5" style="width:100%" :loading="tagsLoading"><el-option v-for="tag in enabledTags" :key="tag.id" :label="tag.name" :value="tag.id" /></el-select><span class="field-hint">仅展示当前场景可用标签，切换场景会移除不适用的已选标签。</span><span v-if="tagsError" class="field-hint">标签加载失败，可不选标签或关闭弹窗后刷新。</span></el-form-item>
        <p class="field-hint">{{ scenarioHint(form.taskMode) }}</p>
        <el-form-item label="自定义总价（元，0.01–1000000.00）" required><el-input v-model="form.priceYuan" inputmode="decimal" placeholder="如 100.00，最多两位小数" /></el-form-item>
        <div class="form-grid"><el-form-item label="联系人" required><el-input v-model="form.contact" /></el-form-item><el-form-item label="联系电话" required><el-input v-model="form.phone" maxlength="11" /></el-form-item></div>
        <template v-if="form.taskMode !== 'ONLINE'">
          <el-form-item :label="`${locationLabel(form.taskMode)}（管理员手填，无需地址簿）`" required><el-input v-model="form.locationAddress" placeholder="填写一个详细地点" /></el-form-item>
          <el-form-item :label="`${form.taskMode === 'PART_TIME' ? '工作时间' : '服务时间'}（${requiresTime(form.taskMode) ? '必填' : '可选'}，最多 100 字）`" :required="requiresTime(form.taskMode)"><el-input v-model="form.serviceTime" maxlength="100" placeholder="如今天 18:30" /></el-form-item>
        </template>
      </el-form>
      <el-alert v-if="createError" :title="createError" type="error" :closable="false" class="notice" />
      <div v-if="quote" class="quote-box"><h3>服务端报价总价：¥{{ formatFen(quote.totalFen) }}</h3><ul><li v-for="(item, index) in quote.items" :key="index">{{ item.label }}：¥{{ formatFen(item.amountFen) }}</li></ul><p>骑手预估收入：¥{{ formatFen(quote.riderShareFen) }} · 平台分成：¥{{ formatFen(quote.platformShareFen) }}</p><p>分成由服务端返回，最终金额以服务端创建结果为准；修改发布信息后需重新报价。</p></div>
      <template #footer><el-button :disabled="createBusy" @click="createOpen = false">关闭</el-button><el-button :loading="quoting" :disabled="createBusy || createNeedsCheck" @click="getQuote">获取服务端报价</el-button><el-button type="primary" :loading="creating" :disabled="createBusy || !quote" @click="publish">确认报价并创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="tagOpen" :title="tagForm.id == null ? '新增标签' : '编辑标签'" width="480px" :close-on-click-modal="false" :show-close="!tagBusy" :close-on-press-escape="!tagBusy">
      <el-alert v-if="tagError" :title="tagError" type="error" :closable="false" class="notice"><el-button v-if="tagConflict" link :disabled="tagBusy" @click="reloadTagDraft">刷新此标签（丢弃草稿）</el-button></el-alert>
      <el-form label-position="top" :disabled="tagBusy || tagConflict"><el-form-item label="名称（1–32 字）" required><el-input v-model="tagForm.name" maxlength="64" /><span class="field-hint">{{ Array.from(tagForm.name).length }} / 32 字（按 Unicode 码点）</span></el-form-item><el-form-item label="排序（0–100000 整数）"><el-input-number v-model="tagForm.sort" :step="1" step-strictly :min="0" :max="100000" /></el-form-item><el-form-item label="适用场景（可多选）" required><el-checkbox-group v-model="tagForm.taskModes"><el-checkbox v-for="mode in taskModes" :key="mode.code" :value="mode.code" :label="mode.code">{{ mode.label }}</el-checkbox></el-checkbox-group></el-form-item><el-form-item label="启用"><el-switch v-model="tagForm.enabled" /></el-form-item><p v-if="tagForm.id != null">当前版本：{{ tagForm.version }}</p></el-form>
      <template #footer><el-button :disabled="tagBusy" @click="tagOpen = false">取消</el-button><el-button type="primary" :loading="tagBusy" :disabled="tagConflict" @click="saveTag">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
import { formatFen, isFen, yuanToFen } from '../utils/money'

const menuCodes = inject('adminMenuCodes', ref([]))
const canManage = computed(() => menuCodes.value.includes('tasks'))
const canSearchUsers = computed(() => menuCodes.value.includes('users'))
const tab = ref('tasks')
const statuses = [{ code: 'WAIT_PAY', label: '待支付' }, { code: 'WAIT_GRAB', label: '待接单' }, { code: 'ACCEPTED', label: '已接单' }, { code: 'PICKING', label: '取件中' }, { code: 'DELIVERING', label: '配送中' }, { code: 'DELIVERED', label: '已送达' }, { code: 'FINISHED', label: '已完成' }, { code: 'CANCELED', label: '已取消' }]
const statusLabel = status => statuses.find(s => s.code === status)?.label || status
const taskModes = [{ code: 'ONLINE', label: '线上任务' }, { code: 'ONSITE', label: '上门服务' }, { code: 'PART_TIME', label: '兼职劳务' }, { code: 'RIDE', label: '接送' }, { code: 'DRIVING', label: '代驾' }]
const isTransportMode = mode => mode === 'RIDE' || mode === 'DRIVING'
const requiresTime = mode => mode === 'PART_TIME' || isTransportMode(mode)
const locationLabel = mode => isTransportMode(mode) ? '起始地址' : mode === 'PART_TIME' ? '工作地点' : '服务地点'
const scenarioHint = mode => isTransportMode(mode) ? '只需一个起始地址，必须填写服务时间，备注可选；仅限分别获平台授权的骑手承接，不代表驾驶资质审核。' : mode === 'ONLINE' ? '线上完成，无需填写地址，由现有骑手承接。' : mode === 'ONSITE' ? '例如通马桶：只需一个服务地点，可选填写服务时间，由现有骑手承接。' : '例如搬水泥：必须填写工作地点及工作时间，由现有骑手承接。'
const taskModeLabel = mode => mode == null || mode === 'DELIVERY' ? '配送任务（历史）' : taskModes.find(item => item.code === mode)?.label || mode
const taskPriceFen = task => task.priceFen ?? task.amountFen
const isLegacyTask = task => task.taskMode == null || task.taskMode === 'DELIVERY'
const validId = id => /^[1-9]\d*$/.test(String(id ?? ''))
const query = reactive({ status: '', userId: '' })
const rows = ref([]), page = ref(1), total = ref(0), loading = ref(false), listError = ref('')
const tags = ref([]), tagsLoading = ref(false), tagsError = ref('')
const tagModes = tag => Array.isArray(tag.taskModes) && tag.taskModes.length > 0 && tag.taskModes.length <= taskModes.length && new Set(tag.taskModes).size === tag.taskModes.length && tag.taskModes.every(code => taskModes.some(mode => mode.code === code)) ? tag.taskModes : []
const enabledTags = computed(() => tags.value.filter(tag => tag.enabled === true && tagModes(tag).includes(form.taskMode)))
function pruneSelectedTags() {
  const valid = isTransportMode(form.taskMode) ? [] : form.tagIds.filter(id => enabledTags.value.some(tag => tag.id === id))
  if (valid.length !== form.tagIds.length) form.tagIds = valid
}
const detail = ref(null), detailOpen = ref(false), detailLoading = ref(false), detailError = ref(''), detailOrderId = ref('')
let disposed = false, listRequest = 0, tagsRequest = 0, detailRequest = 0, usersRequest = 0, createSession = 0

async function load(p = page.value) {
  if (!canManage.value || disposed) return
  const requestId = ++listRequest
  const reconcilingCreate = createNeedsCheck.value && !createOpen.value
  page.value = p; listError.value = ''
  const userId = query.userId.trim()
  if (userId && !validId(userId)) { rows.value = []; total.value = 0; loading.value = false; listError.value = '发布用户 ID 必须为正整数。'; return }
  loading.value = true
  try {
    const data = await adminApi.customTasks({ page: p - 1, status: query.status || undefined, userId: userId || undefined })
    if (requestId !== listRequest) return
    rows.value = data.content || []; total.value = data.totalElements || 0; page.value = (data.number ?? p - 1) + 1
    if (reconcilingCreate && !createOpen.value) createNeedsCheck.value = false
  } catch {
    if (requestId === listRequest) { rows.value = []; total.value = 0; listError.value = '任务列表加载失败，请重试。' }
  } finally { if (requestId === listRequest) loading.value = false }
}
function resetFilters() { Object.assign(query, { status: '', userId: '' }); return load(1) }
async function loadTags() {
  if (!canManage.value || disposed) return false
  const requestId = ++tagsRequest
  tagsLoading.value = true; tagsError.value = ''
  try {
    const data = await adminApi.taskTags()
    if (requestId !== tagsRequest) return false
    if (!Array.isArray(data)) throw new Error('Invalid tags')
    tags.value = data; pruneSelectedTags(); return true
  } catch {
    if (requestId === tagsRequest) { tags.value = []; tagsError.value = '标签加载失败，请刷新重试。' }
    return false
  } finally { if (requestId === tagsRequest) tagsLoading.value = false }
}
async function openDetail(orderId) {
  if (!canManage.value || disposed) return
  const requestId = ++detailRequest
  detailOrderId.value = orderId; detailOpen.value = true; detail.value = null; detailError.value = ''; detailLoading.value = false
  if (!validId(orderId)) { detailError.value = '订单 ID 无效。'; return }
  detailLoading.value = true
  try {
    const data = await adminApi.customTask(orderId)
    if (requestId === detailRequest) { if (!data?.orderId) throw new Error('Invalid task'); detail.value = data }
  } catch { if (requestId === detailRequest) detailError.value = '任务详情加载失败，请重试。' }
  finally { if (requestId === detailRequest) detailLoading.value = false }
}
function closeDetail() { ++detailRequest; detailOpen.value = false; detailLoading.value = false; detail.value = null }

const blankForm = () => ({ userId: '', title: '', content: '', tagIds: [], taskMode: 'ONLINE', priceYuan: '', contact: '', phone: '', locationAddress: '', serviceTime: '' })
const form = reactive(blankForm())
const createOpen = ref(false), createError = ref(''), quoting = ref(false), creating = ref(false), quote = ref(null), createNeedsCheck = ref(false)
const createBusy = computed(() => quoting.value || creating.value)
let quotedPayload = null, formRevision = 0
const customerOptions = ref([]), usersLoading = ref(false), usersError = ref('')
function openCreate() {
  if (!canManage.value || createBusy.value || createNeedsCheck.value || disposed) return
  ++createSession; Object.assign(form, blankForm()); quote.value = null; quotedPayload = null; createError.value = ''; customerOptions.value = []; usersError.value = ''; createOpen.value = true
}
function beforeCloseCreate(done) { if (!createBusy.value) done() }
async function searchUsers(keyword) {
  const requestId = ++usersRequest
  customerOptions.value = []; usersError.value = ''; usersLoading.value = false
  if (!canManage.value || !canSearchUsers.value || !keyword.trim() || disposed) return
  usersLoading.value = true
  try {
    const data = await adminApi.customerUsers({ page: 0, keyword: keyword.trim() })
    if (requestId === usersRequest && canSearchUsers.value) customerOptions.value = data.content || []
  } catch { if (requestId === usersRequest) usersError.value = '用户搜索失败，可手填 ID 后由服务端校验。' }
  finally { if (requestId === usersRequest) usersLoading.value = false }
}
function buildPayload() {
  const userId = String(form.userId).trim(), title = form.title.trim(), content = form.content.trim()
  if (!validId(userId)) throw new Error('请填写有效的发布用户 ID（正整数），用户有效性由后端校验。')
  if (!taskModes.some(mode => mode.code === form.taskMode)) throw new Error('请选择有效任务场景。')
  const transport = isTransportMode(form.taskMode)
  if (!transport && (!title || Array.from(title).length > 64)) throw new Error('标题需为 1–64 字。')
  if ((!transport && !content) || Array.from(content).length > 1000) throw new Error(transport ? '备注最多 1000 字。' : '任务内容需为 1–1000 字。')
  if (!transport && (!Array.isArray(form.tagIds) || form.tagIds.length > 5 || new Set(form.tagIds).size !== form.tagIds.length || form.tagIds.some(id => !enabledTags.value.some(tag => tag.id === id)))) throw new Error('最多选择 5 个适用当前场景的启用标签。')
  const priceFen = yuanToFen(form.priceYuan)
  if (!isFen(priceFen) || priceFen < 1 || priceFen > 100000000) throw new Error('总价需为 0.01–1000000.00 元，最多两位小数。')
  const contact = form.contact.trim(), phone = form.phone.trim()
  if (!contact) throw new Error('请填写联系人。')
  if (!/^1[3-9]\d{9}$/.test(phone)) throw new Error('请填写有效的联系人手机号。')
  const payload = { userId, ...(!transport ? { title } : {}), content, tagIds: transport ? [] : [...form.tagIds], taskMode: form.taskMode, priceFen, contact, phone }
  if (form.taskMode !== 'ONLINE') {
    const locationAddress = form.locationAddress.trim(), serviceTime = form.serviceTime.trim()
    if (!locationAddress) throw new Error(`请填写${locationLabel(form.taskMode)}。`)
    if (serviceTime.length > 100) throw new Error('服务或工作时间最多 100 字。')
    if (requiresTime(form.taskMode) && !serviceTime) throw new Error(form.taskMode === 'PART_TIME' ? '兼职劳务必须填写工作时间。' : '接送、代驾必须填写服务时间。')
    payload.locationAddress = locationAddress
    if (serviceTime) payload.serviceTime = serviceTime
  }
  return payload
}
async function getQuote() {
  if (!canManage.value || !createOpen.value || createBusy.value || createNeedsCheck.value || disposed) return
  createError.value = ''; quote.value = null; quotedPayload = null
  const session = createSession, revision = formRevision
  try {
    const payload = buildPayload()
    quoting.value = true
    const data = await adminApi.quoteCustomTask({ priceFen: payload.priceFen })
    if (disposed || session !== createSession || revision !== formRevision || !createOpen.value || !canManage.value || JSON.stringify(payload) !== JSON.stringify(buildPayload())) return
    if (!isFen(data?.totalFen) || data.totalFen !== payload.priceFen || !isFen(data.riderShareFen) || !isFen(data.platformShareFen) || data.riderShareFen + data.platformShareFen !== data.totalFen || !Array.isArray(data.items) || data.items.some(item => !item || typeof item.feeType !== 'string' || !item.feeType.trim() || typeof item.label !== 'string' || !item.label.trim() || !isFen(item.amountFen))) throw new Error('服务端报价或分成数据无效，请重新获取报价。')
    quote.value = data; quotedPayload = payload
  } catch (error) { if (!disposed && canManage.value && session === createSession && revision === formRevision) createError.value = error instanceof Error ? error.message : '报价失败，请重试。' }
  finally { quoting.value = false }
}
async function publish() {
  if (!canManage.value || !createOpen.value || createBusy.value || createNeedsCheck.value || !quote.value || !quotedPayload || disposed) return
  creating.value = true; createError.value = ''
  let sent = false
  try {
    const payload = buildPayload(), session = createSession
    if (JSON.stringify(payload) !== JSON.stringify(quotedPayload)) throw new Error('发布信息已变化，请重新报价。')
    await ElMessageBox.confirm(`为用户 ID ${payload.userId} 创建「${payload.title || taskModeLabel(payload.taskMode)}」（${taskModeLabel(payload.taskMode)}）？服务端报价总价 ¥${formatFen(quote.value.totalFen)}，骑手预估收入 ¥${formatFen(quote.value.riderShareFen)}，平台分成 ¥${formatFen(quote.value.platformShareFen)}。仅创建待支付任务，不代付；用户须在小程序支付。`, '确认报价并创建', { type: 'warning', confirmButtonText: '创建待支付任务', cancelButtonText: '取消' })
    if (disposed || !canManage.value || session !== createSession || !createOpen.value) return
    if (!quotedPayload || JSON.stringify(buildPayload()) !== JSON.stringify(payload)) throw new Error('发布信息已变化，请重新报价。')
    sent = true
    const task = await adminApi.createCustomTask(payload)
    if (disposed) return
    if (!validId(task?.id) || !validId(task?.orderId)) throw new Error('创建响应无效')
    createOpen.value = false; quote.value = null; quotedPayload = null
    ElMessage.success(`任务已创建${task?.orderNo ? `（${task.orderNo}）` : ''}，请通知用户在小程序支付；后台未代付。`)
    await load(1)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      if (sent) createNeedsCheck.value = true
      createError.value = sent ? '创建结果未确认，请先关闭弹窗核对任务列表，勿重复发布。' : error instanceof Error ? error.message : '创建未确认成功，请先核对任务列表，勿重复发布。'
      quote.value = null; quotedPayload = null
    }
  } finally { creating.value = false }
}
watch(form, () => { ++formRevision; quote.value = null; quotedPayload = null }, { flush: 'sync' })
watch(() => form.taskMode, mode => { pruneSelectedTags(); if (mode === 'ONLINE') { form.locationAddress = ''; form.serviceTime = '' } }, { flush: 'sync' })
watch(createOpen, open => { if (!open) { ++createSession; ++usersRequest; usersLoading.value = false; quote.value = null; quotedPayload = null } }, { flush: 'sync' })
watch(canSearchUsers, allowed => { if (!allowed) { ++usersRequest; customerOptions.value = []; usersLoading.value = false } })

const tagOpen = ref(false), tagBusy = ref(false), tagError = ref(''), tagConflict = ref(false)
const tagForm = reactive({ id: null, name: '', sort: 0, enabled: true, version: null, taskModes: [] })
function editTag(row = {}) {
  if (!canManage.value || tagBusy.value || disposed) return
  Object.assign(tagForm, { id: row.id ?? null, name: row.name || '', sort: row.sort ?? 0, enabled: row.enabled ?? true, version: row.version ?? null, taskModes: [...tagModes(row)] })
  tagError.value = ''; tagConflict.value = false; tagOpen.value = true
}
function tagPayload(draft) {
  const name = draft.name.trim()
  if (!name || Array.from(name).length > 32) throw new Error('标签名称需为 1–32 字。')
  if (!Number.isInteger(draft.sort) || draft.sort < 0 || draft.sort > 100000 || typeof draft.enabled !== 'boolean') throw new Error('标签排序必须为 0–100000 的整数，启用状态必须为布尔值。')
  if (draft.id != null && (!Number.isSafeInteger(draft.version) || draft.version < 0)) throw new Error('版本无效，请刷新此标签。')
  const modes = tagModes(draft)
  if (!modes.length) throw new Error('请选择 1–5 个适用场景；待分类标签请先编辑分类。')
  return { name, enabled: draft.enabled, sort: draft.sort, taskModes: [...modes], ...(draft.id == null ? {} : { version: draft.version }) }
}
async function persistTag(draft, toggling = false) {
  if (!canManage.value || tagBusy.value || disposed || (tagOpen.value && tagConflict.value)) return
  tagBusy.value = true; tagError.value = ''; tagsError.value = ''
  try {
    const payload = tagPayload(draft)
    if (toggling) await ElMessageBox.confirm(`确认${payload.enabled ? '启用' : '停用'}标签「${payload.name}」？停用不会删除已引用标签。`, '标签状态', { type: 'warning', cancelButtonText: '取消' })
    if (disposed || !canManage.value) return
    if (draft.id == null) await adminApi.createTaskTag(payload)
    else await adminApi.updateTaskTag(draft.id, payload)
    if (disposed) return
    tagOpen.value = false; ElMessage.success('标签已保存'); await loadTags()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    const conflict = Number(error?.code) === 409 || error?.response?.status === 409
    const message = conflict ? '版本冲突（409）：标签已被其他管理员修改，列表已刷新，请刷新此标签后重新编辑。' : error instanceof Error ? error.message : '标签保存失败，请刷新确认后重试。'
    if (!toggling) { tagError.value = message; tagConflict.value = conflict }
    else tagsError.value = message
    if (conflict) { await loadTags(); if (toggling) tagsError.value = message }
  } finally { tagBusy.value = false }
}
const saveTag = () => persistTag({ ...tagForm })
const toggleTag = row => persistTag({ ...row, enabled: !row.enabled }, true)
async function reloadTagDraft() {
  if (tagBusy.value || !await loadTags()) return
  const current = tags.value.find(tag => tag.id === tagForm.id)
  if (current) editTag(current)
  else tagError.value = '标签已不存在，请关闭后刷新列表。'
}
watch(canManage, allowed => {
  if (allowed) { load(1); loadTags() }
  else { ++listRequest; ++tagsRequest; closeDetail(); createOpen.value = false; tagOpen.value = false; rows.value = []; tags.value = []; loading.value = false; tagsLoading.value = false }
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; ++listRequest; ++tagsRequest; ++detailRequest; ++usersRequest; ++createSession })
</script>

<style scoped>
.notice, .field-hint { margin-bottom: 18px; }
.field-hint, .quote-box p, .panel-heading p { color: var(--ink-secondary); font-size: 12px; line-height: 1.7; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.task-tag { margin: 3px 5px 3px 0; }
.detail-body { min-height: 150px; }
.multiline { white-space: pre-wrap; overflow-wrap: anywhere; }
.quote-box { padding: 18px; border: 1px solid var(--line); border-radius: 12px; }
.quote-box ul { padding-left: 20px; margin: 12px 0; line-height: 1.8; }
@media(max-width:600px) { .form-grid { grid-template-columns: 1fr; gap: 0; } }
</style>
