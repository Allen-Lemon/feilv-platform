<template>
  <div>
    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane label="骑手分账汇总" name="settle">
        <el-table :data="settlements" border stripe v-loading="loading">
          <el-table-column prop="riderId" label="骑手ID" width="100" />
          <el-table-column prop="name" label="姓名" width="140" />
          <el-table-column prop="totalShareFen" label="累计分账(分)" width="140" />
          <el-table-column prop="totalShareYuan" label="累计分账(元)" />
        </el-table>
        <el-alert type="warning" :closable="false" style="margin-top: 12px"
                  title="生产流程：日切生成分账结果 → 财务审核 → 持牌渠道批量打款（T+1）→ 对账归档；平台不经手资金沉淀" />
      </el-tab-pane>
      <el-tab-pane label="投诉工单（待处理）" name="complaint">
        <el-table :data="complaints" border stripe>
          <el-table-column prop="id" label="工单号" width="90" />
          <el-table-column prop="orderId" label="订单ID" width="100" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              {{ { LOST: '丢件', DAMAGE: '损坏', OVERTIME: '超时', ATTITUDE: '态度', OTHER: '其他' }[row.type] || row.type }}
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" show-overflow-tooltip />
          <el-table-column prop="createdTime" label="创建时间" width="180" />
          <el-table-column label="处理" width="180">
            <template #default="{ row }">
              <el-button v-if="row.status === 'OPEN'" size="small" type="primary" @click="handleComplaint(row)">受理</el-button>
              <el-button v-if="row.status === 'PROCESSING'" size="small" type="success" @click="closeComplaint(row)">办结</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'

const tab = ref('settle')
const settlements = ref([])
const complaints = ref([])
const loading = ref(false)

const handleComplaint = async (row) => {
  await adminApi.handleComplaint(row.id, { status: 'PROCESSING', result: '客服已受理' })
  ElMessage.success('工单已受理')
  load()
}
const closeComplaint = (row) => {
  ElMessageBox.prompt('请输入处理结果', '投诉办结', {
    confirmButtonText: '办结', cancelButtonText: '取消', inputValidator: (v) => !!v || '处理结果必填'
  }).then(async ({ value }) => {
    await adminApi.handleComplaint(row.id, { status: 'DONE', result: value, compensationFen: 0 })
    ElMessage.success('工单已办结')
    load()
  }).catch(() => {})
}

const load = async () => {
  loading.value = true
  try {
    if (tab.value === 'settle') settlements.value = await adminApi.settlements()
    else complaints.value = await adminApi.complaints('OPEN')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
