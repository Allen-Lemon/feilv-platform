const session = require('../../utils/session')
const { definePage, toast, modal } = require('../../utils/page')
const phone = require('../../utils/phone-authorization')
definePage({
  data: { form: { name: '', plateNo: '' }, agreed: false, busy: false, loading: false, loggedIn: false, error: '',
    phoneSupported: false, privacyReady: false, privacyNeeded: false, privacyGateSupported: false, privacyTitle: '微信隐私保护指引' },
  onShow() {
    this._generation = (this._generation || 0) + 1; this._phoneIntent = null
    this.setData({ loggedIn: !!session.token(), error: '' })
    if (!session.token()) return
    return this.run('loading', async api => {
      const revision = session.revision(); const generation = this._generation
      if (wx.getStorageSync('feilv_role') === 'RIDER') {
        const me = await api.riderMe()
        if (!this.active(revision, generation)) return
        if (!me || !Number.isSafeInteger(me.riderId) || me.riderId <= 0) throw new Error('骑手档案未确认，请重新读取')
        wx.switchTab({ url: '/pages/rider/workbench' }); return
      }
      await phone.privacy(this, () => this.active(revision, generation))
    })
  },
  onHide() { this._generation++; this._phoneIntent = null; this._nativePrivacyAttempt = (this._nativePrivacyAttempt || 0) + 1 },
  onUnload() { this._generation++; this._phoneIntent = null; this._nativePrivacyAttempt = (this._nativePrivacyAttempt || 0) + 1 },
  active(revision = session.revision(), generation = this._generation) { return !this._dead && this._visible && !!session.token() && revision === session.revision() && revision === this._sessionRevision && generation === this._generation },
  input(e) { const key = e.currentTarget.dataset.field; if (!this._working && ['name', 'plateNo'].includes(key)) this.safeSet({ ['form.' + key]: e.detail.value.trim() }) },
  consent(e) { if (!this._working) this.safeSet({ agreed: e.detail.value.includes('yes') }) },
  preparePhone() {
    this._phoneIntent = this.active() && !this._working && this.data.agreed && this.data.phoneSupported && this.data.privacyReady
      ? { revision: session.revision(), generation: this._generation, owner: String(wx.getStorageSync('feilv_user_id')) } : null
  },
  register(e) {
    const intent = this._phoneIntent; this._phoneIntent = null
    if (!intent || !this.active(intent.revision, intent.generation) || this._working) return
    if (!this.data.agreed) return toast('请先同意入驻与资料使用说明')
    const name = this.data.form.name.trim(); const plateNo = this.data.form.plateNo.trim()
    if (!name || name.length > 30 || plateNo.length > 16) return toast('请填写1至30字真实姓名及最多16字车牌')
    const phoneCode = phone.phoneCode(e)
    this._usedCodes = this._usedCodes || new Set()
    if (this._usedCodes.has(phoneCode)) return toast('请重新点击微信手机号授权按钮')
    this._usedCodes.add(phoneCode)
    return this.run('busy', async api => {
      if (!(await modal({ title: '确认当前账号入驻', content: '为当前平台账号提交骑手入驻，订单、钱包和用户ID不变，不按手机号切换账号。须使用本次真实微信手机号授权核对当前主体；此前已绑定或开发免验证不能替代。当前即时开通不代表实名认证或驾驶资质审核，接送与代驾权限默认关闭。' })).confirm || !this.active(intent.revision, intent.generation)) return
      try {
        const result = await api.riderEnrollment({ name, plateNo, phoneCode })
        if (!this.active(intent.revision, intent.generation)) return
        if (!result || result.role !== 'RIDER' || String(result.userId) !== intent.owner) throw new Error('入驻结果身份不匹配，请重新登录核实，勿重复提交')
        session.save(result); toast('当前账号骑手入驻已确认')
        wx.switchTab({ url: '/pages/rider/workbench' })
      } catch (e) { if (this.active(intent.revision, intent.generation)) this.safeSet({ error: (e.message || '入驻结果未知') + '；结果未知时请重新微信登录并读取骑手档案后核实。' }); throw e }
    })
  },
  privacy() { if (this.active()) return phone.privacy(this, () => this.active()) },
  openPrivacy() { if (typeof wx.openPrivacyContract === 'function') wx.openPrivacyContract({}) },
  login() { session.login() },
  legal() { wx.navigateTo({ url: '/pages/legal/legal?kind=privacy' }) }
})
