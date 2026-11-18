const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, toast } = require('../../utils/page')
const { mobile } = require('../../utils/format')
const profilePage = require('../../utils/profile-page')
const phoneAuthorization = require('../../utils/phone-authorization')
const client = require('../../config').CLIENT

// 一个页面完成身份登录和资料确认，复用资料页的原生授权与账户隔离。
definePage(Object.assign({}, profilePage, {
  data: Object.assign({}, profilePage.data, {
    agreed: false, sms: false, mobile: '', code: '', busy: false, sending: false,
    cooling: 0, smsLabel: '获取验证码', demoCode: '', profileStage: false,
    smsLoginEnabled: false, identityRequired: false, identityChoice: '', linkCode: '', identityExpiresText: '', riderClient: client === 'RIDER',
    developmentManualPhoneEnabled: false, identityMobile: '', loginOptionsLoading: false
  }),
  onLoad() { this._fromLogin = true; this._identityGeneration = 0 },
  onHide() { this.clearIdentity(); profilePage.onHide.call(this) },
  onUnload() { this.clearIdentity(); profilePage.onUnload.call(this) },
  onShow() {
    this.updateCooling()
    if (!session.token()) { this.safeSet({ profileStage: false }); return this.loadLoginOptions() }
    this.safeSet({ profileStage: true, fromLogin: true })
    if (this._working) { this._showAfterRun = true; return }
    return profilePage.onShow.call(this)
  },
  consent(e) { if (!this._working) this.safeSet({ agreed: e.detail.value.includes('yes') }) },
  toggleSms() {
    if (!this._working && this.data.smsLoginEnabled && !this.data.identityRequired) {
      this._loginOptionsAttempt = (this._loginOptionsAttempt || 0) + 1
      this.safeSet({ sms: !this.data.sms, developmentManualPhoneEnabled: false, identityMobile: '', loginOptionsLoading: false })
    }
  },
  async loadLoginOptions() {
    const revision = session.revision(); const generation = this._identityGeneration
    const attempt = this._loginOptionsAttempt = (this._loginOptionsAttempt || 0) + 1
    const choice = this.data.identityChoice; const profileStage = this.data.profileStage; const sms = this.data.sms
    const current = () => !this._dead && this._visible && !session.token() && revision === session.revision()
      && generation === this._identityGeneration && attempt === this._loginOptionsAttempt
      && choice === this.data.identityChoice && profileStage === this.data.profileStage && sms === this.data.sms
      && (!this._loginTicket || this.identityCurrent(generation))
    this.safeSet({ smsLoginEnabled: false, developmentManualPhoneEnabled: false, identityMobile: '', loginOptionsLoading: true })
    try {
      const value = await api.forSession(revision).loginOptions()
      if (current()) this.safeSet({ smsLoginEnabled: !!value && value.smsLoginEnabled === true,
        developmentManualPhoneEnabled: client === 'RIDER' && !!value && value.developmentManualPhoneEnabled === true })
    } catch { /* 能力未知默认关闭，不将演示短信或手填号码冒充真实授权。 */ }
    finally { if (current()) this.safeSet({ loginOptionsLoading: false }) }
  },
  clearIdentity() {
    this._identityGeneration = (this._identityGeneration || 0) + 1
    this._nativePrivacyAttempt = (this._nativePrivacyAttempt || 0) + 1
    this._loginOptionsAttempt = (this._loginOptionsAttempt || 0) + 1
    this._loginTicket = null; this._identityIntent = null
    this.setData({ identityRequired: false, identityChoice: '', linkCode: '', identityExpiresText: '', identityMobile: '',
      developmentManualPhoneEnabled: false, loginOptionsLoading: false })
  },
  identityCurrent(generation = this._identityGeneration) {
    if (this._loginTicket && this._loginTicket.expires <= Date.now()) { this.clearIdentity(); return false }
    return !this._dead && this._visible && !session.token() && this._sessionRevision === session.revision()
      && generation === this._identityGeneration && !!this._loginTicket
  },
  async chooseIdentity(e) {
    if (this._working || !this.identityCurrent()) return toast('请重新微信登录获取有效凭证')
    const choice = e.currentTarget.dataset.choice
    if (!['LINK', 'NEW'].includes(choice)) return
    this._identityGeneration++; this._loginOptionsAttempt = (this._loginOptionsAttempt || 0) + 1
    this._nativePrivacyAttempt = (this._nativePrivacyAttempt || 0) + 1; this._identityIntent = null
    this.safeSet({ identityChoice: choice, linkCode: '', identityMobile: '', developmentManualPhoneEnabled: false, loginOptionsLoading: false })
    if (choice === 'NEW') {
      const generation = this._identityGeneration
      await this.loadLoginOptions()
      if (this.identityCurrent(generation) && this.data.identityChoice === 'NEW' && !this.data.developmentManualPhoneEnabled) await this.identityPrivacy()
    }
  },
  linkCodeInput(e) { if (!this._working && this.identityCurrent()) this.safeSet({ linkCode: String(e.detail.value || '').trim() }) },
  async identityPrivacy() {
    const generation = this._identityGeneration
    const current = () => this.identityCurrent(generation) && this.data.identityChoice === 'NEW' && !this.data.developmentManualPhoneEnabled && !this.data.loginOptionsLoading
    if (current()) await phoneAuthorization.privacy(this, current)
  },
  openIdentityPrivacy() { if (typeof wx.openPrivacyContract === 'function') wx.openPrivacyContract({}) },
  prepareAccountPhone() {
    this._identityIntent = this.identityCurrent() && this.data.identityChoice === 'NEW' && !this.data.developmentManualPhoneEnabled && !this.data.loginOptionsLoading && this.data.agreed && this.data.phoneSupported && this.data.privacyReady && !this._working
      ? { generation: this._identityGeneration, revision: session.revision() } : null
  },
  createAccount(e) {
    const intent = this._identityIntent; this._identityIntent = null
    if (!intent || !this.identityCurrent(intent.generation) || this._working || !this.checkConsent()) return
    const phoneCode = phoneAuthorization.phoneCode(e)
    if (this._usedIdentityPhoneCodes && this._usedIdentityPhoneCodes.has(phoneCode)) return toast('请重新授权获取新手机号凭证')
    this._usedIdentityPhoneCodes = this._usedIdentityPhoneCodes || new Set(); this._usedIdentityPhoneCodes.add(phoneCode)
    return this.run('busy', async scopedApi => {
      if (!(await require('../../utils/page').modal({ title: '确认创建新账号', content: '此操作创建独立平台账号，不继承原账号订单、钱包或骑手身份。若已有账号，请取消并使用临时关联码。手机号必须由微信真实验证，开发或演示号码不能证明归属。' })).confirm || !this.identityCurrent(intent.generation)) return
      const value = await scopedApi.createAccount(this._loginTicket.value, phoneCode)
      if (this.identityCurrent(intent.generation)) await this.finish(value)
    })
  },
  identityMobileInput(e) {
    if (!this._working && this.developmentIdentityCurrent()) this.safeSet({ identityMobile: String(e.detail.value || '').trim() })
  },
  developmentIdentityCurrent(generation = this._identityGeneration) {
    return this.identityCurrent(generation) && client === 'RIDER' && this.data.developmentManualPhoneEnabled === true
      && !this.data.loginOptionsLoading && this.data.identityRequired && this.data.identityChoice === 'NEW' && !this.data.profileStage
  },
  createDevelopmentAccount() {
    if (this._working || !this.developmentIdentityCurrent() || !this.checkConsent()) return
    const identityMobile = this.data.identityMobile.trim()
    if (!/^1[3-9]\d{9}$/.test(identityMobile)) return toast('请输入正确的11位手机号')
    const generation = this._identityGeneration; const ticket = this._loginTicket.value; const revision = session.revision()
    const current = () => this.developmentIdentityCurrent(generation) && this.data.agreed && revision === session.revision()
      && this._loginTicket.value === ticket && this.data.identityMobile === identityMobile
    return this.run('busy', async scopedApi => {
      const confirmed = (await require('../../utils/page').modal({ title: '开发模式：确认创建独立账号', content: '仅供开发测试：手填手机号未经微信或短信验证，不构成真实号码归属证明。此操作创建独立账号，不继承原账号订单、钱包或骑手档案。已有账号请取消并使用临时关联码。' })).confirm
      if (!confirmed) { this.safeSet({ identityMobile: '' }); return }
      if (!current()) return
      const value = await scopedApi.createDevelopmentAccount(ticket, identityMobile)
      if (current()) await this.finish(value)
    })
  },
  linkAccount() {
    if (this._working || !this.identityCurrent() || !this.checkConsent() || this.data.identityChoice !== 'LINK') return
    const linkCode = this.data.linkCode.trim()
    if (!/^[A-Za-z0-9_-]{43}$/.test(linkCode)) return toast('请填写另一小程序当前账号生成的完整临时关联码')
    const generation = this._identityGeneration
    return this.run('busy', async scopedApi => {
      if (!(await require('../../utils/page').modal({ title: '确认关联已有账号', content: '将当前微信身份关联到临时码所代表的平台账号，共用其订单、钱包与骑手档案。请确认临时码来自您本人在另一小程序登录的账号；不是您本人请取消。' })).confirm || !this.identityCurrent(generation)) return
      const value = await scopedApi.linkIdentity(this._loginTicket.value, linkCode)
      if (this.identityCurrent(generation)) await this.finish(value)
    })
  },
  restartIdentity() { if (!this._working) this.clearIdentity() },
  input(e) {
    const field = e.currentTarget.dataset.field
    if (!this._working && ['mobile', 'code'].includes(field)) this.safeSet({ [field]: e.detail.value.trim() })
  },
  nicknameInput: profilePage.input,
  failure(error, revision, userId) {
    // 在登录页内发生401时不会再导航到本页，必须主动恢复登录入口，不能停留在失效的资料表单。
    if (!this._dead && this._visible && !session.token()) {
      this._sessionRevision = session.revision()
      this._runRevision = session.revision()
      this._profileRevision = undefined
      this.setData(JSON.parse(JSON.stringify(this._initialData)))
      if (!error || !error.silent) toast(error && error.message || '登录已过期，请重新登录')
      return
    }
    return profilePage.failure.call(this, error, revision, userId)
  },
  legal(e) { wx.navigateTo({ url: '/pages/legal/legal?kind=' + e.currentTarget.dataset.kind }) },
  checkConsent() { if (!this.data.agreed) { toast('请先阅读并同意服务与隐私说明'); return false } return true },
  updateCooling() {
    const cooling = Math.max(0, Math.ceil(((this._smsUntil || 0) - Date.now()) / 1000))
    this.safeSet({ cooling, smsLabel: cooling ? cooling + '秒后重试' : '获取验证码' })
    if (cooling && this._visible) this.later(this.updateCooling, 1000)
  },
  sendCode() {
    if (!this.data.smsLoginEnabled || !this.checkConsent() || this.data.cooling || this.data.profileStage || this.data.identityRequired) return
    if (!mobile(this.data.mobile)) return toast('请输入正确的11位手机号')
    return this.run('sending', async (scopedApi) => {
      const result = await scopedApi.smsCode(this.data.mobile)
      this._smsUntil = Date.now() + 60000
      this.safeSet({ demoCode: typeof result === 'string' && /^\d{4,8}$/.test(result) ? result : '' })
      this.updateCooling()
      toast('验证码已发送；如显示演示码可手动填写')
    })
  },
  async finish(value) {
    if (this._dead || !this._visible) return
    this.clearIdentity()
    session.save(value)
    // 仅可信登录成功可切换本页作用域；后续请求必须绑定新会话，不能复用登录请求的旧scope。
    this._sessionRevision = session.revision()
    this._runRevision = session.revision()
    this.safeSet({ profileStage: true, fromLogin: true, sms: false, mobile: '', code: '', demoCode: '', loading: true })
    this.initializeProfile()
    try { await this.loadProfile(api.forSession(session.revision())) }
    finally { if (this.current()) this.safeSet({ loading: false }) }
  },
  wechat() {
    if (this.data.profileStage || !this.checkConsent()) return
    return this.run('busy', async (scopedApi) => {
      const revision = session.revision()
      this.clearIdentity()
      const attempt = this._identityGeneration
      const current = () => !this._dead && this._visible && revision === session.revision() && attempt === this._identityGeneration
      const code = await phoneAuthorization.wxCode()
      if (!current()) return
      const value = await scopedApi.wechatLogin(code)
      if (!current()) return
      if (value && value.state === 'AUTHENTICATED') return this.finish(value.login)
      const expires = value && Date.parse(value.expiresAt)
      if (!value || value.state !== 'IDENTITY_REQUIRED' || typeof value.loginTicket !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(value.loginTicket) || !Number.isFinite(expires) || expires <= Date.now()) throw new Error('微信登录响应无效，请重新登录')
      this._loginTicket = { value: value.loginTicket, expires }
      this.safeSet({ identityRequired: true, identityChoice: '', identityExpiresText: value.expiresAt, sms: false })
      const ticket = this._loginTicket
      this.later(() => { if (ticket === this._loginTicket) { this.clearIdentity(); toast('登录凭证已过期，请重新微信登录') } }, Math.min(expires - Date.now(), 2147483647))
    })
  },
  smsLogin() {
    if (!this.data.smsLoginEnabled || this.data.profileStage || this.data.identityRequired || !this.checkConsent()) return
    if (!mobile(this.data.mobile) || !/^\d{4,8}$/.test(this.data.code)) return toast('请填写正确的手机号与验证码')
    return this.run('busy', async (scopedApi) => {
      const revision = session.revision()
      const value = await scopedApi.login(this.data.mobile, this.data.code)
      if (revision !== session.revision()) return
      await this.finish(value)
    })
  },
  complete() {
    if (!this.current() || this._working) return
    if (!this.data.loaded) return toast('请先成功读取账号资料')
    const nickname = session.validNickname(this.data.nickname)
    if (!session.nicknameReady(nickname)) return toast('请填写1至32个字符的昵称，不使用微信默认昵称')
    if (!this.data.mobileBound) return toast('请先用微信授权或短信验证绑定手机号，也可以暂时返回浏览')
    return this.run('saving', async (scopedApi) => {
      const revision = this._profileRevision; const userId = this._profileUserId
      try {
        // 已完成的步骤保留，任一步失败留在本页重试，不重新登录或重复上传成功的头像。
        if (this.data.avatarDraft) {
          const result = await scopedApi.uploadAvatar(this.data.avatarDraft)
          if (!this.current(revision, userId)) return
          if (!session.avatarPath(result && result.avatarUrl)) throw new Error('头像响应缺少持久化路径，请重新读取资料')
          if (!this.applyProfile(result, revision, userId, true)) return
          this.safeSet({ avatarDraft: '' })
        }
        if (nickname !== this.data.savedNickname) {
          const result = await scopedApi.updateProfile(nickname)
          if (!this.applyProfile(result, revision, userId, false)) return
        }
        // 以最终服务端资料判定完成，不以按钮点击或本机缓存冒充绑定成功。
        const value = await scopedApi.me()
        if (!this.applyProfile(value, revision, userId, false)) return
        if (!value.mobileBound || !session.nicknameReady(value.nickname)) throw new Error('资料尚未完善，请确认昵称和手机号后重试')
        toast('资料已保存，欢迎加入飞毛腿')
        this.leave()
      } catch (e) { this.failure(e, revision, userId) }
    })
  },
  browse() {
    if (this._working) return
    this._leaving = true
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 })
    else wx.switchTab({ url: client === 'RIDER' ? '/pages/rider/workbench' : '/pages/index/index' })
  }
}))
