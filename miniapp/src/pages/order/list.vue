<template>
  <view>
    <view class="card order" v-for="o in list" :key="o.id" @click="goDetail(o.id)">
      <view class="head">
        <text class="no">{{ o.orderNo }}</text>
        <text class="status">{{ o.statusLabel }}</text>
      </view>
      <view class="addr">
        <text>取：{{ o.fromAddress }}</text>
        <text>送：{{ o.toAddress }}</text>
      </view>
      <view class="foot">
        <text>{{ o.createdTime?.replace('T', ' ') }}</text>
        <text class="fee">￥{{ (o.realFeeFen / 100).toFixed(2) }}</text>
      </view>
    </view>
    <view v-if="!list.length" class="empty">暂无订单，快去下一单吧</view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onPullDownRefresh } from '@dcloudio/uni-app'
import api from '../../api/index'

const list = ref([])
const page = ref(0)
const hasMore = ref(true)

const load = async (reset = false) => {
  if (!uni.getStorageSync('feilv_token')) return
  if (reset) { page.value = 0; hasMore.value = true }
  if (!hasMore.value) return
  const data = await api.myOrders(page.value)
  const rows = data.content || []
  list.value = reset ? rows : [...list.value, ...rows]
  hasMore.value = !data.last
  page.value++
}

const goDetail = (id) => uni.navigateTo({ url: '/pages/order/detail?id=' + id })

onPullDownRefresh(async () => {
  await load(true)
  uni.stopPullDownRefresh()
})

// tabBar 页用 onShow 刷新
import { onShow } from '@dcloudio/uni-app'
onShow(() => load(true))
</script>

<style>
.order { margin-bottom: 20rpx; }
.head { display: flex; justify-content: space-between; margin-bottom: 12rpx; }
.no { font-size: 24rpx; color: #909399; }
.status { color: #ff7d00; font-size: 26rpx; font-weight: 600; }
.addr { font-size: 26rpx; color: #606266; line-height: 1.8; }
.foot { display: flex; justify-content: space-between; margin-top: 14rpx; font-size: 24rpx; color: #909399; }
.fee { color: #303133; font-weight: 700; }
.empty { text-align: center; color: #c0c4cc; padding: 120rpx 0; font-size: 26rpx; }
</style>
