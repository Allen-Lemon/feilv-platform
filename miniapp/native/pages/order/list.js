const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage } = require('../../utils/page')
const { order } = require('../../utils/format')
definePage({
  data: { list: [], loading: false, hasMore: true, error: '', loggedIn: false },
  onShow() {
    const loggedIn = !!session.token()
    this.setData({ loggedIn })
    if (!loggedIn) { this.setData({ list: [], error: '', hasMore: false }); return }
    return this.load(true)
  },
  async load(reset = false) {
    if (this._working) { if (reset) this._refreshPending = true; return }
    if (!session.token() || (!reset && !this.data.hasMore)) return
    try {
      await this.run('loading', async () => {
        const page = reset ? 0 : this._nextPage || 0
        try {
          const data = await api.myOrders(page)
          const rows = (data.content || []).map(order)
          this.safeSet({ list: reset ? rows : this.data.list.concat(rows), hasMore: !data.last && rows.length > 0, error: '' })
          this._nextPage = page + 1
        } catch (e) { this.safeSet({ error: e.message }); throw e }
      })
    } finally {
      wx.stopPullDownRefresh()
      if (this._refreshPending && !this._dead) { this._refreshPending = false; this.later(() => this.load(true), 0) }
    }
  },
  retry() { return this.load(this.data.list.length === 0) },
  refresh() { return this.load(true) },
  onPullDownRefresh() { return this.load(true) },
  onReachBottom() { return this.load(false) },
  more() { return this.load(false) },
  detail(e) { wx.navigateTo({ url: '/pages/order/detail?id=' + e.currentTarget.dataset.id }) },
  login() { session.login() },
  home() { wx.switchTab({ url: '/pages/index/index' }) }
})
