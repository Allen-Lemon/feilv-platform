<template>
  <div class="login-page">
    <section class="login-story">
      <div class="login-brand"><span class="login-logo">F↗</span><span>飞毛腿 <small>FEILV BUSINESS</small></span></div>
      <div class="story-copy"><span class="story-eyebrow">为城市里的每一份需要</span><h1>让生活快一点，<br />让生意近一点<span>。</span></h1><p>连接用户与骑手，把每一次托付<br class="desktop-break" />变成值得信赖的同城服务。</p>
        <div class="delivery-art" aria-hidden="true"><div class="art-orbit" /><div class="art-card origin"><span>取</span><div><strong>一份托付</strong><small>从这里出发</small></div></div><div class="art-route"><span /><i /><span /></div><div class="art-card destination"><span>达</span><div><strong>一份安心</strong><small>准时抵达身边</small></div><AppIcon name="check" /></div><div class="art-sticker">LET'S GO ↗</div></div>
      </div>
      <div class="story-footer"><span>德江同城生活服务平台</span><span>用心连接 · 轻松抵达</span></div>
    </section>
    <section class="login-form-section"><div class="login-form-wrap"><span class="eyebrow">WELCOME BACK</span><h2>欢迎回到工作空间</h2><p class="login-sub">登录管理账户，开启今天的运营工作。</p>
      <el-form :model="form" label-position="top" @keyup.enter="doLogin">
        <el-form-item label="管理账号"><el-input v-model="form.username" autocomplete="username" placeholder="请输入管理账号" size="large" :disabled="loading"><template #prefix><AppIcon name="users" :size="18" /></template></el-input></el-form-item>
        <el-form-item label="登录密码"><el-input v-model="form.password" type="password" show-password autocomplete="current-password" placeholder="请输入登录密码" size="large" :disabled="loading"><template #prefix><AppIcon name="lock" :size="18" /></template></el-input></el-form-item>
        <el-button class="login-submit" type="primary" :loading="loading" @click="doLogin"><span>登录工作空间</span><AppIcon v-if="!loading" name="arrow" :size="19" /></el-button>
      </el-form>
      <div class="login-help"><AppIcon name="shield" :size="16" /><span>仅供已授权管理人员使用，请妥善保管账号。</span></div>
      <div class="login-env">当前为业务演示版本，支付等第三方能力尚未接入。</div>
    </div><p class="form-footer">FEILV · 每一份托付，都认真对待</p></section>
  </div>
</template>
<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api'
import AppIcon from '../components/AppIcon.vue'
const router = useRouter()
const form = reactive({ username: 'admin', password: '' })
const loading = ref(false)
const doLogin = async () => {
  if (loading.value) return
  if (!form.username.trim() || !form.password) return ElMessage.warning('请输入账号和密码')
  loading.value = true
  try {
    const data = await adminApi.login({ username: form.username.trim(), password: form.password })
    localStorage.setItem('feilv_admin_token', data.token)
    localStorage.setItem('feilv_admin_nickname', data.nickname || '管理员')
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch { /* 请求层统一提示，保留表单便于重试。 */ } finally { loading.value = false }
}
</script>
<style scoped>
.login-page { min-height: 100vh; display: grid; grid-template-columns: 1.1fr 1fr; background: white; }.login-story { background: #fff0e5; padding: 44px 64px 30px; display: flex; flex-direction: column; min-height: 100vh; overflow: hidden; }.login-brand { display: flex; align-items: center; gap: 12px; font-size: 24px; font-weight: 700; letter-spacing: 1px; }.login-logo { width: 46px; height: 46px; border-radius: 14px; background: #ff7547; display: flex; align-items: center; justify-content: center; font-style: italic; font-size: 27px; letter-spacing: -4px; padding-right: 4px; }.login-brand small { display: block; font-size: 9px; letter-spacing: 1.5px; margin-top: 3px; font-weight: 500; color: #76523d; }.story-copy { margin: auto 0; padding: 70px 0 30px; }.story-eyebrow { font-size: 12px; color: #9b4a25; letter-spacing: 2px; }.story-copy h1 { margin: 24px 0; font-size: clamp(32px, 3.6vw, 56px); line-height: 1.45; letter-spacing: -2px; font-weight: 750; }.story-copy h1 > span { color: #c94320; }.story-copy > p { color: #765d4e; font-size: 14px; line-height: 1.9; }
.delivery-art { position: relative; height: 220px; width: 410px; max-width: 100%; margin-top: 25px; }.art-orbit { position: absolute; width: 200px; height: 200px; border: 1px solid #e9cdb7; border-radius: 50%; top: 6px; left: 80px; }.art-card { display: flex; gap: 12px; align-items: center; border: 1px solid #f0dfd3; background: #fffdfb; padding: 18px 24px 18px 16px; border-radius: 15px; width: 215px; position: absolute; box-shadow: 0 10px 20px #97522b08; }.art-card > span { display: flex; align-items: center; justify-content: center; width: 38px; height: 38px; border-radius: 12px; background: #fff0e5; color: #b23f1b; font-weight: 600; font-size: 13px; }.art-card strong { font-size: 13px; }.art-card small { display: block; margin-top: 5px; color: #667085; font-size: 11px; }.origin { top: 12px; left: 0; transform: rotate(-5deg); }.destination { bottom: 20px; right: 0; transform: rotate(4deg); }.destination > span { background: #c94320; color: white; }.destination svg { color: #168563; margin-left: auto; }.art-route { position: absolute; width: 160px; height: 60px; border-bottom: 2px dashed #cf9b79; border-right: 2px dashed #cf9b79; left: 102px; top: 88px; border-radius: 0 0 22px 0; }.art-sticker { position: absolute; background: #ffd572; padding: 8px 12px; border-radius: 6px; font-size: 11px; font-weight: 750; letter-spacing: .8px; top: 4px; right: 0; transform: rotate(8deg); }
.story-footer { display: flex; justify-content: space-between; gap: 12px; font-size: 10px; color: #856a59; line-height: 1.8; }.login-form-section { position: relative; display: flex; align-items: center; justify-content: center; padding: 72px 40px 100px; }.login-form-wrap { max-width: 360px; width: 100%; }.login-form-wrap h2 { font-size: 29px; letter-spacing: -.7px; margin: 16px 0 12px; }.login-sub { font-size: 13px; color: #667085; margin-bottom: 38px; }.login-form-wrap .el-form-item { margin-bottom: 23px; }.login-form-wrap :deep(.el-input__wrapper) { padding: 5px 15px; min-height: 49px; }.login-form-wrap :deep(.el-form-item__label) { font-size: 12px; }.login-form-wrap :deep(.el-input__prefix) { margin-right: 7px; }.login-submit { width: 100%; height: 49px; margin-top: 8px; }.login-submit :deep(span) { gap: 60px; }.login-help { display: flex; align-items: center; justify-content: center; gap: 7px; margin-top: 25px; color: #667085; font-size: 10px; line-height: 1.7; }.login-env { text-align: center; font-size: 10px; color: #667085; line-height: 1.7; margin-top: 25px; }.form-footer { position: absolute; bottom: 30px; font-size: 10px; color: #667085; }
@media (max-width: 1000px) { .login-story { padding: 36px; }.story-copy h1 { font-size: 34px; }.story-footer span:last-child { display: none; } }
@media (max-width: 720px) { .login-page { display: block; }.login-story { min-height: 0; padding: 28px; }.story-copy { padding: 28px 0 0; }.story-copy h1 { font-size: 28px; margin: 13px 0 0; }.story-copy > p,.story-eyebrow,.delivery-art,.story-footer { display: none; }.login-form-section { padding: 38px 28px 82px; min-height: 560px; }.login-form-wrap h2 { font-size: 25px; }.login-sub { margin-bottom: 28px; } }
</style>
