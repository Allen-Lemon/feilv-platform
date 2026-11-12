<template>
  <view>
    <view class="card">
      <view class="sec-title">取件信息</view>
      <input v-model="form.fromContact" placeholder="取件人姓名" class="input" />
      <input v-model="form.fromPhone" type="number" maxlength="11" placeholder="取件人电话" class="input" />
      <input v-model="form.fromAddress" placeholder="取件地址（生产为地图选点）" class="input" />

      <view class="sec-title">送达信息</view>
      <input v-model="form.toContact" placeholder="收件人姓名" class="input" />
      <input v-model="form.toPhone" type="number" maxlength="11" placeholder="收件人电话" class="input" />
      <input v-model="form.toAddress" placeholder="送达地址（生产为地图选点）" class="input" />

      <view class="sec-title">物品与要求</view>
      <input v-model="form.goodsDesc" placeholder="物品名称（如：文件、蛋糕、药品）" class="input" />
      <button class="photo-btn" @click="choosePhoto">{{ form.photoCount ? `已选择${form.photoCount}张物品照片` : '上传物品照片（选填）' }}</button>
      <picker :range="weightOptions" @change="pickWeight">
        <view class="picker">{{ weightOptions[weightIndex] }}</view>
      </picker>
      <input v-model="distanceKm" type="digit" placeholder="预估距离（公里，生产由地图路线计算）" class="input" />
      <textarea v-model="form.remark" placeholder="备注（如：放门口、轻拿轻放）" class="textarea" />
    </view>

    <view class="card" v-if="quote.totalFen">
      <view class="sec-title">费用预估（生产实时计算）</view>
      <view class="fee" v-for="it in quote.items" :key="it.feeType">
        <text>{{ it.label }}</text><text>￥{{ (it.amountFen / 100).toFixed(2) }}</text>
      </view>
      <view class="fee total">
        <text>预计费用</text><text class="price">￥{{ (quote.totalFen / 100).toFixed(2) }}</text>
      </view>
      <button class="btn-primary" style="margin-top: 24rpx" @click="next">下一步，确认订单</button>
    </view>
    <view class="card" v-else>
      <button class="btn-primary" @click="doQuote">计算费用</button>
    </view>
  </view>
</template>

<script setup>
import { reactive, ref } from 'vue'
import api from '../../api/index'

const type = uni.getStorageSync('feilv_order_type') || 'SEND'
const form = reactive({
  orderType: type, fromContact: '', fromPhone: '', fromAddress: '',
  toContact: '', toPhone: '', toAddress: '',
  goodsDesc: '', remark: '', photoCount: 0
})
const weightOptions = ['5公斤以内', '5-10公斤', '10-20公斤', '20公斤以上']
const weightKgMap = [5, 10, 20, 30]
const weightIndex = ref(0)
const distanceKm = ref('')
const quote = ref({})

const pickWeight = (e) => { weightIndex.value = Number(e.detail.value) }
const choosePhoto = () => uni.chooseImage({ count: 3, success: e => { form.photoCount = e.tempFilePaths.length } })

const doQuote = () => {
  if (!form.fromAddress || !form.toAddress) return uni.showToast({ title: '请填写取件与送达地址', icon: 'none' })
  if (!form.goodsDesc) return uni.showToast({ title: '请填写物品名称', icon: 'none' })
  api.quote({
    orderType: form.orderType,
    distanceM: Math.max(500, Math.round(parseFloat(distanceKm.value || 2) * 1000)),
    weightKg: weightKgMap[weightIndex.value]
  }).then((d) => { quote.value = d })
}

const next = () => {
  const payload = { ...form, distanceM: Math.max(500, Math.round(parseFloat(distanceKm.value || 2) * 1000)), weightKg: weightKgMap[weightIndex.value] }
  uni.setStorageSync('feilv_order_draft', JSON.stringify(payload))
  uni.setStorageSync('feilv_quote', JSON.stringify(quote.value))
  uni.navigateTo({ url: '/pages/order/confirm' })
}
</script>

<style>
.sec-title { font-weight: 600; margin: 24rpx 0 16rpx; font-size: 30rpx; }
.sec-title:first-child { margin-top: 0; }
.input { background: #f5f6f8; padding: 20rpx 24rpx; border-radius: 12rpx; margin-bottom: 16rpx; font-size: 28rpx; }
.picker { background: #f5f6f8; padding: 20rpx 24rpx; border-radius: 12rpx; margin-bottom: 16rpx; color: #606266; }
.textarea { background: #f5f6f8; padding: 20rpx 24rpx; border-radius: 12rpx; width: 100%; height: 120rpx; font-size: 28rpx; }
.photo-btn { margin: 12rpx 0; background:#fff2e0; color:#ff7d00; font-size:24rpx; }
.fee { display: flex; justify-content: space-between; color: #606266; font-size: 26rpx; padding: 8rpx 0; }
.total { border-top: 1rpx solid #eee; margin-top: 10rpx; padding-top: 16rpx; font-weight: 600; color: #303133; }
.price { color: #ff7d00; font-size: 34rpx; }
</style>
