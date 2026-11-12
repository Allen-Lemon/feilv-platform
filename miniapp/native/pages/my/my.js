const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, modal, toast } = require('../../utils/page')
definePage({
  data: { me: null, loggedIn: false, isRider: false, loading: false, error: '', nickname: '', roleText: '' },
  onShow() {
    const loggedIn = !!session.token()
    this.setData({ loggedIn, isRider: wx.getStorageSync('feilv_role') === 'RIDER', nickname: wx.getStorageSync('feilv_nickname') || '用户', roleText: wx.getStorageSync('feilv_role') === 'RIDER' ? '骑手账户' : '普通用户' })
    if (!loggedIn) { this.setData({ me: null, error: '' }); return }
    return this.load()
  },
  load() {
    return this.run('loading', async () => {
      try {
        const me = await api.me()
        // 微信账号允许无手机号，不将手机号缺失视作未登录。
        this.safeSet({ me: { userId: me.userId, mobile: me.mobile || '尚未绑定手机号（微信登录无需手机号）' }, nickname: me.nickname || this.data.nickname, error: '' })
      } catch (e) { this.safeSet({ error: e.message }); throw e }
    })
  },
  login() { session.login() },
  address() { if (session.requireLogin()) wx.navigateTo({ url: '/pages/address/address' }) },
  rider() { wx.navigateTo({ url: '/pages/rider/register' }) },
  workbench() { if (session.requireLogin()) wx.navigateTo({ url: '/pages/rider/workbench' }) },
  settings() { wx.navigateTo({ url: '/pages/settings/settings' }) },
  privacy() { wx.navigateTo({ url: '/pages/legal/legal?kind=privacy' }) },
  async logout() {
    if (!(await modal({ title: '退出登录', content: '将清除本机登录凭证与未完成订单草稿，确认退出？' })).confirm) return
    session.clear(); this.setData({ me: null, loggedIn: false, isRider: false, error: '' }); toast('已退出登录')
  }
})
