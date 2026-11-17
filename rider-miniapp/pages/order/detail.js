const session = require('../../utils/session')
const { definePage } = require('../../utils/page')
const fmt = require('../../utils/format')
function readonlyOrder(value, id) {
  if (!value || Number(value.id) !== id || typeof value.orderType !== 'string' || typeof value.status !== 'string') throw new Error('承接订单详情响应无效，请刷新核实')
  const view = fmt.order(Object.assign({}, value, { owner: false }))
  const amount = value.realFeeFen == null ? value.estimateFeeFen : value.realFeeFen
  // 只映射履约展示字段，不向模板传入发布者动作、内部身份或默认虚构金额。
  return { id: value.id, orderNo: value.orderNo, orderType: value.orderType, status: value.status, statusText: view.statusText, typeText: view.typeText,
    taskMode: value.taskMode, isService: view.isService, isTransport: view.isTransport, modeText: view.modeText,
    fromAddress: value.fromAddress || '', toAddress: value.toAddress || '', fromContact: value.fromContact || '', fromPhone: value.fromPhone || '',
    toContact: value.toContact || '', toPhone: value.toPhone || '', locationAddress: value.locationAddress || '', serviceTime: value.serviceTime || '',
    goodsDesc: value.goodsDesc || '', goodsBudgetText: view.goodsBudgetText, pickupCode: value.pickupCode || '',
    amountText: Number.isSafeInteger(amount) && amount >= 0 ? fmt.money(amount) : '待核实', createdText: fmt.time(value.createdTime) }
}
definePage({
  data: { order: null, task: null, invalid: false, allowed: false, loading: false, taskLoading: false, error: '', taskError: '' },
  onLoad(q) { this._id = Number(q.id); this.setData({ invalid: !Number.isSafeInteger(this._id) || this._id <= 0 }) },
  onShow() {
    this._generation = (this._generation || 0) + 1
    const allowed = !!session.token() && wx.getStorageSync('feilv_role') === 'RIDER'
    this.setData({ allowed, order: null, task: null })
    if (!session.requireLogin() || !allowed || this.data.invalid) return
    return this.load()
  },
  onHide() { this._generation = (this._generation || 0) + 1; this.setData({ order: null, task: null }) },
  onUnload() { this._generation = (this._generation || 0) + 1 },
  active(revision, generation) { return !this._dead && this._visible && !!session.token() && wx.getStorageSync('feilv_role') === 'RIDER' && revision === session.revision() && generation === this._generation },
  load() {
    if (this.data.invalid || !this.data.allowed || !session.token()) return
    return this.run('loading', async api => {
      const revision = session.revision(); const generation = this._generation
      this.safeSet({ order: null, task: null, error: '', taskError: '' })
      try {
        // 后端仅允许当前承运骑手；转派后原骑手的读取必须失败。
        const value = await api.orderDetail(this._id)
        if (!this.active(revision, generation)) return
        this.safeSet({ order: readonlyOrder(value, this._id) })
        if (value.orderType === 'CUSTOM') await this.fetchTask(api, revision, generation)
      } catch (e) { if (this.active(revision, generation)) this.safeSet({ order: null, task: null, error: e.message || '无权读取或订单未确认' }); throw e }
    })
  },
  async fetchTask(api, revision, generation) {
    try {
      const value = await api.customTask(this._id)
      if (!this.active(revision, generation)) return
      if (value !== null && (!value || typeof value.title !== 'string' || typeof value.content !== 'string' || !Array.isArray(value.tags))) throw new Error('任务资料无效，请刷新核实')
      this.safeSet({ task: value ? fmt.task(value) : null, taskError: '' })
    } catch (e) { if (this.active(revision, generation)) this.safeSet({ task: null, taskError: e.message || '任务资料读取失败' }) }
  },
  loadTask() {
    if (!this.data.order || this.data.order.orderType !== 'CUSTOM' || !this.data.allowed) return
    return this.run('taskLoading', api => this.fetchTask(api, session.revision(), this._generation))
  },
  workbench() { wx.switchTab({ url: '/pages/rider/workbench' }) }
})
