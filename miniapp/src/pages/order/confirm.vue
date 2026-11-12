<template>
  <view>
    <view class="card">
      <view class="sec-title">订单确认</view>
      <view class="line"><text>业务类型</text><text>{{ typeLabel }}</text></view>
      <view class="line"><text>取件</text><text>{{ draft.fromAddress }}</text></view>
      <view class="line"><text>送达</text><text>{{ draft.toAddress }}</text></view>
      <view class="line"><text>物品</text><text>{{ draft.goodsDesc }}</text></view>
      <view class="line"><text>备注</text><text>{{ draft.remark || '无' }}</text></view>
    </view>
    <view class="card">
      <view class="fee" v-for="it in quote.items" :key="it.feeType">
        <text>{{ it.label }}</text><text>￥{{ (it.amountFen / 100).toFixed(2) }}</text>
      </view>
      <view class="line total"><text>应付</text><text class="price">￥{{ (quote.totalFen / 100).toFixed(2) }}</text></view>
      <button class="btn-primary" style="margin-top: 24rpx" :disabled="paying" @click="submitAndPay">
        {{ paying ? '支付中…' : '模拟支付并下单' }}
      </button>
      <view class="hint">生产环境拉起微信支付（经持牌机构收单，回调≤5秒同步状态）</view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import api from '../../api/index'

const draft = JSON.parse(uni.getStorageSync('feilv_order_draft') || '{}')
const quote = JSON.parse(uni.getStorageSync('feilv_quote') || '{}')
const typeLabel = ({ SEND: '跑腿送', BUY: '跑腿买', ERRAND: '跑腿办' })[draft.orderType] || draft.orderType
const paying = ref(false)

const submitAndPay = async () => {
  paying.value = true
  try {
    const order = await api.createOrder(draft)   // 创建（含禁发品类拦截）
    await api.payOrder(order.id)                  // 模拟支付 → 待接单
    uni.removeStorageSync('feilv_order_draft')
    uni.showToast({ title: '下单成功，等待骑手接单', icon: 'success' })
    setTimeout(() => uni.redirectTo({ url: '/pages/order/detail?id=' + order.id }), 800)
  } finally {
    paying.value = false
  }
}
</script>

<style>
.sec-title { font-weight: 700; font-size: 32rpx; margin-bottom: 20rpx; }
.line { display: flex; justify-content: space-between; gap: 24rpx; font-size: 26rpx; padding: 10rpx 0; color: #606266; }
.line text:first-child { color: #909399; flex-shrink: 0; }
.fee { display: flex; justify-content: space-between; color: #606266; font-size: 26rpx; padding: 8rpx 0; }
.total { border-top: 1rpx solid #eee; margin-top: 10rpx; padding-top: 16rpx; font-weight: 700; color: #303133; }
.price { color: #ff7d00; font-size: 36rpx; }
.hint { font-size: 22rpx; color: #c0c4cc; margin-top: 18rpx; text-align: center; }
</style>
