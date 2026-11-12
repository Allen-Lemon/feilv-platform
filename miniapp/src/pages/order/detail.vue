<template>
  <view>
    <view class="card">
      <view class="status-bar">
        <text class="big">{{ order.statusLabel }}</text>
        <text class="no">{{ order.orderNo }}</text>
      </view>
      <view class="line"><text>取件</text><text>{{ order.fromAddress }}</text></view>
      <view class="line"><text>送达</text><text>{{ order.toAddress }}</text></view>
      <view class="line"><text>物品</text><text>{{ order.goodsDesc || '—' }}</text></view>
      <view class="line"><text>费用</text><text class="fee">￥{{ (order.realFeeFen / 100).toFixed(2) }}</text></view>
      <view v-if="tracks.length" class="line">
        <text>骑手轨迹</text>
        <text class="track">{{ tracks.length }}个轨迹点（生产用地图展示，刷新≤5秒）</text>
      </view>
    </view>

    <view class="card" v-if="order.statusCode === 'DELIVERED'">
      <button class="btn-primary" @click="confirm">确认收货</button>
      <view class="hint">15分钟未确认将默认完成并触发分账</view>
    </view>

    <view class="card" v-if="order.statusCode === 'FINISHED' && !reviewed">
      <view class="sec-title">评价骑手</view>
      <view class="stars">
        <text v-for="s in 5" :key="s" class="star" :class="{ on: s <= stars }" @click="stars = s">★</text>
      </view>
      <textarea v-model="content" placeholder="说说这次服务（选填）" class="textarea" />
      <button class="btn-primary" style="margin-top: 20rpx" @click="doReview">提交评价</button>
    </view>

    <view class="card" v-if="['DELIVERED','FINISHED'].includes(order.statusCode)">
      <view class="sec-title">售后与投诉</view>
      <picker :range="complaintTypes" @change="complaintType = complaintTypes[$event.detail.value].code"><view class="picker">{{ complaintTypeLabel }}</view></picker>
      <textarea v-model="complaintDescription" class="textarea" placeholder="请描述问题（丢件、损坏、超时等）" />
      <button class="btn-primary" @click="submitComplaint">提交投诉</button>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import api from '../../api/index'

const order = ref({})
const tracks = ref([])
const stars = ref(5)
const content = ref('')
const reviewed = ref(false)
const complaintTypes = [{ code: 'LOST', label: '丢件' }, { code: 'DAMAGE', label: '损坏' }, { code: 'OVERTIME', label: '超时' }, { code: 'ATTITUDE', label: '服务态度' }, { code: 'OTHER', label: '其他' }]
const complaintType = ref('OTHER')
const complaintDescription = ref('')
const complaintTypeLabel = ref('其他')
let orderId = 0

const load = async () => {
  if (!orderId) return
  order.value = await api.orderDetail(orderId)
  tracks.value = await api.orderTrack(orderId)
}

onLoad((q) => { orderId = Number(q.id) })
onShow(() => load())

const confirm = async () => {
  await api.confirmOrder(orderId)
  uni.showToast({ title: '已确认收货，欢迎评价', icon: 'success' })
  load()
}

const doReview = async () => {
  await api.review({ orderId, stars: stars.value, content: content.value })
  reviewed.value = true
  uni.showToast({ title: '感谢您的评价（已同步骑手信用分）', icon: 'none' })
}
const submitComplaint = async () => {
  if (!complaintDescription.value) return uni.showToast({ title: '请填写问题描述', icon: 'none' })
  await api.complaint({ orderId, type: complaintType.value, description: complaintDescription.value })
  complaintDescription.value = ''
  uni.showToast({ title: '投诉已提交，客服将跟进', icon: 'success' })
}
</script>

<style>
.status-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20rpx; }
.big { font-size: 40rpx; font-weight: 700; color: #ff7d00; }
.no { font-size: 22rpx; color: #909399; }
.line { display: flex; justify-content: space-between; gap: 24rpx; font-size: 26rpx; padding: 10rpx 0; color: #606266; }
.line text:first-child { color: #909399; flex-shrink: 0; }
.fee { font-weight: 700; color: #303133; }
.track { font-size: 24rpx; }
.hint { font-size: 22rpx; color: #c0c4cc; text-align: center; margin-top: 14rpx; }
.sec-title { font-weight: 600; margin-bottom: 16rpx; }
.stars { font-size: 60rpx; color: #e5e5e5; }
.star { margin-right: 10rpx; }
.star.on { color: #ffb400; }
.textarea { background: #f5f6f8; padding: 20rpx; border-radius: 12rpx; width: 100%; height: 110rpx; font-size: 26rpx; }
.picker { background:#f5f6f8; padding:18rpx; margin-bottom:14rpx; color:#606266; }
</style>
