const session = require('../../utils/session')
const draftState = require('../../utils/draft')
const { definePage, toast } = require('../../utils/page')
const { types } = require('../../utils/format')
const paidStates = ['WAIT_GRAB', 'ACCEPTED', 'PICKING', 'DELIVERING', 'DELIVERED', 'FINISHED']
definePage({
  data: { draft: null, quote: null, typeText: '', tagNames: [], busy: false, orderId: null, invalid: false, uncertain: false },
  onLoad() { this.restore() },
  onShow() { session.requireLogin() },
  restore() {
    const draft = wx.getStorageSync('native_draft')
    const owner = wx.getStorageSync('feilv_user_id')
    const pending = wx.getStorageSync('native_pending_order')
    if (!draft || draft.owner !== owner || !draft.payload || !draft.quote) { this.setData({ invalid: true }); return }
    const matching = draftState.matches(pending, owner, draft.savedAt)
    this.setData({ draft: draft.payload, quote: draft.quote, tagNames: draft.tagNames || [], typeText: types[draft.payload.orderType], orderId: matching ? pending.id || null : null, uncertain: !!(matching && pending.uncertain) })
    this._savedAt = draft.savedAt
    this._owner = owner
    this._revision = session.revision()
  },
  activeDraft() {
    return this._revision === session.revision() && wx.getStorageSync('feilv_user_id') === this._owner && draftState.current(this._owner, this._savedAt)
  },
  finish(id, message) {
    if (!this.activeDraft()) return
    draftState.complete(this._owner, this._savedAt, id)
    if (!this._dead && this._visible) {
      toast(message)
      wx.redirectTo({ url: '/pages/order/detail?id=' + id })
    }
  },
  submit() {
    if (!session.requireLogin() || this.data.invalid || this.data.uncertain) return
    return this.run('busy', async (api) => {
      if (!this.activeDraft()) throw new Error('账户或订单草稿已变化，请重新打开确认页')
      if (!await session.requireOrderProfile(api, () => !this._dead && this._visible && this.activeDraft())) return
      // 此页已展示路线、报价及MOCK说明；明确点击发布即确认，无需再弹重复确认框。
      if (this._dead || !this._visible || !this.activeDraft()) return
      let id = this.data.orderId
      const retrying = !!id
      if (!id) {
        wx.setStorageSync('native_pending_order', { owner: this._owner, savedAt: this._savedAt, uncertain: true })
        this.safeSet({ uncertain: true })
        let order
        try { order = await api.createOrder(this.data.draft) }
        catch (e) {
          // 业务明确拒绝才可清除匹配标记，旧任务不能删除另一草稿的待核实状态。
          if (this.activeDraft() && typeof e.code === 'number' && !(e.code >= 500 && e.code < 600) && !(e.status >= 500 && e.status < 600)) {
            draftState.clearPending(this._owner, this._savedAt)
            this.safeSet({ uncertain: false })
          }
          if (e.code === 4091 && this.activeDraft() && !this._dead && this._visible) { session.openRequiredProfile(); return }
          throw e
        }
        if (!order || !Number.isSafeInteger(order.id) || order.id <= 0) throw new Error('创建结果缺少订单ID，请到订单列表核实，勿重复提交')
        id = order.id
        if (!this.activeDraft()) return
        // 创建成功即保存；即使离开页面，重开时仍复用原订单。
        wx.setStorageSync('native_pending_order', { id, owner: this._owner, savedAt: this._savedAt })
        this.safeSet({ orderId: id, uncertain: false })
        if (this._dead) return
      }
      if (retrying) {
        const order = await api.orderDetail(id)
        if (!this.activeDraft() || this._dead) return
        if (paidStates.includes(order.status)) { this.finish(id, '订单已支付，正在查看最新进度'); return }
        if (order.status !== 'WAIT_PAY') throw new Error('该订单已取消或当前状态不可支付，请查看订单详情')
      }
      await api.payOrder(id)
      this.finish(id, '模拟支付成功，等待骑手接单')
    })
  },
  detail() { if (this.data.orderId) wx.redirectTo({ url: '/pages/order/detail?id=' + this.data.orderId }) },
  orders() { wx.switchTab({ url: '/pages/order/list' }) },
  back() { wx.navigateBack() }
})
