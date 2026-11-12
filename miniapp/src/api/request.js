/**
 * 统一请求封装（对应后端 /api/v1/**）：
 * - 自动携带 Bearer Token
 * - code!==0 统一 toast
 * - 401 清理登录态跳转登录页
 * 真机联调时将 BASE 改为后端局域网地址（同一 Wi-Fi）
 */
const BASE = 'http://localhost:8080'

const request = (options = {}) => new Promise((resolve, reject) => {
  const token = uni.getStorageSync('feilv_token')
  uni.request({
    url: BASE + options.url,
    method: options.method || 'GET',
    data: options.data || {},
    header: {
      'Content-Type': 'application/json',
      'Authorization': token ? 'Bearer ' + token : ''
    },
    success: (res) => {
      const body = res.data
      if (body.code === 0) return resolve(body.data)
      if (res.statusCode === 401 || body.code === 401) {
        uni.removeStorageSync('feilv_token')
        uni.removeStorageSync('feilv_role')
        uni.navigateTo({ url: '/pages/login/login' })
        return reject(body)
      }
      uni.showToast({ title: body.msg || '请求失败', icon: 'none' })
      reject(body)
    },
    fail: (err) => {
      uni.showToast({ title: '网络异常，请检查后端服务', icon: 'none' })
      reject(err)
    }
  })
})

// 兼容 api/index.js 的方法式调用，保留旧源码的函数式调用方式。
request.get = (url, data) => request({ url, method: 'GET', data })
request.post = (url, data) => request({ url, method: 'POST', data })
request.put = (url, data) => request({ url, method: 'PUT', data })
request.delete = (url, data) => request({ url, method: 'DELETE', data })

/** 简易轮询：等待订单状态变化（轨迹刷新≤5秒由轮询实现） */
export const poll = (fn, times = 10, gap = 2000) => new Promise((resolve, reject) => {
  let n = 0
  const timer = setInterval(async () => {
    n++
    try {
      const data = await fn()
      if (data && n >= times) { clearInterval(timer); resolve(data) }
    } catch (e) {
      if (n >= times) { clearInterval(timer); reject(e) }
    }
  }, gap)
})

export default request
