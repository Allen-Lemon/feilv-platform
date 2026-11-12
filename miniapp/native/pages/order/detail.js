const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, toast, modal } = require('../../utils/page')
const fmt = require('../../utils/format')
const complaintTypes = [ { code: 'LOST', label: '丢件' }, { code: 'DAMAGE', label: '损坏' }, { code: 'OVERTIME', label: '超时' }, { code: 'ATTITUDE', label: '服务态度' }, { code: 'OTHER', label: '其他' } ]
definePage({
  data: { order: null, loading: false, busy: false, error: '', invalid: false, stars: [1, 2, 3, 4, 5], starIndex: 4, content: '', reviewed: false, complaintTypes, complaintIndex: 4, complaintDescription: '', cancelReason: '', ticket: '' },
  onLoad(q) { this._id = Number(q.id); this.setData({ invalid: !Number.isSafeInteger(this._id) || this._id <= 0 }) },
  onShow() { if (session.requireLogin() && !this.data.invalid) return this.load() },
  load() {
    return this.run('loading', async () => {
      try {
        const value = await api.orderDetail(this._id)
        const key = 'native_review_' + wx.getStorageSync('feilv_user_id') + '_' + this._id
        this.safeSet({ order: fmt.order(value), error: '', reviewed: !!wx.getStorageSync(key) })
      } catch (e) { this.safeSet({ error: e.message }); throw e }
    })
  },
  track() { wx.navigateTo({ url: '/pages/order/track?id=' + this._id }) },
  input(e) { const k = e.currentTarget.dataset.field; if (['content', 'complaintDescription', 'cancelReason'].includes(k)) this.setData({ [k]: e.detail.value }) },
  starsChange(e) { this.setData({ starIndex: Number(e.detail.value) }) },
  complaintChange(e) { this.setData({ complaintIndex: Number(e.detail.value) }) },
  pay() {
    return this.run('busy', async () => {
      if (!(await modal({ title: 'MOCK 模拟支付', content: '仅调用模拟支付接口，不发生真实扣款，确认继续？' })).confirm) return
      await api.payOrder(this._id)
      this.safeSet({ order: fmt.order(await api.orderDetail(this._id)) })
      toast('MOCK 模拟支付已完成')
    })
  },
  cancel() {
    return this.run('busy', async () => {
      const accepted = ['ACCEPTED', 'PICKING'].includes(this.data.order.status)
      const text = accepted ? '骑手已接单，后端规则可能收取起步价50%的取消费。退款为模拟流程，是否继续？' : '确认取消此订单？待接单退款为模拟流程，不发生真实资金退回。'
      if (!(await modal({ title: '取消订单', content: text })).confirm) return
      const value = await api.cancelOrder(this._id, this.data.cancelReason.trim() || '用户取消')
      this.safeSet({ order: fmt.order(value) }); toast('订单已取消')
    })
  },
  confirm() {
    return this.run('busy', async () => {
      if (!(await modal({ title: '确认收货', content: '请确认已收到物品，确认后订单完成。' })).confirm) return
      this.safeSet({ order: fmt.order(await api.confirmOrder(this._id)) }); toast('已确认收货')
    })
  },
  review() {
    return this.run('busy', async () => {
      await api.review({ orderId: this._id, stars: this.data.starIndex + 1, content: this.data.content.trim() })
      wx.setStorageSync('native_review_' + wx.getStorageSync('feilv_user_id') + '_' + this._id, true)
      this.safeSet({ reviewed: true }); toast('评价已提交')
    })
  },
  complaint() {
    if (!this.data.complaintDescription.trim()) return toast('请填写投诉问题描述')
    return this.run('busy', async () => {
      const result = await api.complaint({ orderId: this._id, type: complaintTypes[this.data.complaintIndex].code, description: this.data.complaintDescription.trim() })
      this.safeSet({ complaintDescription: '', ticket: '工单编号：' + result.ticketId + '；' + (result.sla || '请等待客服处理') }); toast('投诉已提交')
    })
  }
})
