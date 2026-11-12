<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="5" v-for="card in cards" :key="card.label">
        <el-card>
          <div class="num">{{ card.value }}</div>
          <div class="label">{{ card.label }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-card style="margin-top: 16px">
      <template #header>运营提示</template>
      <p>· 看板指标对齐功能设计文档 7.6 经营看板（用户/订单/骑手/收入核心指标）。</p>
      <p>· 异常波动（环比超阈值）自动预警在生产版接入消息中心告警。</p>
      <p>· 今日平台收入为分账后平台留存（30%），骑手分账明细见"结算中心"。</p>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { adminApi } from '../api'

const stats = ref({})
const cards = computed(() => [
  { label: '注册用户', value: stats.value.userCount ?? '-' },
  { label: '注册骑手', value: stats.value.riderCount ?? '-' },
  { label: '今日订单', value: stats.value.orderCountToday ?? '-' },
  { label: '累计订单', value: stats.value.orderCountTotal ?? '-' },
  { label: '今日平台收入(元)', value: ((stats.value.incomeTodayFen ?? 0) / 100).toFixed(2) }
])

onMounted(async () => { stats.value = await adminApi.stats() })
</script>

<style scoped>
.num { font-size: 26px; font-weight: 700; color: #409EFF; }
.label { color: #909399; font-size: 13px; margin-top: 6px; }
p { color: #606266; font-size: 13px; line-height: 1.8; }
</style>
