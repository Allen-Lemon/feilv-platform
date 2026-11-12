const config = require('../config')
const session = require('./session')
function baseURL() { return (wx.getStorageSync('native_base_url') || config.BASE_URL).replace(/\/+$/, '') }
function failure(message, extra) { return Object.assign(new Error(message), extra || {}) }
function request(options) {
  const capturedToken = session.token()
  const revision = session.revision()
  return new Promise((resolve, reject) => {
    wx.request({
      url: baseURL() + options.url,
      method: options.method || 'GET',
      data: options.data === undefined ? {} : options.data,
      timeout: config.REQUEST_TIMEOUT,
      header: Object.assign({ 'Content-Type': 'application/json' }, capturedToken ? { Authorization: 'Bearer ' + capturedToken } : {}),
      success(res) {
        if (revision !== session.revision() || capturedToken !== session.token()) {
          return reject(failure('账户状态已变化，请重试', { silent: true }))
        }
        const body = res.data
        const businessCode = body && typeof body === 'object' ? body.code : undefined
        if (res.statusCode === 401 || businessCode === 401) {
          session.clear()
          if (!options.public) session.login()
          return reject(failure('登录已过期，请重新登录', { status: 401 }))
        }
        if (res.statusCode < 200 || res.statusCode >= 300) {
          const msg = body && typeof body.msg === 'string' ? body.msg : '服务请求失败（HTTP ' + res.statusCode + '）'
          return reject(failure(msg, { status: res.statusCode, code: businessCode }))
        }
        if (!body || typeof body !== 'object' || typeof body.code !== 'number') {
          return reject(failure('服务响应格式错误，预期 Result 响应体'))
        }
        if (body.code !== 0) return reject(failure(body.msg || '业务处理失败', { code: body.code }))
        resolve(body.data)
      },
      fail(err) { reject(failure('网络异常，请检查服务地址、合法域名与网络连接', { cause: err })) }
    })
  })
}
;['get', 'post', 'put', 'delete'].forEach(method => {
  request[method] = (url, data, options) => request(Object.assign({}, options, { url, data, method: method.toUpperCase() }))
})
module.exports = { request, baseURL }
