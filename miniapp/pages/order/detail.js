const session = require('../../utils/session')
const { definePage, toast, modal } = require('../../utils/page')
const fmt = require('../../utils/format')
const complaintTypes = [ { code: 'LOST', label: '丢件' }, { code: 'DAMAGE', label: '损坏' }, { code: 'OVERTIME', label: '超时' }, { code: 'ATTITUDE', label: '服务态度' }, { code: 'OTHER', label: '其他' } ]
definePage({
  data: { order: null, loading: false, busy: false, error: '', invalid: false, stars: [1, 2, 3, 4, 5], starIndex: 4, content: '', reviewed: false, complaintTypes, complaintIndex: 4, complaintDescription: '', cancelReason: '', ticket: '', task: null, taskError: '', taskLoading: false,
    complaints: [], complaintsLoaded: false, complaintsError: '', complaintsLoading: false, activeComplaint: false, complaintAllowed: false, complaintDenied: false },
  onLoad(q) { this._id = Number(q.id); this.setData({ invalid: !Number.isSafeInteger(this._id) || this._id <= 0 }) },
  onShow() { if (session.requireLogin() && !this.data.invalid) return this.load() },
  load() {
    if (this.data.invalid) return
    return this.run('loading', async (api) => {
      const revision = session.revision()
      try {
        const value = await api.orderDetail(this._id)
        if (this._dead || revision !== session.revision()) return
        const key = 'native_review_' + wx.getStorageSync('feilv_user_id') + '_' + this._id
        this.safeSet({ order: fmt.order(value), error: '', reviewed: !!wx.getStorageSync(key), complaintAllowed: false })
      } catch (e) { this.safeSet({ error: e.message, complaintAllowed: false }); throw e }
      if (this.data.order.orderType === 'CUSTOM') await this.fetchTask(api)
      else this.safeSet({ task: null, taskError: '' })
      if (this._dead || revision !== session.revision()) return
      await this.fetchComplaints(api)
      this.updateComplaintAllowed()
    })
  },
  updateComplaintAllowed() {
    const value = this.data.order
    if (!value) return
    let owner = value.owner === true
    if (typeof value.owner !== 'boolean') {
      owner = value.orderType === 'CUSTOM'
        ? !!this.data.task && this._taskOwnerRevision === session.revision() && this._taskOwner === Number(wx.getStorageSync('feilv_user_id'))
        : wx.getStorageSync('feilv_role') === 'USER'
    }
    this.safeSet({ complaintAllowed: value.canComplain && owner && !this.data.error && this.data.complaintsLoaded && !this.data.complaintsError && !this.data.activeComplaint && !this.data.complaintDenied })
  },
  async fetchTask(api) {
    const revision = session.revision()
    try {
      const value = await api.customTask(this._id)
      if (this._dead || revision !== session.revision()) return
      if (value !== null && (!value || typeof value.title !== 'string' || typeof value.content !== 'string' || !Array.isArray(value.tags))) throw new Error('任务资料返回异常，请重试')
      this._taskOwner = value ? Number(value.userId) : null
      this._taskOwnerRevision = revision
      this.safeSet({ task: value ? { title: value.title, content: value.content, tags: value.tags.map(t => ({ id: t.id, name: t.name })) } : null, taskError: '' })
    } catch (e) { this.safeSet({ taskError: e.message || '任务资料读取失败' }) }
  },
  loadTask() {
    if (!this.data.order || this.data.order.orderType !== 'CUSTOM') return
    return this.run('taskLoading', async (api) => { await this.fetchTask(api); this.updateComplaintAllowed() })
  },
  async fetchComplaints(api) {
    const revision = session.revision()
    try {
      const list = await api.orderComplaints(this._id)
      if (this._dead || revision !== session.revision()) return
      if (!Array.isArray(list)) throw new Error('投诉记录返回异常，请重试')
      const complaints = list.map(fmt.complaint)
      this.safeSet({ complaints, complaintsLoaded: true, complaintsError: '', activeComplaint: complaints.some(c => c.active) })
    } catch (e) {
      this.safeSet({ complaintsError: e.message || '投诉记录读取失败', complaintsLoaded: false, complaintAllowed: false })
    }
  },
  loadComplaints() {
    return this.run('complaintsLoading', async (api) => { await this.fetchComplaints(api); this.updateComplaintAllowed() })
  },

  track() { wx.navigateTo({ url: '/pages/order/track?id=' + this._id }) },
  input(e) { const k = e.currentTarget.dataset.field; if (['content', 'complaintDescription', 'cancelReason'].includes(k)) this.setData({ [k]: e.detail.value }) },
  starsChange(e) { this.setData({ starIndex: Number(e.detail.value) }) },
  complaintChange(e) { this.setData({ complaintIndex: Number(e.detail.value) }) },
  pay() {
    return this.run('busy', async (api) => {
      if (!(await modal({ title: 'MOCK 模拟支付', content: '仅调用模拟支付接口，不发生真实扣款，确认继续？' })).confirm) return
      await api.payOrder(this._id)
      this.safeSet({ order: fmt.order(await api.orderDetail(this._id)) }); this.updateComplaintAllowed()
      toast('MOCK 模拟支付已完成')
    })
  },
  cancel() {
    return this.run('busy', async (api) => {
      const accepted = ['ACCEPTED', 'PICKING'].includes(this.data.order.status)
      const text = accepted ? '骑手已接单，后端规则可能收取起步价50%的取消费。退款为模拟流程，是否继续？' : '确认取消此订单？待接单退款为模拟流程，不发生真实资金退回。'
      if (!(await modal({ title: '取消订单', content: text })).confirm) return
      const value = await api.cancelOrder(this._id, this.data.cancelReason.trim() || '用户取消')
      this.safeSet({ order: fmt.order(value) }); this.updateComplaintAllowed(); toast('订单已取消')
    })
  },
  confirm() {
    return this.run('busy', async (api) => {
      if (!(await modal({ title: '确认收货', content: '请确认已收到物品，确认后订单完成。' })).confirm) return
      this.safeSet({ order: fmt.order(await api.confirmOrder(this._id)) }); this.updateComplaintAllowed(); toast('已确认收货')
    })
  },
  review() {
    return this.run('busy', async (api) => {
      await api.review({ orderId: this._id, stars: this.data.starIndex + 1, content: this.data.content.trim() })
      wx.setStorageSync('native_review_' + wx.getStorageSync('feilv_user_id') + '_' + this._id, true)
      this.safeSet({ reviewed: true }); toast('评价已提交')
    })
  },
  complaint() {
    if (!this.data.complaintAllowed || this.data.error) return toast('请先刷新投诉状态，并确认您是订单发布者')
    const description = this.data.complaintDescription.trim()
    if (!description || description.length > 255) return toast('投诉问题描述请填写1至255字')
    const type = complaintTypes[this.data.complaintIndex]
    if (!type) return toast('请选择有效的问题类型')
    return this.run('busy', async (api) => {
      const revision = session.revision()
      try {
        const result = await api.complaint({ orderId: this._id, type: type.code, description })
        if (this._dead || revision !== session.revision()) return
        if (!result || !result.ticketId) throw new Error('投诉结果待核实，请刷新记录后再操作')
        this.safeSet({ complaintDescription: '', complaintsLoaded: false, complaintAllowed: false,
          ticket: '工单编号：' + result.ticketId + '；' + (result.sla || '请等待客服处理') })
        toast('投诉已提交')
        await this.fetchComplaints(api)
        this.updateComplaintAllowed()
      } catch (e) {
        if (revision !== session.revision() || this._dead) return
        if (e.status === 403 || e.code === 403) this.safeSet({ complaintDenied: true, complaintAllowed: false })
        // 即便超时也不能当作未提交；保留描述并要求重新核实服务端状态。
        this.safeSet({ complaintsLoaded: false, complaintAllowed: false, complaintsError: e.message || '提交结果待核实，请刷新投诉记录' })
        throw e
      }
    })
  }
})
