const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, toast } = require('../../utils/page')
const { mobile } = require('../../utils/format')
definePage({
  data: { agreed: false, sms: false, mobile: '', code: '', busy: false, sending: false, cooling: 0, smsLabel: '获取验证码', demoCode: '' },
  onShow() { this.updateCooling() },
  consent(e) { this.setData({ agreed: e.detail.value.includes('yes') }) },
  toggleSms() { this.setData({ sms: !this.data.sms }) },
  input(e) { const field = e.currentTarget.dataset.field; if (['mobile', 'code'].includes(field)) this.setData({ [field]: e.detail.value.trim() }) },
  legal(e) { wx.navigateTo({ url: '/pages/legal/legal?kind=' + e.currentTarget.dataset.kind }) },
  checkConsent() { if (!this.data.agreed) { toast('请先阅读并同意服务与隐私说明'); return false } return true },
  updateCooling() {
    const cooling = Math.max(0, Math.ceil(((this._smsUntil || 0) - Date.now()) / 1000))
    this.safeSet({ cooling, smsLabel: cooling ? cooling + '秒后重试' : '获取验证码' })
    if (cooling && this._visible) this.later(this.updateCooling, 1000)
  },
  sendCode() {
    if (!this.checkConsent() || this.data.cooling) return
    if (!mobile(this.data.mobile)) return toast('请输入正确的11位手机号')
    return this.run('sending', async () => {
      const result = await api.smsCode(this.data.mobile)
      this._smsUntil = Date.now() + 60000
      this.safeSet({ demoCode: typeof result === 'string' && /^\d{4,8}$/.test(result) ? result : '' })
      this.updateCooling()
      toast('验证码已发送；如显示演示码可手动填写')
    })
  },
  async finish(value) {
    if (this._dead || !this._visible) return
    session.save(value)
    toast('登录成功')
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack({ delta: 1 })
    else wx.switchTab({ url: '/pages/my/my' })
  },
  wechat() {
    if (!this.checkConsent()) return
    return this.run('busy', async () => {
      const result = await new Promise((resolve, reject) => wx.login({ timeout: 15000, success: resolve, fail: () => reject(new Error('微信登录失败，请检查 AppID 或稍后重试')) }))
      if (this._dead || !this._visible) return
      if (!result.code) throw new Error('微信未返回登录 code，请使用真实 AppID 联调')
      const value = await api.wechatLogin(result.code)
      if (!value || !['USER', 'RIDER'].includes(value.role)) throw new Error('微信登录返回的账户类型无效')
      await this.finish(value)
    })
  },
  smsLogin() {
    if (!this.checkConsent()) return
    if (!mobile(this.data.mobile) || !/^\d{4,8}$/.test(this.data.code)) return toast('请填写正确的手机号与验证码')
    return this.run('busy', async () => this.finish(await api.login(this.data.mobile, this.data.code)))
  }
})
