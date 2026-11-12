<template>
  <view>
    <view class="banner">
      <view class="title">飞毛腿</view>
      <view class="sub">德江同城跑腿 · 送得快、办得好</view>
    </view>
    <view class="grid">
      <view class="entry" v-for="t in types" :key="t.code" @click="go(t.code)">
        <view class="icon">{{ t.icon }}</view>
        <view class="name">{{ t.label }}</view>
        <view class="desc">{{ t.desc }}</view>
      </view>
    </view>
    <view class="card">
      <view class="tip-title">服务保障</view>
      <view class="tip">· 骑手实名认证 + 全程轨迹可视</view>
      <view class="tip">· 丢损按平台规则先行赔付</view>
      <view class="tip">· 禁发品类（处方药、烟草等）依法拦截</view>
    </view>
  </view>
</template>

<script setup>
const types = [
  { code: 'SEND', icon: '📦', label: '跑腿送', desc: '送文件、送快递、送蛋糕鲜花' },
  { code: 'BUY', icon: '🛒', label: '跑腿买', desc: '代买商超药品、排队代办' },
  { code: 'ERRAND', icon: '📋', label: '跑腿办', desc: '代排队、代取件、办事代跑' }
]

const go = (type) => {
  if (!uni.getStorageSync('feilv_token')) {
    uni.navigateTo({ url: '/pages/login/login' })
    return
  }
  uni.setStorageSync('feilv_order_type', type)
  uni.navigateTo({ url: '/pages/order/create' })
}
</script>

<style>
.banner { background: linear-gradient(135deg, #ff9d4d, #ff7d00); color: #fff; padding: 60rpx 40rpx 40rpx; }
.title { font-size: 52rpx; font-weight: 700; }
.sub { font-size: 26rpx; margin-top: 8rpx; opacity: .9; }
.grid { display: flex; flex-wrap: wrap; padding: 30rpx 20rpx 0; }
.entry { width: 46%; margin: 2%; background: #fff; border-radius: 20rpx; padding: 34rpx; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.05); }
.icon { font-size: 56rpx; }
.name { font-size: 34rpx; font-weight: 600; margin-top: 10rpx; }
.desc { font-size: 24rpx; color: #909399; margin-top: 6rpx; }
.card { margin-top: 24rpx; }
.tip-title { font-weight: 600; margin-bottom: 12rpx; }
.tip { font-size: 24rpx; color: #909399; line-height: 1.9; }
</style>
