const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, modal, toast } = require('../../utils/page')
const { types } = require('../../utils/format')
definePage({
  data: { draft: null, quote: null, typeText: '', busy: false, orderId: null, invalid: false, uncertain: false },
  onLoad() { this.restore() },
  onShow() { session.requireLogin() },
  restore() {
    const draft = wx.getStorageSync('native_draft')
    const owner = wx.getStorageSync('feilv_user_id')
    const pending = wx.getStorageSync('native_pending_order')
    if (!draft || draft.owner !== owner || !draft.payload || !draft.quote) { this.setData({ invalid: true }); return }
    const matching = pending && pending.owner === owner && pending.savedAt === draft.savedAt
    this.setData({ draft: draft.payload, quote: draft.quote, typeText: types[draft.payload.orderType], orderId: matching ? pending.id || null : null, uncertain: !!(matching && pending.uncertain) })
    this._savedAt = draft.savedAt
    this._owner = owner
  },
  submit() {
    if (!session.requireLogin() || this.data.invalid || this.data.uncertain) return
    return this.run('busy', async () => {
      if (wx.getStorageSync('feilv_user_id') !== this._owner) throw new Error('账户已变化，请重新填写订单')
      const answer = await modal({ title: 'MOCK 模拟支付', content: '不拉起微信支付，不发生真实扣款。确认创建订单并调用后端模拟支付？' })
      if (!answer.confirm || this._dead) return
      let id = this.data.orderId
      if (!id) {
        wx.setStorageSync('native_pending_order', { owner: this._owner, savedAt: this._savedAt, uncertain: true })
        this.safeSet({ uncertain: true })
        let order
        try { order = await api.createOrder(this.data.draft) }
        catch (e) {
          // 业务明确拒绝可重新提交；超时/5xx/无有效响应均不能判定未创建。
          if (typeof e.code === 'number' && e.status !== 500 && e.status !== 502 && e.status !== 503 && e.status !== 504) {
            wx.removeStorageSync('native_pending_order'); this.safeSet({ uncertain: false })
          }
          throw e
        }
        if (!order || !order.id) throw new Error('创建结果缺少订单ID，请到订单列表核实，勿重复提交')
        id = order.id
        // 创建成功立即保存；支付失败重试同一订单，禁止再次创建。
        wx.setStorageSync('native_pending_order', { id, owner: this._owner, savedAt: this._savedAt })
        this.safeSet({ orderId: id, uncertain: false })
      }
      await api.payOrder(id)
      wx.removeStorageSync('native_draft')
      wx.removeStorageSync('native_pending_order')
      toast('模拟支付成功，等待骑手接单')
      if (!this._dead) wx.redirectTo({ url: '/pages/order/detail?id=' + id })
    })
  },
  detail() { if (this.data.orderId) wx.redirectTo({ url: '/pages/order/detail?id=' + this.data.orderId }) },
  orders() { wx.switchTab({ url: '/pages/order/list' }) },
  back() { wx.navigateBack() }
})
