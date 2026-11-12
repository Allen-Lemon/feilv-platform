<template>
  <view>
    <view class="card" v-if="me.userId">
      <view class="user-row">
        <view class="avatar">{{ me.nickname?.slice(0, 1) || '客' }}</view>
        <view>
          <view class="nick">{{ me.nickname }}</view>
          <view class="mobile">{{ me.mobile }}（已脱敏）</view>
        </view>
      </view>
    </view>
    <view class="card" v-else>
      <button class="btn-primary" @click="login">登录 / 注册</button>
    </view>

    <!-- 骑手入驻（演示：即时开通；生产：审核≤1个工作日+培训考试≥80分） -->
    <view class="card" v-if="role !== 'RIDER'">
      <view class="sec-title">成为飞毛腿骑手</view>
      <input v-model="rider.mobile" type="number" maxlength="11" placeholder="手机号" class="input" />
      <input v-model="rider.name" placeholder="真实姓名" class="input" />
      <input v-model="rider.plateNo" placeholder="车牌号（选填）" class="input" />
      <view class="row">
        <input v-model="rider.code" type="number" maxlength="6" placeholder="验证码" class="input code" />
        <button class="mini" @click="sendRiderCode">获取验证码</button>
      </view>
      <button class="btn-primary" @click="registerRider">提交入驻</button>
      <view class="hint">演示模式验证码自动回填；生产需健康证明、意外险与职业伤害保障</view>
    </view>

    <view class="card" v-else>
      <view class="sec-title">骑手服务</view>
      <button class="btn-primary" @click="goWorkbench">进入骑手工作台</button>
    </view>

    <view class="card" v-if="me.userId">
      <button class="btn-primary" @click="goAddress">地址簿管理</button>
      <button class="btn-out" @click="logout">退出登录</button>
    </view>
  </view>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import api from '../../api/index'

const me = ref({})
const role = ref(uni.getStorageSync('feilv_role') || '')
const rider = reactive({ mobile: '', name: '', plateNo: '', code: '' })

const loadMe = async () => {
  if (!uni.getStorageSync('feilv_token')) return
  me.value = await api.me()
  role.value = uni.getStorageSync('feilv_role') || 'USER'
}
onShow(() => loadMe())

const login = () => uni.navigateTo({ url: '/pages/login/login' })

const sendRiderCode = async () => {
  const c = await api.smsCode(rider.mobile)
  rider.code = c // 演示回填
}

const registerRider = async () => {
  const data = await api.riderRegister(rider)
  uni.setStorageSync('feilv_token', data.token)
  uni.setStorageSync('feilv_role', 'RIDER')
  uni.showToast({ title: '入驻成功', icon: 'success' })
  setTimeout(() => uni.navigateTo({ url: '/pages/rider/workbench' }), 600)
}

const goWorkbench = () => uni.navigateTo({ url: '/pages/rider/workbench' })
const goAddress = () => uni.navigateTo({ url: '/pages/address/address' })

const logout = () => {
  uni.removeStorageSync('feilv_token')
  uni.removeStorageSync('feilv_role')
  uni.removeStorageSync('feilv_user_id')
  me.value = {}
  role.value = ''
  uni.showToast({ title: '已退出', icon: 'none' })
}
</script>

<style>
.user-row { display: flex; align-items: center; gap: 24rpx; }
.avatar { width: 100rpx; height: 100rpx; border-radius: 50%; background: #ff7d00; color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 44rpx; }
.nick { font-size: 32rpx; font-weight: 600; }
.mobile { font-size: 24rpx; color: #909399; margin-top: 6rpx; }
.sec-title { font-weight: 600; margin-bottom: 18rpx; }
.input { background: #f5f6f8; padding: 20rpx 24rpx; border-radius: 12rpx; margin-bottom: 16rpx; font-size: 28rpx; }
.row { display: flex; align-items: center; }
.code { flex: 1; margin-right: 16rpx; }
.mini { background: #fff2e0; color: #ff7d00; font-size: 24rpx; border-radius: 12rpx; height: 76rpx; line-height: 76rpx; padding: 0 20rpx; }
.hint { font-size: 22rpx; color: #c0c4cc; margin-top: 16rpx; }
.btn-out { background: #fff; color: #f56c6c; border: 1rpx solid #f56c6c; border-radius: 44rpx; font-size: 28rpx; }
</style>
