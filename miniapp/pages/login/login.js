const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, toast } = require('../../utils/page')
const { mobile } = require('../../utils/format')
const profilePage = require('../../utils/profile-page')

// 一个页面完成身份登录和资料确认，复用资料页的原生授权与账户隔离。
definePage(Object.assign({}, profilePage, {
  data: Object.assign({}, profilePage.data, {
    agreed: false, sms: false, mobile: '', code: '', busy: false, sending: false,
    cooling: 0, smsLabel: '获取验证码', demoCode: '', profileStage: false
  }),
  onLoad() { this._fromLogin = true },
  onShow() {
    this.updateCooling()
    if (!session.token()) return
    this.safeSet({ profileStage: true, fromLogin: true })
    if (this._working) { this._showAfterRun = true; return }
    return profilePage.onShow.call(this)
  },
  consent(e) { if (!this._working) this.safeSet({ agreed: e.detail.value.includes('yes') }) },
  toggleSms() { if (!this._working) this.safeSet({ sms: !this.data.sms }) },
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
    if (!this.checkConsent() || this.data.cooling || this.data.profileStage) return
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
      const result = await new Promise((resolve, reject) => wx.login({ timeout: 15000, success: resolve, fail: () => reject(new Error('微信登录失败，请检查 AppID 或稍后重试')) }))
      if (this._dead || !this._visible || revision !== session.revision()) return
      if (!result.code) throw new Error('微信未返回登录 code，请使用真实 AppID 联调')
      const value = await scopedApi.wechatLogin(result.code)
      if (this._dead || !this._visible || revision !== session.revision()) return
      if (!value || !['USER', 'RIDER'].includes(value.role)) throw new Error('微信登录返回的账户类型无效')
      await this.finish(value)
    })
  },
  smsLogin() {
    if (this.data.profileStage || !this.checkConsent()) return
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
    else wx.switchTab({ url: '/pages/index/index' })
  }
}))
