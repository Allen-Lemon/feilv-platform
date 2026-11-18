const session = require('../../utils/session')
const { definePage, toast, modal } = require('../../utils/page')
const phone = require('../../utils/phone-authorization')
definePage({
  data: { form: { name: '', plateNo: '' }, agreed: false, busy: false, loading: false, loggedIn: false, error: '',
    developmentManualPhoneEnabled: false, enrollmentMobile: '', phoneOptionsError: '',
    phoneSupported: false, privacyReady: false, privacyNeeded: false, privacyGateSupported: false, privacyTitle: '微信隐私保护指引' },
  onShow() {
    this._generation = (this._generation || 0) + 1; this._phoneIntent = null
    this._owner = String(wx.getStorageSync('feilv_user_id'))
    this.setData({ loggedIn: !!session.token(), error: '', developmentManualPhoneEnabled: false, enrollmentMobile: '',
      phoneOptionsError: '', phoneSupported: false, privacyReady: false, privacyNeeded: false })
    if (!session.token()) return
    if (this._working) { this._showAfterRun = true; return }
    return this.run('loading', async api => {
      const revision = session.revision(); const generation = this._generation; const owner = this._owner
      const current = () => this.active(revision, generation, owner)
      if (wx.getStorageSync('feilv_role') === 'RIDER') {
        const me = await api.riderMe()
        if (!current()) return
        if (!me || !Number.isSafeInteger(me.riderId) || me.riderId <= 0) throw new Error('骑手档案未确认，请重新读取')
        wx.switchTab({ url: '/pages/rider/workbench' }); return
      }
      try {
        const options = await api.phoneBindingOptions()
        if (!current()) return
        if (!options || typeof options.developmentBypass !== 'boolean') throw new Error('手机号配置响应格式错误')
        this.safeSet({ developmentManualPhoneEnabled: options.developmentBypass === true })
      } catch (e) {
        if (!current()) return
        this.safeSet({ developmentManualPhoneEnabled: false, phoneOptionsError: '无法确认开发手机号能力，已禁用手填入驻；可重试配置或使用真实微信手机号授权。' })
      }
      if (current() && !this.data.developmentManualPhoneEnabled) await phone.privacy(this, current)
    })
  },
  invalidateEnrollment() {
    this._generation = (this._generation || 0) + 1; this._phoneIntent = null
    this._nativePrivacyAttempt = (this._nativePrivacyAttempt || 0) + 1
    this.setData({ enrollmentMobile: '', form: { name: '', plateNo: '' }, agreed: false, developmentManualPhoneEnabled: false })
  },
  onHide() { this.invalidateEnrollment() },
  onUnload() { this.invalidateEnrollment() },
  active(revision = session.revision(), generation = this._generation, owner = this._owner) {
    return !this._dead && this._visible && !!session.token() && revision === session.revision() && revision === this._sessionRevision
      && generation === this._generation && owner === String(wx.getStorageSync('feilv_user_id'))
  },
  input(e) { const key = e.currentTarget.dataset.field; if (!this._working && ['name', 'plateNo'].includes(key)) this.safeSet({ ['form.' + key]: e.detail.value.trim() }) },
  consent(e) { if (!this._working) this.safeSet({ agreed: e.detail.value.includes('yes') }) },
  enrollmentMobileInput(e) {
    if (this.active() && !this._working && !this.data.loading && this.data.developmentManualPhoneEnabled === true) this.safeSet({ enrollmentMobile: String(e.detail.value || '').trim() })
  },
  enrollmentDetails() {
    const name = this.data.form.name.trim(); const plateNo = this.data.form.plateNo.trim()
    if (!this.data.agreed) { toast('请先同意入驻与资料使用说明'); return null }
    if (!name || name.length > 30 || plateNo.length > 16) { toast('请填写1至30字真实姓名及最多16字车牌'); return null }
    return { name, plateNo }
  },
  preparePhone() {
    this._phoneIntent = this.active() && !this._working && !this.data.loading && !this.data.developmentManualPhoneEnabled && this.data.agreed && this.data.phoneSupported && this.data.privacyReady
      ? { revision: session.revision(), generation: this._generation, owner: this._owner } : null
  },
  register(e) {
    const intent = this._phoneIntent; this._phoneIntent = null
    if (!intent || !this.active(intent.revision, intent.generation, intent.owner) || this._working || this.data.developmentManualPhoneEnabled) return
    const details = this.enrollmentDetails()
    if (!details) return
    const phoneCode = phone.phoneCode(e)
    this._usedCodes = this._usedCodes || new Set()
    if (this._usedCodes.has(phoneCode)) return toast('请重新点击微信手机号授权按钮')
    this._usedCodes.add(phoneCode)
    return this.submitEnrollment(intent, Object.assign(details, { phoneCode }), false)
  },
  registerDevelopment() {
    if (!this.active() || this._working || this.data.loading || this.data.developmentManualPhoneEnabled !== true) return
    const details = this.enrollmentDetails()
    if (!details) return
    const enrollmentMobile = this.data.enrollmentMobile.trim()
    if (!/^1[3-9]\d{9}$/.test(enrollmentMobile)) return toast('请输入正确的11位手机号')
    const intent = { revision: session.revision(), generation: this._generation, owner: this._owner }
    return this.submitEnrollment(intent, Object.assign(details, { mobile: enrollmentMobile }), true)
  },
  submitEnrollment(intent, details, development) {
    const current = () => this.active(intent.revision, intent.generation, intent.owner) && this.data.agreed
      && this.data.developmentManualPhoneEnabled === development && !this.data.loading
      && this.data.form.name.trim() === details.name && this.data.form.plateNo.trim() === details.plateNo
      && (!development || this.data.enrollmentMobile === details.mobile)
    return this.run('busy', async api => {
      const confirmed = (await modal({ title: development ? '开发模式：确认当前账号入驻' : '确认当前账号入驻', content: development
        ? '仅供开发测试：手填手机号未经微信或短信验证，不构成真实号码归属证明。号码必须与当前账号已绑定号码一致，由服务端核对；不会自动绑定、换号或继承其他账号资产。如未绑定或号码不一致，请取消并前往资料页绑定或换绑。即时开通不代表实名认证或驾驶资质审核，接送与代驾权限默认关闭。'
        : '为当前平台账号提交骑手入驻，订单、钱包和用户ID不变，不按手机号切换账号。须使用本次真实微信手机号授权核对当前主体；此前已绑定或开发免验证不能替代。当前即时开通不代表实名认证或驾驶资质审核，接送与代驾权限默认关闭。' })).confirm
      if (!confirmed) { if (development) this.safeSet({ enrollmentMobile: '' }); return }
      if (!current()) return
      try {
        const result = await api.riderEnrollment(details)
        if (!current()) return
        if (!result || result.role !== 'RIDER' || String(result.userId) !== intent.owner) throw new Error('入驻结果身份不匹配，请重新登录核实，勿重复提交')
        session.save(result); toast('当前账号骑手入驻已确认')
        wx.switchTab({ url: '/pages/rider/workbench' })
      } catch (e) { if (current()) this.safeSet({ error: (e.message || '入驻结果未知') + '；结果未知时请重新微信登录并读取骑手档案后核实。' }); throw e }
    })
  },
  retryPhoneOptions() { if (this.active() && !this._working) return this.onShow() },
  privacy() {
    const revision = session.revision(); const generation = this._generation; const owner = this._owner
    const current = () => this.active(revision, generation, owner) && !this.data.developmentManualPhoneEnabled && !this.data.loading
    if (current()) return phone.privacy(this, current)
  },
  openPrivacy() { if (typeof wx.openPrivacyContract === 'function') wx.openPrivacyContract({}) },
  profile() { if (this.active() && !this._working) wx.navigateTo({ url: '/pages/profile/profile?mode=phone' }) },
  login() { session.login() },
  legal() { wx.navigateTo({ url: '/pages/legal/legal?kind=privacy' }) }
})
