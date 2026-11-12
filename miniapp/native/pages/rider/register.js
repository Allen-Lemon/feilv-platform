const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, toast, modal } = require('../../utils/page')
const { mobile } = require('../../utils/format')
definePage({
  data: { form: { mobile: '', name: '', plateNo: '', code: '' }, agreed: false, cooling: 0, smsLabel: '获取验证码', busy: false, sending: false, demoCode: '' },
  onShow() { this.updateCooling() },
  input(e) { const k = e.currentTarget.dataset.field; if (['mobile', 'name', 'plateNo', 'code'].includes(k)) this.setData({ ['form.' + k]: e.detail.value.trim() }) },
  consent(e) { this.setData({ agreed: e.detail.value.includes('yes') }) },
  updateCooling() {
    const cooling = Math.max(0, Math.ceil(((this._smsUntil || 0) - Date.now()) / 1000))
    this.safeSet({ cooling, smsLabel: cooling ? cooling + '秒后重试' : '获取验证码' })
    if (cooling && this._visible) this.later(this.updateCooling, 1000)
  },
  sendCode() {
    if (!this.data.agreed) return toast('请先阅读并同意入驻说明')
    if (!mobile(this.data.form.mobile)) return toast('请填写正确的手机号')
    if (this.data.cooling) return
    return this.run('sending', async () => {
      const result = await api.smsCode(this.data.form.mobile)
      this._smsUntil = Date.now() + 60000
      this.safeSet({ demoCode: typeof result === 'string' && /^\d{4,8}$/.test(result) ? result : '' })
      this.updateCooling(); toast('验证码已发送')
    })
  },
  register() {
    if (!this.data.agreed) return toast('请先同意入驻与资料使用说明')
    const data = Object.assign({}, this.data.form)
    if (!mobile(data.mobile) || !data.name || !/^\d{4,8}$/.test(data.code)) return toast('请填写手机号、真实姓名与验证码')
    return this.run('busy', async () => {
      if (!(await modal({ title: '确认骑手入驻', content: '当前后端为演示即时开通。此接口按手机号识别账户，可能切换当前微信账号的登录身份，不保证合并原账户订单。是否继续？' })).confirm) return
      const result = await api.riderRegister(data)
      if (!result || result.role !== 'RIDER') throw new Error('入驻接口未返回有效 RIDER 账户')
      if (this._dead || !this._visible) return
      session.save(result); toast('骑手账户已开通')
      if (!this._dead) wx.redirectTo({ url: '/pages/rider/workbench' })
    })
  },
  legal() { wx.navigateTo({ url: '/pages/legal/legal?kind=privacy' }) }
})
