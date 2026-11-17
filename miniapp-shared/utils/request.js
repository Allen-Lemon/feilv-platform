const config = require('../config')
const session = require('./session')
function canOverrideBaseURL() {
  if (!config.ALLOW_DEV_SERVER_OVERRIDE) return false
  try { return wx.getAccountInfoSync().miniProgram.envVersion === 'develop' } catch { return false }
}
function baseURL() {
  const override = canOverrideBaseURL() ? wx.getStorageSync('native_base_url') : ''
  return (override || config.BASE_URL).replace(/\/+$/, '')
}
function apiURL(path) {
  const base = baseURL()
  // API_BASE_URL / BASE_URL 可包含 /api，不能拼出 /api/api/v1。
  return base + (/\/api$/.test(base) && path.startsWith('/api/') ? path.slice(4) : path)
}
function avatarURL(path) {
  const origin = baseURL().match(/^https?:\/\/[^/?#]+/i)
  return origin && session.avatarPath(path) ? origin[0] + path : ''
}
function initializeServerConfig() {
  const current = baseURL()
  const previous = wx.getStorageSync('native_effective_base_url')
  const legacy = wx.getStorageSync('native_base_url')
  // 只清理曾发往其他服务的会话/草稿，不将开发环境凭据带到线上。
  if ((previous && previous !== current) || (!previous && (session.token() || (legacy && String(legacy).replace(/\/+$/, '') !== current)))) session.clear()
  if (!canOverrideBaseURL()) wx.removeStorageSync('native_base_url')
  wx.setStorageSync('native_effective_base_url', current)
}
function failure(message, extra) { return Object.assign(new Error(message), extra || {}) }
function stale() { return failure('账户状态已变化，请重试', { silent: true }) }
function context(options) {
  if (options.sessionRevision !== undefined && options.sessionRevision !== session.revision()) throw stale()
  const token = session.token(); const revision = session.revision()
  return { token, check() { if (revision !== session.revision() || token !== session.token()) throw stale() } }
}
function response(res, options, ctx, uploaded) {
  ctx.check()
  let body = res.data
  if (uploaded && typeof body === 'string') { try { body = JSON.parse(body) } catch { body = null } }
  const businessCode = body && typeof body === 'object' ? body.code : undefined
  if (res.statusCode === 401 || businessCode === 401 || businessCode === 40101) {
    session.expire()
    if (!options.public) session.login()
    throw failure('登录已过期，请重新登录', { status: 401 })
  }
  if (res.statusCode < 200 || res.statusCode >= 300) {
    const msg = body && typeof body.msg === 'string' ? body.msg : '服务请求失败（HTTP ' + res.statusCode + '）'
    throw failure(msg, { status: res.statusCode, code: businessCode })
  }
  if (!body || typeof body !== 'object' || typeof body.code !== 'number') throw failure('服务响应格式错误，预期 Result 响应体')
  if (body.code !== 0) throw failure(body.msg || '业务处理失败', { code: body.code })
  return body.data
}
function request(options) {
  let ctx
  try { ctx = context(options) } catch (e) { return Promise.reject(e) }
  return new Promise((resolve, reject) => {
    wx.request({
      url: apiURL(options.url),
      method: options.method || 'GET',
      data: options.data === undefined ? {} : options.data,
      timeout: config.REQUEST_TIMEOUT,
      header: Object.assign({ 'Content-Type': 'application/json' }, ctx.token ? { Authorization: 'Bearer ' + ctx.token } : {}),
      success(res) { try { resolve(response(res, options, ctx, false)) } catch (e) { reject(e) } },
      fail(err) {
        try { ctx.check(); reject(failure('网络异常，请检查服务地址、合法域名与网络连接', { cause: err })) } catch (e) { reject(e) }
      }
    })
  })
}
async function uploadAvatar(filePath, options = {}) {
  const ctx = context(options)
  if (!ctx.token) { session.login(); throw failure('请先登录', { status: 401 }) }
  if (typeof filePath !== 'string' || !filePath) throw failure('请先选择头像')
  const inspect = method => new Promise((resolve, reject) => wx[method]({
    filePath, src: filePath,
    success(result) { try { ctx.check(); resolve(result) } catch (e) { reject(e) } },
    fail() { try { ctx.check(); reject(failure('无法读取头像，请重新选择 JPEG 或 PNG 图片')) } catch (e) { reject(e) } }
  }))
  const file = await inspect('getFileInfo')
  if (!Number.isFinite(file.size) || file.size <= 0 || file.size > 5 * 1024 * 1024) throw failure('头像大小须为5MB以内')
  const image = await inspect('getImageInfo')
  if (!['jpeg', 'jpg', 'png'].includes(String(image.type).toLowerCase())) throw failure('头像仅支持 JPEG 或 PNG 图片')
  ctx.check()
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: apiURL('/api/v1/user/avatar'), filePath, name: 'file', timeout: config.REQUEST_TIMEOUT,
      header: { Authorization: 'Bearer ' + ctx.token },
      success(res) { try { resolve(response(res, options, ctx, true)) } catch (e) { reject(e) } },
      fail(err) {
        try { ctx.check(); reject(failure('头像上传失败，请检查网络及 uploadFile 合法域名后重试', { cause: err })) } catch (e) { reject(e) }
      }
    })
  })
}
request.uploadAvatar = uploadAvatar
;['get', 'post', 'put', 'delete'].forEach(method => {
  request[method] = (url, data, options) => request(Object.assign({}, options, { url, data, method: method.toUpperCase() }))
})
function bindSession(revision) {
  const scoped = { uploadAvatar: filePath => uploadAvatar(filePath, { sessionRevision: revision }) }
  ;['get', 'post', 'put', 'delete'].forEach(method => {
    scoped[method] = (url, data, options) => request[method](url, data, Object.assign({}, options, { sessionRevision: revision }))
  })
  return scoped
}
module.exports = { request, baseURL, apiURL, avatarURL, canOverrideBaseURL, initializeServerConfig, bindSession }
