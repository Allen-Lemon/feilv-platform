const session = require('../../utils/session')
const { definePage, toast } = require('../../utils/page')
function nickname(value) {
  if (typeof value !== 'string' || /[\u0000-\u001f\u007f-\u009f]/.test(value)) return ''
  const trimmed = value.trim()
  return trimmed && Array.from(trimmed).length <= 32 ? trimmed : ''
}
function codePhoneSupported() {
  if (typeof wx.canIUse !== 'function' || !wx.canIUse('button.open-type.getPhoneNumber')) return false
  try {
    const info = typeof wx.getAppBaseInfo === 'function' ? wx.getAppBaseInfo() : wx.getSystemInfoSync()
    const version = String(info.SDKVersion || '').split('.').map(Number)
    return version[0] > 2 || (version[0] === 2 && (version[1] > 21 || (version[1] === 21 && version[2] >= 2)))
  } catch { return false }
}
definePage({
  data: {
    loaded: false, loading: false, saving: false, binding: false, checkingPrivacy: false, openingPrivacy: false,
    nickname: '', savedNickname: '', mobile: '', mobileBound: false, error: '', fromLogin: false,
    phoneSupported: false, privacyReady: false, privacyNeeded: false, privacyGateSupported: false,
    privacyTitle: '微信隐私保护指引'
  },
  onLoad(q) { this.safeSet({ fromLogin: q.from === 'login' }); this._fromLogin = q.from === 'login' },
  onShow() {
    if (!session.requireLogin()) return
    const revision = session.revision()
    if (this._profileRevision !== revision) {
      this._profileRevision = revision
      this._profileUserId = wx.getStorageSync('feilv_user_id')
      this._usedPhoneCodes = new Set()
      this._phoneIntent = null
      this.safeSet({ fromLogin: this._fromLogin })
    }
    return this.load()
  },
  onHide() { this._privacyAttempt = (this._privacyAttempt || 0) + 1; this._phoneIntent = null },
  onUnload() { this._privacyAttempt = (this._privacyAttempt || 0) + 1; this._phoneIntent = null },
  current(revision = this._profileRevision, userId = this._profileUserId) {
    return !this._dead && this._visible && !this._leaving && !!session.token()
      && revision === session.revision() && revision === this._sessionRevision
      && String(userId) === String(wx.getStorageSync('feilv_user_id'))
  },
  applyProfile(value, revision, userId, preserveDraft) {
    if (!this.current(revision, userId)) return false
    if (!value || String(value.userId) !== String(userId) || !['USER', 'RIDER'].includes(value.role)) throw new Error('账户资料响应无效，请重试')
    const savedNickname = typeof value.nickname === 'string' ? value.nickname : ''
    if (!session.updateNickname(savedNickname, userId, revision)) return false
    // 只展示服务端脱敏值；不缓存手机号，也不让手机号响应覆盖尚未保存的昵称。
    const masked = typeof value.mobile === 'string' && /^\d{3}\*{4}\d{4}$/.test(value.mobile) ? value.mobile : ''
    this.safeSet({ loaded: true, savedNickname, nickname: preserveDraft ? this.data.nickname : savedNickname,
      mobileBound: value.mobileBound === true, mobile: masked, error: '' })
    return true
  },
  failure(error, revision, userId) {
    if (!this.current(revision, userId) || (error && error.silent)) return
    const message = error && error.message || '操作失败，请重试'
    this.safeSet({ error: message }); toast(message)
  },
  load() {
    if (!this.current()) return
    return this.run('loading', async (api) => {
      const revision = this._profileRevision; const userId = this._profileUserId
      try {
        const wasLoaded = this.data.loaded
        const preserveDraft = wasLoaded && this.data.nickname !== this.data.savedNickname
        const value = await api.me()
        if (!this.applyProfile(value, revision, userId, preserveDraft)) return
        if (!wasLoaded && this._fromLogin && nickname(value.nickname) && value.mobileBound === true) return this.leave()
        await this.readPrivacy(revision, userId)
      } catch (e) { this.failure(e, revision, userId) }
    })
  },
  input(e) { if (this.current() && !this._working) this.safeSet({ nickname: e.detail.value }) },
  save() {
    if (!this.current() || !this.data.loaded) return
    const value = nickname(this.data.nickname)
    if (!value) return toast('昵称需为1至32个字符，不能包含控制字符')
    return this.run('saving', async (api) => {
      const revision = this._profileRevision; const userId = this._profileUserId
      try {
        const result = await api.updateProfile(value)
        if (this.applyProfile(result, revision, userId, false)) toast('昵称已保存')
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
    if (this.current() && !this._working && this.data.loaded && this.data.phoneSupported && this.data.privacyReady && !this.data.mobileBound) {
      this._phoneIntent = { revision: this._profileRevision, userId: this._profileUserId }
    }
  },
  bindPhone(e) {
    const intent = this._phoneIntent
    this._phoneIntent = null
    if (!intent || !this.current(intent.revision, intent.userId) || this._working
        || !this.data.loaded || !this.data.phoneSupported || !this.data.privacyReady || this.data.mobileBound) return
    const detail = e.detail || {}
    if (detail.errMsg !== 'getPhoneNumber:ok' || typeof detail.code !== 'string' || !detail.code.trim()) {
      return toast('未获得手机号授权，您仍可继续使用')
    }
    const code = detail.code.trim()
    if (this._usedPhoneCodes.has(code)) return toast('请重新点击按钮授权手机号')
    this._usedPhoneCodes.add(code)
    return this.run('binding', async (api) => {
      try {
        // 仅使用本次 getPhoneNumber 用户操作的 code，绝不使用 wx.login 的 code 或客户端手机号。
        const value = await api.bindWechatPhone(code)
        if (this.applyProfile(value, intent.revision, intent.userId, true)) toast('手机号已绑定')
      } catch (e) { this.failure(e, intent.revision, intent.userId) }
    })
  },
  complete() {
    if (!this.current() || this._working) return
    if (this.data.nickname !== this.data.savedNickname) return toast('请先保存昵称，或选择暂时跳过')
    this.leave()
  },
  leave() {
    if (!this.current()) return
    this._leaving = true
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 })
    else wx.switchTab({ url: '/pages/my/my' })
  }
})
