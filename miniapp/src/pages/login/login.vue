<template>
  <view class="card">
    <view class="h">手机号快捷登录</view>
    <view class="sub">未注册的手机号将自动创建账号</view>
    <view class="row">
      <input v-model="mobile" type="number" maxlength="11" placeholder="请输入手机号" class="input" />
    </view>
    <view class="row">
      <input v-model="code" type="number" maxlength="6" placeholder="短信验证码" class="input code" />
      <button class="mini" :disabled="cooling > 0" @click="sendCode">
        {{ cooling > 0 ? cooling + 's' : '获取验证码' }}
      </button>
    </view>
    <button class="btn-primary" @click="doLogin">登 录</button>
    <view class="hint">演示模式：验证码将自动回填（生产仅经短信下发）</view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import api from '../../api/index'

const mobile = ref('')
const code = ref('')
const cooling = ref(0)

const sendCode = async () => {
  if (!/^1\d{10}$/.test(mobile.value)) return uni.showToast({ title: '手机号格式不正确', icon: 'none' })
  const c = await api.smsCode(mobile.value)
  code.value = c // 演示模式自动回填
  uni.showToast({ title: '验证码已发送', icon: 'success' })
  cooling.value = 60
  const timer = setInterval(() => {
    cooling.value--
    if (cooling.value <= 0) clearInterval(timer)
  }, 1000)
}

const doLogin = async () => {
  if (!code.value) return uni.showToast({ title: '请输入验证码', icon: 'none' })
  const data = await api.login(mobile.value, code.value)
  uni.setStorageSync('feilv_token', data.token)
  uni.setStorageSync('feilv_role', data.role)
  uni.setStorageSync('feilv_user_id', data.userId)
  uni.showToast({ title: '登录成功', icon: 'success' })
  setTimeout(() => uni.switchTab({ url: '/pages/index/index' }), 600)
}
</script>

<style>
.h { font-size: 36rpx; font-weight: 700; }
.sub { font-size: 24rpx; color: #909399; margin: 8rpx 0 30rpx; }
.row { display: flex; align-items: center; margin-bottom: 24rpx; }
.input { flex: 1; background: #f5f6f8; padding: 22rpx 24rpx; border-radius: 14rpx; font-size: 28rpx; }
.code { flex: 1; margin-right: 16rpx; }
.mini { background: #fff2e0; color: #ff7d00; font-size: 24rpx; border-radius: 14rpx; padding: 0 20rpx; height: 78rpx; line-height: 78rpx; }
.hint { font-size: 22rpx; color: #c0c4cc; margin-top: 20rpx; text-align: center; }
</style>
