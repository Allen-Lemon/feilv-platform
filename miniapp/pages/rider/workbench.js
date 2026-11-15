const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, toast, modal } = require('../../utils/page')
const fmt = require('../../utils/format')
function mine(value) {
  // 此骑手专用端点返回 status(中文) + statusCode；与用户 OrderVO 不同。
  const isService = fmt.service(value)
  return Object.assign({}, value, { isService, canTransfer: value.statusCode === 'ACCEPTED', modeText: fmt.taskModes[value.taskMode] || fmt.taskModes.DELIVERY,
    statusText: isService ? ({ PICKING: '服务中', DELIVERED: '待用户确认完成' }[value.statusCode] || value.status) : value.status,
    rewardText: fmt.money(value.rewardFen), canPickup: !isService && value.statusCode === 'ACCEPTED', canDepart: !isService && value.statusCode === 'PICKING',
    canDeliver: !isService && value.statusCode === 'DELIVERING', canServiceStart: isService && value.statusCode === 'ACCEPTED', canServiceComplete: isService && value.statusCode === 'PICKING' })
}
definePage({
  data: { allowed: false, info: null, online: false, modeText: '已下线', halls: [], orders: [], hasMore: false, pickupCodes: {}, busy: false, loading: false, error: '', logs: [], logsVisible: false, locationInfo: '', locationDenied: false, lastGrabCode: '' },
  onShow() {
    const allowed = session.token() && wx.getStorageSync('feilv_role') === 'RIDER'
    this.setData({ allowed: !!allowed })
    this._generation = (this._generation || 0) + 1
    if (!allowed) return
    if (this._working) { this._showAfterRun = true; return }
    return this.load()
  },
  onHide() { this._generation = (this._generation || 0) + 1 },
  onUnload() { this._generation = (this._generation || 0) + 1 },
  workbenchCurrent(revision, generation) { return !this._dead && this._visible && revision === session.revision() && revision === this._sessionRevision && generation === this._generation && wx.getStorageSync('feilv_role') === 'RIDER' },
  load() {
    if (!this.workbenchCurrent(session.revision(), this._generation)) return
    return this.run('loading', async (api) => {
      const revision = session.revision(); const generation = this._generation
      try {
        const info = await api.riderMe()
        if (!this.workbenchCurrent(revision, generation)) return
        const halls = info.onlineStatus > 0 ? await api.grabList() : []
        if (!this.workbenchCurrent(revision, generation)) return
        const data = await api.riderOrders(0)
        if (!this.workbenchCurrent(revision, generation)) return
        const rows = data.content || []
        this._nextPage = 1
        this.safeSet({ info, online: info.onlineStatus > 0, modeText: ({ 0: '已下线', 1: '抢单中', 2: '听单中' })[info.onlineStatus] || '未知状态', halls: halls.map(g => Object.assign({}, g, { isService: fmt.service(g), modeText: fmt.taskModes[g.taskMode] || fmt.taskModes.DELIVERY, typeText: fmt.types[g.orderType] || g.orderType, distanceText: (Number(g.distanceM) / 1000).toFixed(1), rewardText: fmt.money(g.rewardFen) })), orders: rows.map(mine), hasMore: !data.last && rows.length > 0, error: '' })
      } catch (e) { if (this.workbenchCurrent(revision, generation)) { this.safeSet({ error: e.message }); throw e } }
    })
  },
  more() {
    if (!this.data.hasMore || !this.workbenchCurrent(session.revision(), this._generation)) return
    return this.run('loading', async (api) => {
      const revision = session.revision(); const generation = this._generation
      const data = await api.riderOrders(this._nextPage)
      if (!this.workbenchCurrent(revision, generation)) return
      const rows = data.content || []
      this.safeSet({ orders: this.data.orders.concat(rows.map(mine)), hasMore: !data.last && rows.length > 0 })
      this._nextPage++
    })
  },
  online() {
    return this.run('busy', async (api) => {
      await api.riderOnline(this.data.online ? 0 : 1)
      toast(this.data.online ? '已下线' : '已开启抢单')
    }).then(() => this.load())
  },
  grab(e) {
    const id = e.currentTarget.dataset.id
    return this.run('busy', async (api) => {
      const revision = session.revision()
      const result = await api.grab(id)
      if (revision !== session.revision() || this._dead || !this._visible) return
      const selected = this.data.halls.find(g => String(g.orderId) === String(id))
      const isService = selected && selected.isService || fmt.service(result || {})
      if (isService) {
        this.safeSet({ lastGrabCode: '' })
        await modal({ title: '接单成功', content: '请与发布者沟通服务安排，在承接订单中开始服务，完成后提交用户确认。无需取件码。', showCancel: false })
      } else if (result && result.pickupCode) {
        this.safeSet({ lastGrabCode: '订单 ' + id + ' 的取件码：' + result.pickupCode })
        await modal({ title: '抢单成功', content: '取件码：' + result.pickupCode + '。请到取件点核对并手动填写该码。', showCancel: false })
      } else {
        this.safeSet({ lastGrabCode: '' })
        await modal({ title: '接单成功', content: '请刷新承接订单查看履约步骤；接口未返回取件码，不要使用虚构取件码。', showCancel: false })
      }
    }).then(() => this.load())
  },
  codeInput(e) {
    const id = String(e.currentTarget.dataset.id)
    if (!/^\d+$/.test(id)) return
    this.setData({ ['pickupCodes.' + id]: e.detail.value.trim() })
  },
  pickup(e) {
    const id = e.currentTarget.dataset.id
    if (this.data.orders.some(o => String(o.id) === String(id) && o.isService)) return
    const code = this.data.pickupCodes[String(id)] || ''
    if (!code) return toast('请先输入并核对真实取件码')
    return this.run('busy', async (api) => {
      if (!(await modal({ title: '取件核销', content: '确认已到达取件点，并核对取件码 ' + code + '？' })).confirm) return
      const revision = session.revision(); const generation = this._generation
      try {
        await api.pickup(id, code)
        if (this._dead || !this._visible || revision !== session.revision() || generation !== this._generation) return
        this.safeSet({ ['pickupCodes.' + id]: '' }); toast('取件码核销成功')
      } catch (e) {
        if (this._dead || !this._visible || revision !== session.revision() || generation !== this._generation) return
        this.safeSet({ error: (e.message || '取件未确认') + '；请刷新工作台核实订单归属和状态。' }); toast(e.message || '取件未确认，请刷新核实')
      }
    }).then(() => this.load())
  },
  depart(e) {
    const id = e.currentTarget.dataset.id
    if (this.data.orders.some(o => String(o.id) === String(id) && o.isService)) return
    return this.run('busy', async (api) => {
      if (!(await modal({ title: '出发配送', content: '确认已取到物品并准备出发？' })).confirm) return
      await api.depart(id); toast('已开始配送')
    }).then(() => this.load())
  },
  deliver(e) {
    const id = e.currentTarget.dataset.id
    if (this.data.orders.some(o => String(o.id) === String(id) && o.isService)) return
    return this.run('busy', async (api) => {
      if (!(await modal({ title: '演示送达确认', content: '请确认已实际送达。现有接口未支持照片凭证，本操作仅推进演示状态，不代表已上传送达照片。' })).confirm) return
      await api.deliver(id); toast('已确认送达，等待用户收货')
    }).then(() => this.load())
  },
  report(e) {
    const id = Number(e.currentTarget.dataset.id)
    if (this.data.orders.some(o => Number(o.id) === id && o.isService)) return
    return this.run('busy', async (api) => {
      if (!(await modal({ title: '上报真实配送位置', content: '将获取本次精确位置，仅发送给当前配送订单。页面不会后台持续定位。是否继续？' })).confirm) return
      if (!this._visible || this._dead) return
      const revision = session.revision()
      const pos = await new Promise((resolve, reject) => wx.getLocation({
        type: 'gcj02', isHighAccuracy: true,
        success: resolve,
        fail: err => { this.safeSet({ locationDenied: true }); reject(new Error('定位失败或未授权，请检查小程序位置权限及系统定位：' + (err.errMsg || ''))) }
      }))
      if (!this._visible || this._dead || revision !== session.revision()) return
      if (!Number.isFinite(pos.longitude) || !Number.isFinite(pos.latitude)) throw new Error('设备未提供有效位置，未上报')
      await api.reportTrack({ orderId: id, lng: pos.longitude, lat: pos.latitude, speed: Number.isFinite(pos.speed) && pos.speed >= 0 ? pos.speed : null })
      this.safeSet({ locationDenied: false, locationInfo: '订单 ' + id + ' 已上报：' + pos.longitude.toFixed(6) + ', ' + pos.latitude.toFixed(6) + '；' + fmt.time(new Date().toISOString()) })
      toast('真实位置已上报')
    })
  },
  serviceStart(e) { return this.serviceAction(e, 'serviceStart', '开始服务', '确认已与发布者沟通约定内容并开始服务？') },
  serviceComplete(e) { return this.serviceAction(e, 'serviceComplete', '提交完成', '请核实约定服务已经完成。提交后等待用户确认，不会直接完成结算。') },
  serviceAction(e, action, title, content) {
    const id = e.currentTarget.dataset.id
    const row = this.data.orders.find(o => String(o.id) === String(id))
    if (!row || !row.isService || !(action === 'serviceStart' ? row.canServiceStart : row.canServiceComplete)) return
    return this.run('busy', async (api) => {
      if (!(await modal({ title, content })).confirm || !this._visible || this._dead) return
      const revision = session.revision(); const generation = this._generation
      try {
        await api[action](id)
        if (!this.workbenchCurrent(revision, generation)) return
        toast(action === 'serviceStart' ? '已开始服务' : '已提交完成，等待用户确认')
      } catch (e) {
        if (!this.workbenchCurrent(revision, generation)) return
        this.safeSet({ error: (e.message || '履约未确认') + '；请刷新工作台核实归属和状态。' }); toast(e.message || '履约未确认，请刷新核实')
      }
    }).then(() => this.load())
  },
  detail(e) {
    const id = Number(e.currentTarget.dataset.id)
    if (Number.isSafeInteger(id) && id > 0 && this.data.orders.some(o => Number(o.id) === id)) wx.navigateTo({ url: '/pages/order/detail?id=' + id })
  },
  transfers(e) {
    if (!this.workbenchCurrent(session.revision(), this._generation) || this._working) return
    const id = Number(e && e.currentTarget.dataset.id)
    if (id && (!Number.isSafeInteger(id) || !this.data.orders.some(o => o.id === id && o.canTransfer))) return
    wx.navigateTo({ url: '/pages/rider/transfers' + (id ? '?orderId=' + id : '') })
  },
  permission() { wx.openSetting({}) },
  credit() {
    return this.run('busy', async (api) => {
      const logs = await api.creditLogs()
      this.safeSet({ logsVisible: true, logs: (logs || []).map((l, i) => ({ id: l.id || i, reason: l.reasonCode || '信用调整', deltaText: Number(l.changeValue) > 0 ? '+' + l.changeValue : String(l.changeValue == null ? '—' : l.changeValue), createdText: fmt.time(l.createdTime) })) })
    })
  },
  register() { wx.redirectTo({ url: '/pages/rider/register' }) },
  login() { session.login() }
})
