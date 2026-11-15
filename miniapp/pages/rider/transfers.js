const api = require('../../utils/api')
const session = require('../../utils/session')
const fmt = require('../../utils/format')
const { definePage, modal, toast } = require('../../utils/page')
const statuses = { PENDING: '待对方接收', ACCEPTED: '已接受交接', REJECTED: '已拒绝', CANCELED: '已撤销', EXPIRED: '已过期' }
function positive(value) { return Number.isSafeInteger(value) && value > 0 }
function initial() {
  return { allowed: false, riderId: null, online: false, identityError: '', orderId: 0, targetRiderId: '', reason: '', busy: false, submitError: '', requestUncertain: false,
    tab: 'INCOMING', incoming: [], incomingLoaded: false, incomingLoading: false, incomingError: '', incomingMore: true,
    outgoing: [], outgoingLoaded: false, outgoingLoading: false, outgoingError: '', outgoingMore: true,
    history: [], historyLoaded: false, historyLoading: false, historyError: '', orderPending: false }
}
function context(page) { return { generation: page._generation, revision: session.revision(), owner: String(wx.getStorageSync('feilv_user_id') || '') } }
function active(page, ctx) {
  return ctx && page._visible && !page._dead && page._generation === ctx.generation && page._sessionRevision === ctx.revision
    && session.revision() === ctx.revision && !!session.token() && wx.getStorageSync('feilv_role') === 'RIDER'
    && String(wx.getStorageSync('feilv_user_id')) === ctx.owner
}
function transferView(value, riderId, direction) {
  if (!value || !positive(value.id) || !positive(value.orderId) || !positive(value.fromRiderId) || !positive(value.toRiderId) || !statuses[value.status]) throw new Error('转派记录响应无效，请刷新核实')
  if (direction && Number(direction === 'INCOMING' ? value.toRiderId : value.fromRiderId) !== riderId) throw new Error('转派记录归属不符，请刷新核实')
  // 只映射必要公开记录；不透传手机号或订单私有详情，也不提供原发送人详情入口。
  return { id: value.id, orderId: value.orderId, orderNo: value.orderNo || String(value.orderId), title: value.title || '',
    modeText: fmt.taskModes[value.taskMode] || fmt.taskModes.DELIVERY, fromName: value.fromRiderName || '原骑手', toName: value.toRiderName || '目标骑手',
    status: value.status, statusText: statuses[value.status], reason: value.reason || '', responseReason: value.responseReason || '',
    createdText: fmt.time(value.createdTime), handledText: value.handledTime ? fmt.time(value.handledTime) : '',
    canRespond: direction === 'INCOMING' && value.status === 'PENDING' && value.toRiderId === riderId,
    canCancel: direction === 'OUTGOING' && value.status === 'PENDING' && value.fromRiderId === riderId }
}
definePage({
  data: initial(),
  onLoad(q) { const id = Number(q.orderId); this._orderId = positive(id) ? id : 0 },
  async onShow() {
    this._generation = (this._generation || 0) + 1
    const revision = session.revision()
    if (this._ownerRevision !== undefined && this._ownerRevision !== revision) this._orderId = 0
    this._ownerRevision = revision; this._lists = {}
    this.setData(Object.assign(initial(), { orderId: this._orderId || 0 }))
    if (!session.requireLogin() || wx.getStorageSync('feilv_role') !== 'RIDER') return
    const ctx = context(this)
    this.setData({ allowed: true })
    try {
      const info = await api.forSession(ctx.revision).riderMe()
      if (!active(this, ctx)) return
      if (!info || !positive(info.riderId)) throw new Error('未读取到当前骑手ID，请重试')
      this.setData({ riderId: info.riderId, online: info.onlineStatus === 1 || info.onlineStatus === 2 })
      await this.reload(ctx)
    } catch (e) { if (active(this, ctx)) this.setData({ identityError: e.message || '骑手资料未加载' }) }
  },
  onHide() { this._generation = (this._generation || 0) + 1; this.setData({ targetRiderId: '', reason: '' }) },
  onUnload() { this._generation = (this._generation || 0) + 1 },
  async reload(ctx = context(this)) {
    if (!active(this, ctx) || !this.data.riderId) return
    await Promise.all([this.loadList('INCOMING', true, ctx), this.loadList('OUTGOING', true, ctx), this.loadHistory(ctx)])
  },
  async refresh() {
    try {
      if (this.data.busy) return
      return await this.onShow()
    } finally { wx.stopPullDownRefresh() }
  },
  onPullDownRefresh() { return this.refresh() },
  onReachBottom() { return this.more() },
  async loadList(direction, reset, ctx = context(this)) {
    if (!active(this, ctx) || !this.data.riderId) return
    const key = direction === 'INCOMING' ? 'incoming' : 'outgoing'
    if (reset) this._lists[direction] = { next: 0, loading: false }
    const state = this._lists[direction]
    if (!state || state.loading || !reset && !this.data[key + 'More']) return
    const page = state.next; state.loading = true
    const current = () => active(this, ctx) && this._lists[direction] === state
    this.setData({ [key + 'Loading']: true, [key + 'Error']: '', ...(reset ? { [key]: [], [key + 'Loaded']: false } : {}) })
    try {
      const value = await api.forSession(ctx.revision).riderTransfers(page, direction)
      if (!current()) return
      if (!value || !Array.isArray(value.content) || value.content.length > 15 || value.number !== page || typeof value.last !== 'boolean') throw new Error('转派分页响应无效，请重试')
      const rows = value.content.map(row => transferView(row, this.data.riderId, direction))
      const merged = (reset ? [] : this.data[key]).concat(rows); const seen = new Set()
      this.setData({ [key]: merged.filter(row => { if (seen.has(row.id)) return false; seen.add(row.id); return true }), [key + 'Loaded']: true,
        [key + 'More']: !value.last && rows.length > 0, [key + 'Error']: '' })
      state.next = page + 1
    } catch (e) { if (current()) this.setData({ [key + 'Error']: e.message || '转派列表未加载，请重试' }) }
    finally { if (current()) { state.loading = false; this.setData({ [key + 'Loading']: false }) } }
  },
  async loadHistory(ctx = context(this)) {
    if (!active(this, ctx) || !this.data.orderId) return
    const request = {}; this._historyRequest = request
    const current = () => active(this, ctx) && this._historyRequest === request
    this.setData({ historyLoading: true, historyError: '', historyLoaded: false })
    try {
      const rows = await api.forSession(ctx.revision).orderTransfers(this.data.orderId)
      if (!current()) return
      if (!Array.isArray(rows) || rows.some(row => row.orderId !== this.data.orderId)) throw new Error('订单转派历史响应无效')
      const history = rows.map(row => transferView(row, this.data.riderId))
      this.setData({ history, historyLoaded: true, historyError: '', requestUncertain: false, orderPending: history.some(row => row.status === 'PENDING') })
    } catch (e) { if (current()) this.setData({ historyError: e.message || '转派历史未加载，请先刷新核实' }) }
    finally { if (current()) this.setData({ historyLoading: false }) }
  },
  tabChange(e) { const tab = e.currentTarget.dataset.tab; if (!this.data.busy && ['INCOMING', 'OUTGOING'].includes(tab)) this.setData({ tab }) },
  more() { if (!this.data.busy) return this.loadList(this.data.tab, false) },
  retryList() { if (!this.data.busy) return this.loadList(this.data.tab, false) },
  input(e) {
    if (!active(this, context(this)) || this.data.busy || this.data.requestUncertain) return
    const field = e.currentTarget.dataset.field
    if (['targetRiderId', 'reason'].includes(field)) this.setData({ [field]: e.detail.value, submitError: '' })
  },
  async submit() {
    const ctx = context(this)
    if (!active(this, ctx) || this.data.busy || !this.data.riderId || !this.data.orderId || !this.data.historyLoaded || this.data.historyLoading || this.data.requestUncertain || this.data.orderPending) return
    const text = String(this.data.targetRiderId).trim(); const targetRiderId = Number(text); const reason = this.data.reason.trim()
    if (!/^[1-9]\d*$/.test(text) || !positive(targetRiderId)) return toast('请输入目标骑手的正整数骑手ID，不是用户ID')
    if (targetRiderId === this.data.riderId) return toast('不能转派给自己')
    if (!reason || reason.length > 255) return toast('转派原因需为1至255字')
    this.setData({ busy: true, submitError: '' })
    try {
      if (!(await modal({ title: '确认申请转派', content: '转派给骑手ID ' + targetRiderId + '。仅限已接单但未取件/未开始服务。对方接受前仍由您负责；拒绝或撤销仍由您负责，开始履约会使待转派过期。交接后最终骑手取得原收入并负责投诉，您不再有履约或订单私有详情权限。是否发送？', confirmText: '发送申请' })).confirm || !active(this, ctx)) return
      this.setData({ requestUncertain: true })
      const result = await api.forSession(ctx.revision).requestOrderTransfer(this.data.orderId, { targetRiderId, reason })
      if (!active(this, ctx)) return
      transferView(result, this.data.riderId, 'OUTGOING')
      if (result.orderId !== this.data.orderId || result.toRiderId !== targetRiderId || result.reason !== reason) throw new Error('转派响应不匹配，请刷新核实')
      this.setData({ targetRiderId: '', reason: '', requestUncertain: false, tab: 'OUTGOING' })
      toast('转派申请已确认，请以服务端记录为准')
      await this.reload(ctx)
    } catch (e) {
      if (!active(this, ctx)) return
      this.setData({ submitError: (e.message || '申请未确认') + '；请先核实下方服务端记录，勿重复发送。' })
      await this.reload(ctx)
    } finally { if (active(this, ctx)) this.setData({ busy: false }) }
  },
  async handle(e) {
    const ctx = context(this)
    if (!active(this, ctx) || this.data.busy || !this.data.riderId) return
    const { id, action } = e.currentTarget.dataset
    const key = action === 'CANCELED' ? 'outgoing' : 'incoming'
    const row = this.data[key].find(item => item.id === Number(id))
    if (!row || !(action === 'CANCELED' ? row.canCancel : ['ACCEPTED', 'REJECTED'].includes(action) && row.canRespond)) return
    if (action === 'ACCEPTED' && !this.data.online) return toast('请先到工作台上线，再刷新并接受转派')
    const title = { ACCEPTED: '接受并交接', REJECTED: '拒绝转派', CANCELED: '撤销转派' }[action]
    this.setData({ busy: true, submitError: '' })
    try {
      const content = action === 'ACCEPTED' ? '确认接受订单 ' + row.orderNo + '？成功后由您履约、取得该单原收入并负责投诉；原骑手不再有履约或私有详情权限。以服务端当前状态为准。' : '确认' + title + '订单 ' + row.orderNo + '？拒绝或撤销后仍由原骑手负责。'
      if (!(await modal({ title, content, confirmText: '确认' })).confirm || !active(this, ctx)) return
      const scoped = api.forSession(ctx.revision)
      const result = action === 'CANCELED' ? await scoped.cancelOrderTransfer(row.id, { reason: '' }) : await scoped.respondOrderTransfer(row.id, { decision: action, reason: '' })
      if (!active(this, ctx)) return
      transferView(result, this.data.riderId, action === 'CANCELED' ? 'OUTGOING' : 'INCOMING')
      if (result.id !== row.id || result.orderId !== row.orderId || result.status !== action) throw new Error('处理结果未确认，请刷新核实')
      toast(title + '已确认，请刷新工作台查看')
      await this.reload(ctx)
    } catch (e) {
      if (!active(this, ctx)) return
      this.setData({ submitError: (e.message || '处理未确认') + '；已重新查询服务端，请核实状态后操作。' })
      await this.reload(ctx)
    } finally { if (active(this, ctx)) this.setData({ busy: false }) }
  },
  workbench() {
    if (!active(this, context(this)) || this.data.busy) return
    const pages = getCurrentPages()
    if (pages.length > 1 && pages[pages.length - 2].route === 'pages/rider/workbench') wx.navigateBack({ delta: 1 })
    else wx.navigateTo({ url: '/pages/rider/workbench' })
  },
  login() { session.login() }
})
