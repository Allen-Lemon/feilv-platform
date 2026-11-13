const KEYS = ['feilv_token', 'feilv_role', 'feilv_user_id', 'feilv_nickname']
let navigating = false
let revision = 0
function clear() {
  KEYS.forEach(k => wx.removeStorageSync(k))
  ;['native_draft', 'native_pending_order'].forEach(k => wx.removeStorageSync(k))
  revision++
}
function save(value) {
  if (!value || !value.token || !value.userId || !['USER', 'RIDER'].includes(value.role)) {
    throw new Error('登录响应无效：需要 USER 或 RIDER 的 LoginVO')
  }
  clear()
  wx.setStorageSync('feilv_token', value.token)
  wx.setStorageSync('feilv_role', value.role)
  wx.setStorageSync('feilv_user_id', value.userId)
  wx.setStorageSync('feilv_nickname', value.nickname || '用户')
}
function token() { return wx.getStorageSync('feilv_token') || '' }
function login() {
  const pages = getCurrentPages()
  if (navigating || (pages.length && pages[pages.length - 1].route === 'pages/login/login')) return
  navigating = true
  wx.navigateTo({ url: '/pages/login/login', complete() { navigating = false } })
}
function requireLogin() {
  if (token()) return true
  login()
  return false
}
module.exports = { clear, save, token, login, requireLogin, revision: () => revision }
