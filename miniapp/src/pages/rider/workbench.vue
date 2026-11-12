<template>
  <view>
    <view class="card">
      <view class="rider-head">
        <view>
          <view class="name">{{ riderInfo.name || '骑手' }}</view>
          <view class="meta">信用分 {{ riderInfo.creditScore ?? '-' }} ｜ {{ riderInfo.stars || '暂无评价' }}</view>
        </view>
        <button class="mode" :class="{ on: online }" @click="toggleOnline">
          {{ online ? '抢单中' : '已下线' }}
        </button>
      </view>
    </view>

    <!-- 抢单大厅（对应详细设计5.2.1） -->
    <view class="card">
      <view class="sec-title">抢单大厅（酬劳=配送费×70%）</view>
      <view class="grab" v-for="g in grabList" :key="g.orderId">
        <view class="grab-info">
          <text class="type">{{ typeLabel(g.orderType) }}</text>
          <text class="addr">{{ g.fromAddress }} → {{ g.toAddress }}</text>
          <text class="meta">{{ (g.distanceM / 1000).toFixed(1) }}公里 · 酬劳￥{{ (g.rewardFen / 100).toFixed(2) }}</text>
        </view>
        <button class="grab-btn" size="mini" @click="doGrab(g.orderId)">抢单</button>
      </view>
      <view v-if="!grabList.length" class="empty">大厅暂无可抢订单</view>
    </view>

    <!-- 我的承运（对应详细设计5.3 履约） -->
    <view class="card">
      <view class="sec-title">我的订单</view>
      <view v-for="o in myOrders" :key="o.id" class="mine">
        <view class="grab-info">
          <text class="addr">{{ o.fromAddress }} → {{ o.toAddress }}</text>
          <text class="meta">{{ o.status }} · 取件码请见抢单成功提示</text>
        </view>
        <view class="acts">
          <button v-if="o.statusCode === 'ACCEPTED'" class="act" size="mini" @click="doPickup(o.id)">取件核销</button>
          <button v-if="o.statusCode === 'PICKING'" class="act" size="mini" @click="doDepart(o.id)">出发配送</button>
          <button v-if="o.statusCode === 'DELIVERING'" class="act" size="mini" @click="reportTrack(o.id)">轨迹上报</button>
          <button v-if="o.statusCode === 'DELIVERING'" class="act main" size="mini" @click="doDeliver(o.id)">确认送达</button>
        </view>
      </view>
      <view v-if="!myOrders.length" class="empty">暂无承运订单</view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import api from '../../api/index'

const riderInfo = ref({})
const grabList = ref([])
const myOrders = ref([])
const online = ref(false)

const typeLabel = (t) => ({ SEND: '送', BUY: '买', ERRAND: '办' })[t] || t

const load = async () => {
  if (uni.getStorageSync('feilv_role') !== 'RIDER') {
    uni.showToast({ title: '请先在"我的"页完成骑手入驻', icon: 'none' })
    return
  }
  riderInfo.value = await api.riderMe()
  online.value = riderInfo.value.onlineStatus > 0
  grabList.value = online.value ? await api.grabList() : []
  const mine = await api.riderOrders()
  myOrders.value = mine.content || []
}

onShow(() => load())

const toggleOnline = async () => {
  await api.riderOnline(online.value ? 0 : 1)
  online.value = !online.value
  uni.showToast({ title: online.value ? '已开启抢单' : '已下线', icon: 'none' })
  load()
}

const doGrab = async (id) => {
  const res = await api.grab(id)
  uni.showModal({
    title: '抢单成功（公平锁）',
    content: `取件码：${res.pickupCode}，请凭此码到取件点核销`,
    showCancel: false,
    success: () => load()
  })
}

const doPickup = async (id) => {
  const { confirm } = await new Promise((r) =>
    uni.showModal({ title: '取件核销', content: '确认已到达取件点并核对取件码？', success: r }))
  if (confirm) { await api.pickup(id, ''); load() }
}

const doDepart = async (id) => { await api.depart(id); uni.showToast({ title: '开始配送', icon: 'none' }); load() }

const doDeliver = async (id) => {
  await api.deliver(id)
  uni.showToast({ title: '已送达，等待用户确认', icon: 'success' })
  load()
}

/** 模拟轨迹上报（德江县城中心附近随机点；生产由SDK按5-10秒自适应上报） */
const reportTrack = async (id) => {
  await api.reportTrack({
    orderId: id,
    lng: (108.1175 + Math.random() * 0.01).toFixed(6),
    lat: (28.2645 + Math.random() * 0.01).toFixed(6),
    speed: (Math.random() * 8 + 2).toFixed(2)
  })
  uni.showToast({ title: '轨迹已上报', icon: 'none' })
}
</script>

<style>
.rider-head { display: flex; justify-content: space-between; align-items: center; }
.name { font-size: 32rpx; font-weight: 700; }
.meta { font-size: 24rpx; color: #909399; margin-top: 6rpx; }
.mode { background: #f5f6f8; color: #909399; font-size: 26rpx; border-radius: 40rpx; height: 72rpx; line-height: 72rpx; padding: 0 30rpx; }
.mode.on { background: #fff2e0; color: #ff7d00; }
.sec-title { font-weight: 600; margin-bottom: 18rpx; }
.grab { display: flex; justify-content: space-between; align-items: center; padding: 18rpx 0; border-bottom: 1rpx solid #f2f3f5; }
.grab-info { flex: 1; display: flex; flex-direction: column; gap: 6rpx; }
.type { color: #ff7d00; font-size: 26rpx; font-weight: 600; }
.addr { font-size: 26rpx; color: #303133; }
.grab-btn { background: #ff7d00; color: #fff; font-size: 24rpx; border-radius: 30rpx; }
.mine { padding: 18rpx 0; border-bottom: 1rpx solid #f2f3f5; }
.acts { display: flex; gap: 12rpx; margin-top: 12rpx; flex-wrap: wrap; }
.act { background: #f5f6f8; color: #606266; font-size: 22rpx; border-radius: 26rpx; }
.act.main { background: #ff7d00; color: #fff; }
.empty { color: #c0c4cc; font-size: 24rpx; text-align: center; padding: 30rpx 0; }
</style>
