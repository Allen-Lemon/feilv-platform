<template>
  <div>
    <el-alert v-if="!canManage" type="warning" :closable="false" title="正在确认权限，或当前账户未获骑手管理权限。" />
    <template v-else>
      <el-alert type="info" :closable="false" class="notice"
                title="骑手在线状态：0 下线 / 1 抢单 / 2 听单；信用分初始100，等级联动：≥110优先派单、＜80降权、＜60限单复训、＜40停单整改" />
      <el-alert type="warning" :closable="false" class="notice" title="接送、代驾须分别授权，默认不开通。这是平台接单权限，不代表驾驶资质审核；撤销后禁止新增接单和开始服务，已开始服务仍可完成。" />
      <el-button :loading="loading" class="notice" @click="load">刷新骑手列表</el-button>
      <el-alert v-if="listError" type="error" :closable="false" :title="listError" class="notice"><el-button link @click="load">重试</el-button></el-alert>
      <el-table :data="rows" row-key="riderId" border stripe v-loading="loading">
        <el-table-column prop="riderId" label="骑手ID" width="90" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column label="信用分" width="120">
          <template #default="{ row }">
            <el-tag :type="row.creditScore >= 110 ? 'success' : row.creditScore < 60 ? 'danger' : 'warning'">{{ row.creditScore }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="接单模式" width="120">
          <template #default="{ row }">{{ ['下线', '抢单', '听单'][row.onlineStatus] }}</template>
        </el-table-column>
        <el-table-column label="账户状态" width="120">
          <template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column v-for="mode in capabilityModes" :key="mode.code" :label="`${mode.label}授权`" min-width="155">
          <template #default="{ row }">
            <el-switch :model-value="row[mode.field] === true" :active-text="'开通'" :inactive-text="'关闭'"
                       :aria-label="`骑手 ${row.riderId} ${mode.label}授权`" :loading="pendingIds.has(row.riderId)"
                       :disabled="!canManage || loading || pendingIds.has(row.riderId) || needsRefresh.has(row.riderId) || typeof row[mode.field] !== 'boolean'"
                       @change="enabled => changeCapability(row, mode.code, enabled)" />
            <small v-if="typeof row[mode.field] !== 'boolean'">服务端未提供授权状态，请刷新确认</small>
          </template>
        </el-table-column>
        <el-table-column label="操作结果" min-width="240"><template #default="{ row }"><span v-if="rowErrors[row.riderId] || needsRefresh.has(row.riderId)" role="alert">{{ rowErrors[row.riderId] || '授权结果待核对，请刷新骑手列表。' }}</span></template></el-table-column>
      </el-table>
    </template>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'

const menuCodes = inject('adminMenuCodes', ref([]))
const canManage = computed(() => Array.isArray(menuCodes.value) && menuCodes.value.includes('riders'))
const capabilityModes = [{ code: 'RIDE', field: 'rideEnabled', label: '接送' }, { code: 'DRIVING', field: 'drivingEnabled', label: '代驾' }]
const rows = ref([]), loading = ref(false), listError = ref('')
const pendingIds = reactive(new Set()), needsRefresh = reactive(new Set()), rowErrors = reactive({})
let disposed = false, listRequest = 0, permissionSession = 0
const isCurrent = session => !disposed && canManage.value && session === permissionSession
const validId = id => /^[1-9]\d*$/.test(String(id ?? ''))

async function load() {
  if (disposed || !canManage.value) return false
  const request = ++listRequest, session = permissionSession
  const reconciling = [...needsRefresh].filter(id => !pendingIds.has(id))
  loading.value = true; listError.value = ''
  try {
    const data = await adminApi.riders()
    if (!isCurrent(session) || request !== listRequest) return false
    if (!Array.isArray(data) || data.some(row => !row || !validId(row.riderId))) throw new Error('Invalid rider list')
    rows.value = data
    for (const id of reconciling) { needsRefresh.delete(id); delete rowErrors[id] }
    return true
  } catch {
    if (isCurrent(session) && request === listRequest) { rows.value = []; listError.value = '骑手列表加载失败，请刷新重试。' }
    return false
  } finally { if (request === listRequest) loading.value = false }
}

async function changeCapability(row, code, enabled) {
  const mode = capabilityModes.find(item => item.code === code)
  if (disposed || !canManage.value || loading.value || !mode || !validId(row?.riderId) || typeof enabled !== 'boolean') return
  const id = row.riderId, current = rows.value.find(item => item.riderId === id)
  if (current !== row || pendingIds.has(id) || needsRefresh.has(id) || typeof current[mode.field] !== 'boolean' || current[mode.field] === enabled) return
  const session = permissionSession, expectedEnabled = current[mode.field]
  pendingIds.add(id); delete rowErrors[id]
  let sent = false
  try {
    const { value } = await ElMessageBox.prompt(`确认为骑手 ${current.name || ''}（ID ${id}）${enabled ? '开通' : '撤销'}${mode.label}接单权限？请填写操作原因（1–160 字，记录审计）。这是平台授权，不代表驾驶资质审核。`, '确认骑手授权变更', {
      type: 'warning', confirmButtonText: enabled ? '确认开通' : '确认撤销', cancelButtonText: '取消',
      inputValidator: value => typeof value === 'string' && !!value.trim() && value.trim().length <= 160 || '原因需为 1–160 字'
    })
    if (!isCurrent(session)) return
    const reason = typeof value === 'string' ? value.trim() : ''
    if (!reason || reason.length > 160) { rowErrors[id] = '原因需为 1–160 字，未保存授权。'; return }
    const latest = rows.value.find(item => item.riderId === id)
    if (!latest || latest[mode.field] !== expectedEnabled || needsRefresh.has(id)) { rowErrors[id] = '授权状态已变化，请刷新后重新确认。'; return }
    sent = true; needsRefresh.add(id)
    const data = await adminApi.updateRiderCapability(id, code, { enabled, expectedEnabled, reason })
    if (!isCurrent(session)) return
    if (!data || String(data.riderId) !== String(id) || typeof data.rideEnabled !== 'boolean' || typeof data.drivingEnabled !== 'boolean' || data[mode.field] !== enabled) throw new Error('Invalid capability response')
    // 使用完整安全行响应替换，不乐观切换，也不把旧列表响应覆盖回授权结果。
    ++listRequest; loading.value = false
    rows.value = rows.value.map(item => item.riderId === id ? data : item)
    needsRefresh.delete(id)
    ElMessage.success(`${mode.label}授权已${enabled ? '开通' : '撤销'}`)
  } catch (error) {
    if (!isCurrent(session) || error === 'cancel' || error === 'close') return
    const conflict = Number(error?.code) === 409 || Number(error?.response?.status) === 409
    if (sent) needsRefresh.add(id)
    const message = conflict ? '授权冲突（409）：状态已被其他管理员修改，已刷新列表，请核对后重新操作。' : '授权未确认保存成功，请刷新列表核对，勿重复操作。'
    rowErrors[id] = message
    if (conflict) {
      const refreshed = await load()
      if (isCurrent(session)) {
        if (refreshed) needsRefresh.delete(id)
        rowErrors[id] = refreshed ? message : '授权冲突（409），列表刷新失败，请刷新成功后再操作。'
      }
    }
  } finally { pendingIds.delete(id) }
}

watch(canManage, allowed => {
  ++permissionSession; ++listRequest
  rows.value = []; loading.value = false; listError.value = ''
  for (const id of Object.keys(rowErrors)) delete rowErrors[id]
  if (allowed) load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; ++permissionSession; ++listRequest })
</script>

<style scoped>
.notice { margin-bottom: 12px; }
small { display: block; color: var(--ink-secondary); }
</style>
