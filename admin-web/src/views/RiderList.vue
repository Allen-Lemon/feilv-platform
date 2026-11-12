<template>
  <div>
    <el-alert type="info" :closable="false" style="margin-bottom: 12px"
              title="骑手在线状态：0 下线 / 1 抢单 / 2 听单；信用分初始100，等级联动：≥110优先派单、＜80降权、＜60限单复训、＜40停单整改" />
    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="riderId" label="骑手ID" width="90" />
      <el-table-column prop="name" label="姓名" width="120" />
      <el-table-column label="信用分" width="120">
        <template #default="{ row }">
          <el-tag :type="row.creditScore >= 110 ? 'success' : row.creditScore < 60 ? 'danger' : 'warning'">
            {{ row.creditScore }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="接单模式" width="120">
        <template #default="{ row }">{{ ['下线', '抢单', '听单'][row.onlineStatus] }}</template>
      </el-table-column>
      <el-table-column label="账户状态">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'

const rows = ref([])
const loading = ref(false)
onMounted(async () => {
  loading.value = true
  try { rows.value = await adminApi.riders() } finally { loading.value = false }
})
</script>
