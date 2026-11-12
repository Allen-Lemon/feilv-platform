<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2>飞毛腿跑腿平台 · 管理后台</h2>
      <p class="sub">德江县"飞毛腿"城市同城跑腿平台</p>
      <el-form :model="form" @keyup.enter="doLogin">
        <el-form-item>
          <el-input v-model="form.username" placeholder="账号（演示：admin）" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="doLogin">登 录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api'

const router = useRouter()
const form = reactive({ username: 'admin', password: '' })
const loading = ref(false)

const doLogin = async () => {
  if (!form.username || !form.password) return ElMessage.warning('请输入账号和密码')
  loading.value = true
  try {
    const data = await adminApi.login(form)
    localStorage.setItem('feilv_admin_token', data.token)
    localStorage.setItem('feilv_admin_nickname', data.nickname || '管理员')
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap { height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #1d2b45 0%, #409EFF 100%); }
.login-card { width: 380px; }
h2 { text-align: center; margin-bottom: 4px; }
.sub { text-align: center; color: #909399; font-size: 13px; margin-bottom: 18px; }
</style>
