import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

// 统一请求封装：携带 Token、统一错误提示、401 踢回登录
const request = axios.create({ baseURL: '/api', timeout: 10000 })

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('feilv_admin_token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})

request.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body.code !== 0) {
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(body)
    }
    return body.data
  },
  (err) => {
    const body = err.response?.data
    if (err.response?.status === 401) {
      localStorage.removeItem('feilv_admin_token')
      router.push('/login')
      ElMessage.error(body?.msg || '登录已失效')
    } else {
      ElMessage.error(body?.msg || '网络异常，请稍后再试')
    }
    return Promise.reject(err)
  }
)

export default request
