const session = require('../../utils/session')
const { avatarURL } = require('../../utils/request')
const { definePage, modal, toast } = require('../../utils/page')
definePage({
  data: { me: null, loggedIn: false, isRider: false, loading: false, error: '', nickname: '', avatarUrl: '', roleText: '' },
  onShow() {
    const loggedIn = !!session.token()
    this.setData({ loggedIn, avatarUrl: loggedIn ? avatarURL(wx.getStorageSync('feilv_avatar_url')) : '', isRider: wx.getStorageSync('feilv_role') === 'RIDER', nickname: wx.getStorageSync('feilv_nickname') || '用户', roleText: wx.getStorageSync('feilv_role') === 'RIDER' ? '骑手账户' : '普通用户' })
    if (!loggedIn) { this.setData({ me: null, error: '' }); return }
    return this.load()
  },
  load() {
    return this.run('loading', async (api) => {
      const revision = session.revision(); const userId = wx.getStorageSync('feilv_user_id')
      try {
        const me = await api.me()
        if (this._dead || revision !== session.revision() || String(me.userId) !== String(userId)) return
        if (!session.updateProfile(me, userId, revision)) throw new Error('账户资料响应无效，请重试')
        // 未绑定可浏览；下单使用实时 me 门禁。号码只展示脱敏值，不写入本机。
        const masked = typeof me.mobile === 'string' && /^\d{3}\*{4}\d{4}$/.test(me.mobile) ? me.mobile : ''
        this.safeSet({ me: { userId: me.userId, mobileBound: me.mobileBound === true, mobile: me.mobileBound ? (masked || '手机号已绑定') : '尚未绑定手机号（下单必填）' }, nickname: me.nickname || this.data.nickname, avatarUrl: avatarURL(me.avatarUrl), error: '' })
      } catch (e) { this.safeSet({ error: e.message }); throw e }
    })
  },
  login() { session.login() },
  orders() { wx.switchTab({ url: '/pages/order/list' }) },
  profile() { if (session.requireLogin()) wx.navigateTo({ url: '/pages/profile/profile?from=my' }) },
  phone() { if (session.requireLogin()) wx.navigateTo({ url: '/pages/profile/profile?from=my&mode=phone' }) },
  address() { if (session.requireLogin()) wx.navigateTo({ url: '/pages/address/address' }) },
  wallet() { if (session.requireLogin()) wx.navigateTo({ url: '/pages/wallet/wallet' }) },
  rider() { wx.navigateTo({ url: '/pages/rider/register' }) },
  workbench() { if (session.requireLogin()) wx.navigateTo({ url: '/pages/rider/workbench' }) },
  settings() { wx.navigateTo({ url: '/pages/settings/settings' }) },
  privacy() { wx.navigateTo({ url: '/pages/legal/legal?kind=privacy' }) },
  async logout() {
    if (!(await modal({ title: '退出登录', content: '将清除本机登录凭证与未完成订单草稿，确认退出？' })).confirm) return
    session.clear(); this.setData({ me: null, loggedIn: false, isRider: false, nickname: '', avatarUrl: '', error: '' }); toast('已退出登录')
  }
})
