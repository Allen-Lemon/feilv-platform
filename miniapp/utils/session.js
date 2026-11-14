const KEYS = ['feilv_token', 'feilv_role', 'feilv_user_id', 'feilv_nickname', 'feilv_avatar_url']
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
// 资料更新不属于重新登录，不能清除草稿或递增会话版本。
function updateNickname(value, expectedUserId, expectedRevision) {
  if (!token() || revision !== expectedRevision || String(wx.getStorageSync('feilv_user_id')) !== String(expectedUserId)
      || typeof value !== 'string') return false
  wx.setStorageSync('feilv_nickname', value)
  return true
}
// 只缓存服务端公开头像路径，绝不缓存 chooseAvatar 临时文件或任意外站地址。
function avatarPath(value) { return typeof value === 'string' && /^\/api\/v1\/avatars\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(?:jpg|png)$/i.test(value) ? value : '' }
function updateProfile(value, userId, expectedRevision) {
  if (!value || String(value.userId) !== String(userId) || !['USER', 'RIDER'].includes(value.role)
      || !updateNickname(value.nickname, userId, expectedRevision)) return false
  wx.setStorageSync('feilv_avatar_url', avatarPath(value.avatarUrl))
  return true
}
function validNickname(value) {
  if (typeof value !== 'string' || /[\u0000-\u001f\u007f-\u009f]/.test(value)) return ''
  const trimmed = value.trim()
  return trimmed && Array.from(trimmed).length <= 32 ? trimmed : ''
}
// 微信占位昵称不能代表用户主动完善；用户可在资料页主动选择/保存真实昵称。
function nicknameReady(value) { return !!validNickname(value) && !['微信用户', '微信昵称'].includes(value.trim()) }
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
function openRequiredProfile() {
  wx.showToast({ title: '下单前请保存昵称并绑定手机号，订单草稿已保留', icon: 'none' })
  wx.navigateTo({ url: '/pages/profile/profile?from=order' })
}
async function requireOrderProfile(api, active = () => true) {
  const expectedRevision = revision; const userId = wx.getStorageSync('feilv_user_id')
  const me = await api.me()
  if (revision !== expectedRevision || !token() || !active()) return false
  if (!updateProfile(me, userId, expectedRevision)) throw new Error('账户资料响应无效，请重试')
  if (me.mobileBound !== true || !nicknameReady(me.nickname)) { openRequiredProfile(); return false }
  return true
}
module.exports = { clear, save, updateNickname, updateProfile, avatarPath, validNickname, nicknameReady, token, login, requireLogin, requireOrderProfile, openRequiredProfile, revision: () => revision }
