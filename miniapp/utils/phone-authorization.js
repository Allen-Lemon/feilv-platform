// 新建账号与入驻均要求本次原生手机号 code；不接受手填号码或开发免验证作为归属证明。
function supported() {
  if (typeof wx.canIUse !== 'function' || !wx.canIUse('button.open-type.getPhoneNumber')) return false
  try {
    const info = typeof wx.getAppBaseInfo === 'function' ? wx.getAppBaseInfo() : wx.getSystemInfoSync()
    const v = String(info.SDKVersion || '').split('.').map(Number)
    return v[0] > 2 || v[0] === 2 && (v[1] > 21 || v[1] === 21 && v[2] >= 2)
  } catch { return false }
}
async function privacy(page, active) {
  const attempt = page._nativePrivacyAttempt = (page._nativePrivacyAttempt || 0) + 1
  const current = () => attempt === page._nativePrivacyAttempt && active()
  if (!current()) return
  const phoneSupported = supported()
  page.safeSet({ phoneSupported, privacyReady: false, privacyNeeded: false })
  if (!phoneSupported) return
  if (typeof wx.getPrivacySetting !== 'function') { page.safeSet({ privacyReady: true }); return }
  await new Promise((resolve, reject) => wx.getPrivacySetting({
    success(value) {
      if (current()) page.safeSet({ privacyNeeded: value.needAuthorization === true, privacyReady: value.needAuthorization !== true,
        privacyGateSupported: wx.canIUse('button.open-type.agreePrivacyAuthorization'), privacyTitle: value.privacyContractName || '微信隐私保护指引' })
      resolve()
    },
    fail() { if (current()) reject(new Error('无法读取微信隐私授权状态，请重试')); else resolve() }
  }))
}
function phoneCode(event) {
  const detail = event && event.detail || {}
  if (detail.errMsg !== 'getPhoneNumber:ok' || typeof detail.code !== 'string' || !detail.code.trim()) throw new Error('需要本次真实微信手机号授权；拒绝授权不会创建或变更账号，请重新点击授权')
  return detail.code.trim()
}
function wxCode() {
  return new Promise((resolve, reject) => wx.login({ timeout: 15000,
    success(value) { if (value && value.code) resolve(value.code); else reject(new Error('微信未返回登录凭证，请检查真实 AppID 配置')) },
    fail() { reject(new Error('微信登录失败，请检查真实 AppID 或稍后重试')) }
  }))
}
module.exports = { supported, privacy, phoneCode, wxCode }
