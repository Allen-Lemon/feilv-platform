const session = require('./session')
const { toast, modal } = require('./page')
const { avatarURL, baseURL } = require('./request')
const nickname = session.validNickname
function codePhoneSupported() {
  if (typeof wx.canIUse !== 'function' || !wx.canIUse('button.open-type.getPhoneNumber')) return false
  try {
    const info = typeof wx.getAppBaseInfo === 'function' ? wx.getAppBaseInfo() : wx.getSystemInfoSync()
    const version = String(info.SDKVersion || '').split('.').map(Number)
    return version[0] > 2 || (version[0] === 2 && (version[1] > 21 || (version[1] === 21 && version[2] >= 2)))
  } catch { return false }
}
module.exports = {
  data: {
    loaded: false, loading: false, saving: false, binding: false, checkingPrivacy: false, openingPrivacy: false,
    nickname: '', savedNickname: '', avatarUrl: '', avatarDraft: '', uploading: false, avatarSupported: false,
    mobile: '', mobileBound: false, changingPhone: false, error: '', fromLogin: false, phoneOnly: false, phoneError: '',
    manualPhone: false, manualMobile: '', manualCode: '', phoneSending: false, phoneCooling: 0,
    phoneSmsLabel: '获取验证码', phoneDemoCode: '', phoneSmsMock: false,
    phoneDevelopment: false, phoneOptionsLoading: false, phoneOptionsError: '', phoneModeMessage: '', phoneBackend: '',
    phoneSupported: false, privacyReady: false, privacyNeeded: false, privacyGateSupported: false,
    privacyTitle: '微信隐私保护指引'
  },
  onLoad(q) { this.safeSet({ fromLogin: q.from === 'login', phoneOnly: q.mode === 'phone' }); this._fromLogin = q.from === 'login' },
  onShow() {
    if (!session.requireLogin()) return
    this.initializeProfile()
    this.updatePhoneCooling()
    if (this._working) { this._showAfterRun = true; return }
    return this.load()
  },
  initializeProfile() {
    const revision = session.revision()
    if (this._profileRevision !== revision) {
      this._profileRevision = revision
      this._profileUserId = wx.getStorageSync('feilv_user_id')
      this._usedPhoneCodes = new Set()
      this._phoneIntent = null
      this._avatarIntent = null
      this._phoneSmsUntil = 0
      this._phoneSmsMobile = ''
      this.safeSet({ fromLogin: this._fromLogin, avatarSupported: typeof wx.canIUse === 'function' && wx.canIUse('button.open-type.chooseAvatar') })
    }
  },
  onHide() { this.invalidatePhone(); this._privacyAttempt = (this._privacyAttempt || 0) + 1; this._avatarIntent = null },
  onUnload() { this.invalidatePhone(); this._privacyAttempt = (this._privacyAttempt || 0) + 1; this._avatarIntent = null },
  invalidatePhone() {
    this._phoneGeneration = (this._phoneGeneration || 0) + 1
    this._phoneIntent = null
    this.clearManualPhone()
    this.safeSet({ changingPhone: false, manualPhone: false, phoneDevelopment: false, phoneError: '', phoneOptionsError: '', phoneModeMessage: '' })
  },
  phoneContext() { return { revision: this._profileRevision, userId: this._profileUserId, generation: this._phoneGeneration || 0, changing: this.data.changingPhone } },
  phoneCurrent(ctx) { return !!ctx && this.current(ctx.revision, ctx.userId) && ctx.generation === (this._phoneGeneration || 0) && ctx.changing === this.data.changingPhone },
  phoneFailureFor(error, ctx) {
    // 真实401已由传输层清会话；登录页仍须退出资料阶段，但hide/切号的旧失败不能重置新页。
    const expired = error && (error.status === 401 || error.code === 401) && !session.token() && !this._dead && this._visible
      && ctx.generation === (this._phoneGeneration || 0) && this._profileRevision === ctx.revision && this._sessionRevision === ctx.revision
    if (this.phoneCurrent(ctx) || expired) this.phoneFailure(error, ctx.revision, ctx.userId)
  },
  startPhoneChange() {
    if (!this.current() || this._working || !this.data.loaded || !this.data.mobileBound) return
    this.invalidatePhone()
    this.safeSet({ changingPhone: true })
  },
  cancelPhoneChange() {
    if (this.current() && !this._working && this.data.changingPhone) this.invalidatePhone()
  },
  togglePhoneMethod() {
    if (!this.current() || this._working || this.data.mobileBound && !this.data.changingPhone) return
    this._phoneIntent = null
    const manualPhone = !this.data.manualPhone
    this.safeSet({ manualPhone, phoneError: '', phoneDevelopment: false, phoneOptionsError: '', phoneModeMessage: '' })
    if (manualPhone) return this.loadPhoneOptions()
  },
  loadPhoneOptions() {
    if (!this.current() || this._working || !this.data.manualPhone) return
    this.safeSet({ phoneDevelopment: false, phoneOptionsError: '', phoneModeMessage: '', phoneBackend: baseURL() })
    return this.run('phoneOptionsLoading', async (api) => {
      const ctx = this.phoneContext()
      try {
        const options = await api.phoneBindingOptions()
        if (!this.phoneCurrent(ctx)) return
        if (!options || typeof options.developmentBypass !== 'boolean') throw new Error('绑定配置响应格式错误')
        this.safeSet({ phoneDevelopment: options.developmentBypass,
          phoneModeMessage: options.developmentBypass ? '' : typeof options.message === 'string' && options.message ? options.message : '当前后端未开启开发免验证码模式，请检查开关、开发profile并重启后端后刷新。' })
      } catch (e) {
        if (this.phoneCurrent(ctx)) this.safeSet({ phoneDevelopment: false,
          phoneOptionsError: e && (e.status === 404 || e.code === 404) ? '当前服务没有绑定配置接口，请更新后端；修改本地配置不会影响小程序正在连接的远程服务。' : '无法读取手机号绑定配置，请检查网络和当前服务地址后重试。' })
        this.phoneFailureFor(e, ctx)
      }
    })
  },
  manualPhoneInput(e) {
    if (!this.current() || this._working || this.data.mobileBound && !this.data.changingPhone) return
    const field = e.currentTarget.dataset.field
    if (!['manualMobile', 'manualCode'].includes(field)) return
    const value = String(e.detail.value || '').trim()
    const patch = { [field]: value }
    if (field === 'manualMobile' && value !== this.data.manualMobile) Object.assign(patch, { manualCode: '', phoneDemoCode: '', phoneSmsMock: false })
    this.safeSet(patch)
  },
  updatePhoneCooling() {
    if (!this.current()) return
    const cooling = Math.max(0, Math.ceil(((this._phoneSmsUntil || 0) - Date.now()) / 1000))
    this.safeSet({ phoneCooling: cooling, phoneSmsLabel: cooling ? cooling + '秒后重试' : '获取验证码' })
    if (cooling) this.later(this.updatePhoneCooling, 1000)
  },
  sendPhoneSms() {
    if (!this.current() || this._working || !this.data.loaded || this.data.mobileBound && !this.data.changingPhone || !this.data.manualPhone || this.data.phoneCooling || this.data.phoneDevelopment) return
    const mobile = this.data.manualMobile.trim()
    if (!/^1[3-9]\d{9}$/.test(mobile)) return toast('请输入正确的11位手机号')
    return this.run('phoneSending', async (api) => {
      const ctx = this.phoneContext(); const { revision, userId } = ctx
      try {
        const value = ctx.changing ? await api.changePhoneSmsCode(mobile) : await api.phoneSmsCode(mobile)
        if (!this.phoneCurrent(ctx)) return
        if (!value || typeof value.mock !== 'boolean' || !Number.isInteger(value.retryAfterSeconds) || value.retryAfterSeconds < 1 || value.retryAfterSeconds > 3600
            || value.mock && !/^\d{6}$/.test(value.demoCode || '')) throw new Error('验证码响应异常，请重新获取')
        this._phoneSmsUntil = Date.now() + value.retryAfterSeconds * 1000
        this._phoneSmsMobile = mobile
        this.safeSet({ phoneSmsMock: value.mock, phoneDemoCode: value.mock ? value.demoCode : '', manualCode: '', phoneError: '' })
        this.updatePhoneCooling()
        toast(value.mock ? '演示验证码已生成，未发送真实短信' : '验证码已发送，请查看短信')
      } catch (e) { this.phoneFailureFor(e, ctx) }
    })
  },
  bindManualPhone() {
    if (!this.current() || this._working || !this.data.loaded || this.data.mobileBound && !this.data.changingPhone || !this.data.manualPhone) return
    const mobile = this.data.manualMobile.trim(); const code = this.data.manualCode.trim()
    if (!/^1[3-9]\d{9}$/.test(mobile)) return toast('请输入正确的11位手机号')
    if (!this.data.phoneDevelopment && !/^\d{6}$/.test(code)) return toast('请填写6位短信验证码')
    if (!this.data.phoneDevelopment && mobile !== this._phoneSmsMobile) return toast('请先为当前手机号获取绑定验证码')
    return this.run('binding', async (api) => {
      const ctx = this.phoneContext(); const { revision, userId } = ctx
      try {
        const development = this.data.phoneDevelopment; const mock = this.data.phoneSmsMock
        if (ctx.changing && !(await modal({ title: '确认更换手机号', content: '将使用新号码替换当前绑定号码；用户ID、钱包、订单和角色不变，不合并其他账号。' + (development ? '开发模式未验证号码归属。' : mock ? '演示验证码未发送真实短信，不代表真实验证。' : '请确认新号码由您本人使用。') })).confirm) return
        if (!this.phoneCurrent(ctx)) return
        const value = ctx.changing
          ? development ? await api.changeDevelopmentPhone(mobile) : await api.changePhone(mobile, code)
          : development ? await api.bindDevelopmentPhone(mobile) : await api.bindPhone(mobile, code)
        if (!this.phoneCurrent(ctx)) return
        if (!value || value.mobileBound !== true || ctx.changing && (value.role !== wx.getStorageSync('feilv_role') || !/^\d{3}\*{4}\d{4}$/.test(value.mobile || ''))) throw new Error('服务端尚未确认手机号操作成功，请重新读取资料后重试')
        if (this.applyProfile(value, revision, userId, true)) {
          this.invalidatePhone()
          toast(development ? '开发测试手机号已' + (ctx.changing ? '更换' : '绑定') + '（未验证归属）'
            : mock ? '演示手机号已' + (ctx.changing ? '更换' : '绑定') + '（未真实验证）' : '手机号已' + (ctx.changing ? '更换' : '验证并绑定'))
        }
      } catch (e) { this.phoneFailureFor(e, ctx) }
    })
  },
  clearManualPhone() {
    this._phoneSmsUntil = 0; this._phoneSmsMobile = ''
    this.safeSet({ manualMobile: '', manualCode: '', phoneDemoCode: '', phoneSmsMock: false, phoneCooling: 0, phoneSmsLabel: '获取验证码' })
  },
  phoneFailure(error, revision, userId) {
    if (this.current(revision, userId) && !(error && error.silent)) this.safeSet({ phoneError: error && error.message || '绑定未完成，请重试' })
    this.failure(error, revision, userId)
  },
  current(revision = this._profileRevision, userId = this._profileUserId) {
    return !this._dead && this._visible && !this._leaving && !!session.token()
      && revision === session.revision() && revision === this._sessionRevision
      && String(userId) === String(wx.getStorageSync('feilv_user_id'))
  },
  applyProfile(value, revision, userId, preserveDraft) {
    if (!this.current(revision, userId)) return false
    if (!value || String(value.userId) !== String(userId) || !['USER', 'RIDER'].includes(value.role)) throw new Error('账户资料响应无效，请重试')
    const savedNickname = typeof value.nickname === 'string' ? value.nickname : ''
    if (!session.updateProfile(value, userId, revision)) return false
    // 只展示服务端脱敏值；不缓存手机号，也不让手机号响应覆盖尚未保存的昵称。
    const masked = typeof value.mobile === 'string' && /^\d{3}\*{4}\d{4}$/.test(value.mobile) ? value.mobile : ''
    this.safeSet({ loaded: true, savedNickname, nickname: preserveDraft ? this.data.nickname : savedNickname,
      avatarUrl: avatarURL(value.avatarUrl), mobileBound: value.mobileBound === true, mobile: masked, error: '',
      phoneError: value.mobileBound === true ? '' : this.data.phoneError })
    if (value.mobileBound === true && !this.data.changingPhone) this.clearManualPhone()
    return true
  },
  failure(error, revision, userId) {
    if (!this.current(revision, userId) || (error && error.silent)) return
    const message = error && error.message || '操作失败，请重试'
    this.safeSet({ error: message }); toast(message)
  },
  load() {
    if (!this.current()) return
    return this.run('loading', api => this.loadProfile(api))
  },
  async loadProfile(api) {
    const revision = this._profileRevision; const userId = this._profileUserId; const generation = this._phoneGeneration || 0
    try {
      const wasLoaded = this.data.loaded
      const preserveDraft = wasLoaded && this.data.nickname !== this.data.savedNickname
      const value = await api.me()
      if (generation !== (this._phoneGeneration || 0)) return
      if (!this.applyProfile(value, revision, userId, preserveDraft)) return
      if (!wasLoaded && this._fromLogin && session.nicknameReady(value.nickname) && value.mobileBound === true) return this.leave()
      await this.readPrivacy(revision, userId)
      if (this.data.manualPhone && this.current(revision, userId)) this.later(this.loadPhoneOptions, 0)
    } catch (e) { if (generation === (this._phoneGeneration || 0)) this.failure(e, revision, userId) }
  },
  input(e) { if (this.current() && !this._working) this.safeSet({ nickname: e.detail.value }) },
  save() {
    if (!this.current() || !this.data.loaded) return
    const value = nickname(this.data.nickname)
    if (!value) return toast('昵称需为1至32个字符，不能包含控制字符')
    if (!session.nicknameReady(value)) return toast('请主动填写昵称，不要使用微信默认昵称')
    return this.run('saving', async (api) => {
      const revision = this._profileRevision; const userId = this._profileUserId
      try {
        const result = await api.updateProfile(value)
        if (this.applyProfile(result, revision, userId, false)) toast('昵称已保存')
      } catch (e) { this.failure(e, revision, userId) }
    })
  },
  prepareAvatar() {
    this._avatarIntent = this.current() && !this._working && this.data.loaded && this.data.avatarSupported
      ? { revision: this._profileRevision, userId: this._profileUserId } : null
  },
  chooseAvatar(e) {
    const intent = this._avatarIntent; this._avatarIntent = null
    // 部分基础库/真机不会先触发按钮 tap；chooseavatar 已证明用户在当前页操作，
    // 没有 intent 时按当前页面、会话和 owner 复核，隐藏页/切号后的迟到事件仍然拒绝。
    const revision = intent ? intent.revision : this._profileRevision
    const userId = intent ? intent.userId : this._profileUserId
    if (this._working || !this.current(revision, userId)) return
    const path = e.detail && e.detail.avatarUrl
    if (typeof path !== 'string' || !path) return toast('未选择头像，可保留默认头像')
    // 临时文件只在当前页面内预览；上传成功后仅保存服务端持久化路径。
    this.safeSet({ avatarDraft: path, error: '' })
  },
  discardAvatar() { if (this.current() && !this._working) this.safeSet({ avatarDraft: '' }) },
  saveAvatar() {
    if (!this.current() || !this.data.loaded || !this.data.avatarDraft) return
    const path = this.data.avatarDraft
    return this.run('uploading', async (api) => {
      const revision = this._profileRevision; const userId = this._profileUserId
      try {
        const result = await api.uploadAvatar(path)
        if (!this.current(revision, userId)) return
        if (!session.avatarPath(result && result.avatarUrl)) throw new Error('头像响应缺少持久化路径，请重新读取资料')
        if (this.applyProfile(result, revision, userId, true)) { this.safeSet({ avatarDraft: '' }); toast('头像已保存') }
      } catch (e) { this.failure(e, revision, userId) }
    })
  },
  readPrivacy(revision, userId) {
    if (!this.current(revision, userId)) return
    const attempt = this._privacyAttempt = (this._privacyAttempt || 0) + 1
    const supported = codePhoneSupported()
    this.safeSet({ phoneSupported: supported, privacyReady: false, privacyNeeded: false })
    if (!supported) return
    // 较旧的基础库没有隐私查询能力，由平台原生授权流程处理；仍必须支持新版 code 换号。
    if (typeof wx.getPrivacySetting !== 'function') { this.safeSet({ privacyReady: true }); return }
    return new Promise(resolve => {
      wx.getPrivacySetting({
        success: result => {
          if (attempt === this._privacyAttempt && this.current(revision, userId)) {
            const needed = result.needAuthorization === true
            this.safeSet({ privacyNeeded: needed, privacyReady: !needed,
              privacyGateSupported: wx.canIUse('button.open-type.agreePrivacyAuthorization'),
              privacyTitle: result.privacyContractName || '微信隐私保护指引', error: '' })
          }
          resolve()
        },
        fail: () => {
          if (attempt === this._privacyAttempt) this.failure(new Error('无法读取微信隐私授权状态，请重试或跳过'), revision, userId)
          resolve()
        }
      })
    })
  },
  agreePrivacy() {
    if (!this.current() || !this.data.privacyNeeded || !this.data.privacyGateSupported) return
    return this.run('checkingPrivacy', () => this.readPrivacy(this._profileRevision, this._profileUserId))
  },
  openPrivacy() {
    if (!this.current()) return
    if (typeof wx.openPrivacyContract !== 'function') return toast('当前微信版本不支持查看平台隐私指引，请升级微信')
    return this.run('openingPrivacy', () => {
      const revision = this._profileRevision; const userId = this._profileUserId
      return new Promise(resolve => wx.openPrivacyContract({
        success: () => resolve(),
        fail: () => { this.failure(new Error('隐私指引暂时无法打开，请稍后重试'), revision, userId); resolve() }
      }))
    })
  },
  preparePhone() {
    this._phoneIntent = null
    if (this.current() && !this._working && this.data.loaded && this.data.phoneSupported && this.data.privacyReady && (!this.data.mobileBound || this.data.changingPhone) && !this.data.manualPhone) {
      this._phoneIntent = this.phoneContext()
    }
  },
  bindPhone(e) {
    const intent = this._phoneIntent
    this._phoneIntent = null
    if (!this.phoneCurrent(intent) || this._working
        || !this.data.loaded || !this.data.phoneSupported || !this.data.privacyReady || this.data.mobileBound && !this.data.changingPhone || this.data.manualPhone) return
    const detail = e.detail || {}
    if (detail.errMsg !== 'getPhoneNumber:ok' || typeof detail.code !== 'string' || !detail.code.trim()) {
      const denied = /deny|cancel/i.test(detail.errMsg || '')
      const message = denied ? '未授权手机号，仍可返回浏览；下单前须绑定' : '微信未返回有效手机号凭证，请更新微信并重新点击授权；请确认小程序已开通手机号能力'
      this.safeSet({ phoneError: message })
      return toast(message)
    }
    const code = detail.code.trim()
    if (this._usedPhoneCodes.has(code)) return toast('请重新点击按钮授权手机号')
    this._usedPhoneCodes.add(code)
    this.safeSet({ phoneError: '' })
    return this.run('binding', async (api) => {
      try {
        // 仅使用本次 getPhoneNumber 用户操作的 code，绝不使用 wx.login 的 code 或客户端手机号。
        if (intent.changing && !(await modal({ title: '确认更换微信手机号', content: '确认使用本次微信授权的新号码替换当前手机号？用户ID、钱包、订单和角色保持不变，不合并已占用号码。' })).confirm) return
        if (!this.phoneCurrent(intent)) return
        const value = intent.changing ? await api.changeWechatPhone(code) : await api.bindWechatPhone(code)
        if (!this.phoneCurrent(intent)) return
        if (!value || value.mobileBound !== true || intent.changing && (value.role !== wx.getStorageSync('feilv_role') || !/^\d{3}\*{4}\d{4}$/.test(value.mobile || ''))) throw new Error('服务端尚未确认手机号操作成功，请重新读取资料后重试')
        if (this.applyProfile(value, intent.revision, intent.userId, true)) { this.invalidatePhone(); toast(intent.changing ? '手机号已更换' : '手机号已绑定') }
      } catch (e) { this.phoneFailureFor(e, intent) }
    })
  },
  complete() {
    if (!this.current() || this._working) return
    if (!this.data.loaded) return toast('请先成功读取账号资料')
    if (this.data.phoneOnly) {
      if (!this.data.mobileBound) return toast('请先使用微信授权或短信验证码绑定手机号')
      return this.leave()
    }
    if (!session.nicknameReady(this.data.savedNickname)) return toast('请填写并主动保存非空昵称，不使用微信默认昵称')
    if (this.data.nickname !== this.data.savedNickname) return toast('请先保存昵称修改')
    if (!this.data.mobileBound) return toast('下单前必须绑定手机号；可返回继续浏览')
    if (this.data.avatarDraft) return toast('已选择的头像尚未保存，请保存或返回保留原头像')
    this.leave()
  },
  leave() {
    if (!this.current()) return
    this._leaving = true
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 })
    else wx.switchTab({ url: require('../config').CLIENT === 'RIDER' ? '/pages/rider/workbench' : '/pages/my/my' })
  }
}
