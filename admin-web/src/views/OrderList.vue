<template>
  <div>
    <el-form inline>
      <el-form-item label="订单状态">
        <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 160px"
                   @change="load(0)">
          <el-option v-for="s in statuses" :key="s.code" :label="s.label" :value="s.code" />
        </el-select>
      </el-form-item>
      <el-button type="primary" @click="load(0)">查询</el-button>
    </el-form>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="orderNo" label="订单号" width="210" />
      <el-table-column prop="orderType" label="类型" width="90">
        <template #default="{ row }">{{ typeLabel(row.orderType) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="tagType(row.statusCode)">{{ row.statusLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="fromAddress" label="取件地" show-overflow-tooltip />
      <el-table-column prop="toAddress" label="送达地" show-overflow-tooltip />
      <el-table-column label="实收(元)" width="100">
        <template #default="{ row }">{{ (row.realFeeFen / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="createdTime" label="下单时间" width="170" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="danger" plain
                    :disabled="row.statusCode === 'CANCELED' || row.statusCode === 'FINISHED'"
                    @click="cancel(row)">干预取消</el-button>
          <el-button v-if="row.statusCode === 'DELIVERED'" size="small" type="success" plain
                    @click="complete(row)">强制完成</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top: 12px" layout="prev, pager, next, total"
                   :total="total" :page-size="15" v-model:current-page="page" @current-change="load" />
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'

const statuses = [
  { code: 'WAIT_PAY', label: '待支付' }, { code: 'WAIT_GRAB', label: '待接单' },
  { code: 'ACCEPTED', label: '已接单' }, { code: 'PICKING', label: '取件中' },
  { code: 'DELIVERING', label: '配送中' }, { code: 'DELIVERED', label: '已送达' },
  { code: 'FINISHED', label: '已完成' }, { code: 'CANCELED', label: '已取消' }
]
const query = reactive({ status: '' })
const rows = ref([])
const page = ref(0)
const total = ref(0)
const loading = ref(false)

const typeLabel = (t) => ({ SEND: '跑腿送', BUY: '跑腿买', ERRAND: '跑腿办' }[t] || t)
const tagType = (code) => ({
  FINISHED: 'success', CANCELED: 'info', DELIVERING: 'primary', WAIT_GRAB: 'warning'
}[code] || '')

const load = async (p = page.value) => {
  loading.value = true
  try {
    const data = await adminApi.orders({ page: p, status: query.status || undefined })
    rows.value = data.content || []
    total.value = data.totalElements || 0
    page.value = data.number ?? p
  } finally {
    loading.value = false
  }
}

const complete = (row) => {
  ElMessageBox.prompt('请输入强制完成原因', '订单干预', {
    confirmButtonText: '确认完成', cancelButtonText: '关闭', inputValidator: (v) => !!v || '原因必填'
  }).then(async ({ value }) => {
    await adminApi.completeOrder(row.id, value)
    ElMessage.success('订单已完成')
    load()
  }).catch(() => {})
}

const cancel = (row) => {
  ElMessageBox.prompt('请输入干预取消原因（生产环境必填并落审计日志）', '订单干预', {
    confirmButtonText: '确认取消', cancelButtonText: '关闭', inputValidator: (v) => !!v || '原因必填'
  }).then(async ({ value }) => {
    await adminApi.cancelOrder(row.id, value)
    ElMessage.success('订单已取消')
    load()
  }).catch(() => {})
}

onMounted(() => load(0))
</script>
