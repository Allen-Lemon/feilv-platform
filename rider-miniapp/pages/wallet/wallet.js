const api = require('../../utils/api')
const session = require('../../utils/session')
const wallet = require('../../utils/wallet')
const { definePage, modal, toast } = require('../../utils/page')
function initialData() {
  return { loggedIn: false, wallet: null, loading: false, error: '', mobileBound: null, profileError: '',
    amount: '', amountError: '', canSubmit: false, busy: false, pending: null, pendingError: '', submitError: '',
    tab: 'entries', entries: [], entriesLoaded: false, entriesLoading: false, entriesError: '', entriesMore: true,
    withdrawals: [], withdrawalsLoaded: false, withdrawalsLoading: false, withdrawalsError: '', withdrawalsMore: true }
}
function context(page) { return { generation: page._generation, revision: session.revision(), owner: String(wx.getStorageSync('feilv_user_id') || '') } }
function active(page, ctx) {
  return !!ctx && page._visible && !page._dead && page._sessionRevision === ctx.revision && page._generation === ctx.generation && session.revision() === ctx.revision
    && !!session.token() && String(wx.getStorageSync('feilv_user_id')) === ctx.owner
}
function pendingView(value) { return value ? { amountText: wallet.fenText(value.amountFen) } : null }
definePage({
  data: initialData(),
  onShow() {
    this._generation = (this._generation || 0) + 1
    this._lists = {}
    this.setData(initialData())
    if (!session.requireLogin()) return
    const ctx = context(this)
    this.setData({ loggedIn: true })
    try {
      const pending = wallet.pending(ctx.owner)
      this.setData({ pending: pendingView(pending), amount: pending ? wallet.fenText(pending.amountFen) : '' })
    } catch (e) { this.setData({ pendingError: e.message }) }
    return Promise.all([this.loadWallet(ctx), this.loadRecords('entries', true, ctx), this.loadRecords('withdrawals', true, ctx)])
  },
  onHide() { this._generation = (this._generation || 0) + 1 },
  onUnload() { this._generation = (this._generation || 0) + 1 },
  updateForm() {
    let amount = null; let amountError = ''
    try { if (this.data.amount) amount = wallet.amountFen(this.data.amount) } catch (e) { amountError = e.message }
    const value = this.data.wallet
    const canSubmit = !!(amount && value && !this.data.error && !this.data.loading && !this.data.busy && !this.data.pending && !this.data.pendingError
      && this.data.mobileBound === true && !this.data.profileError && value.withdrawAllowed && value.debtFen === 0 && amount <= value.availableFen)
    this.setData({ amountError, canSubmit })
  },
  async loadWallet(ctx) {
    if (!active(this, ctx)) return
    const scoped = api.forSession(ctx.revision)
    const load = {}; this._walletLoad = load
    const current = () => active(this, ctx) && this._walletLoad === load
    this.setData({ loading: true, wallet: null, error: '', mobileBound: null, profileError: '', canSubmit: false })
    await Promise.all([
      scoped.wallet().then(value => {
        if (current()) this.setData({ wallet: wallet.walletView(value, ctx.owner), error: '' })
      }).catch(e => { if (current()) this.setData({ wallet: null, error: e.message || '钱包未加载，请重试' }) }),
      scoped.me().then(value => {
        if (!current()) return
        if (!value || String(value.userId) !== ctx.owner || typeof value.mobileBound !== 'boolean') throw new Error('手机号绑定状态未加载，请重试')
        this.setData({ mobileBound: value.mobileBound, profileError: '' })
      }).catch(e => { if (current()) this.setData({ mobileBound: null, profileError: e.message || '手机号绑定状态未加载，请重试' }) })
    ])
    if (current()) { this.setData({ loading: false }); this.updateForm() }
  },
  async loadRecords(kind, reset, ctx) {
    if (!active(this, ctx) || !['entries', 'withdrawals'].includes(kind)) return
    if (reset) this._lists[kind] = { next: 0, loading: false }
    const state = this._lists[kind]
    if (!state || state.loading || (!reset && !this.data[kind + 'More'])) return
    state.loading = true
    const page = state.next
    this.setData({ [kind + 'Loading']: true, [kind + 'Error']: '', ...(reset ? { [kind]: [], [kind + 'Loaded']: false, [kind + 'More']: true } : {}) })
    const current = () => active(this, ctx) && this._lists[kind] === state
    try {
      const scoped = api.forSession(ctx.revision)
      const result = kind === 'entries' ? await scoped.walletEntries(page) : await scoped.withdrawals(page)
      if (!current()) return
      const { rows, hasMore } = wallet.pageRows(result, page, kind === 'entries' ? wallet.entryView : value => wallet.withdrawalView(value, ctx.owner))
      const merged = reset ? rows : this.data[kind].concat(rows)
      const seen = new Set()
      this.setData({ [kind]: merged.filter(row => { const id = String(row.id); if (seen.has(id)) return false; seen.add(id); return true }),
        [kind + 'Loaded']: true, [kind + 'More']: hasMore, [kind + 'Error']: '' })
      state.next = page + 1
    } catch (e) { if (current()) this.setData({ [kind + 'Error']: e.message || '记录未加载，请重试' }) }
    finally { if (current()) { state.loading = false; this.setData({ [kind + 'Loading']: false }) } }
  },
  input(e) {
    if (!active(this, context(this)) || this.data.busy || this.data.pending || this.data.pendingError) return
    this.setData({ amount: e.detail.value, submitError: '' }); this.updateForm()
  },
  tabChange(e) { const tab = e.currentTarget.dataset.tab; if (['entries', 'withdrawals'].includes(tab)) this.setData({ tab }) },
  more() { return this.loadRecords(this.data.tab, false, context(this)) },
  retryRecords() { return this.loadRecords(this.data.tab, false, context(this)) },
  retryWallet() { if (!this.data.busy && !this.data.loading) return this.loadWallet(context(this)) },
  async refresh() {
    try {
      if (this._visible && !this._dead && this._sessionRevision === session.revision() && !this.data.busy) await this.onShow()
    } finally { wx.stopPullDownRefresh() }
  },
  onPullDownRefresh() { return this.refresh() },
  onReachBottom() { return this.more() },
  profile() { if (active(this, context(this))) wx.navigateTo({ url: '/pages/profile/profile?from=wallet&mode=phone' }) },
  login() { session.login() },
  async submit() {
    const ctx = context(this)
    if (!active(this, ctx) || this.data.busy || this.data.pendingError) return
    let pending
    try { pending = wallet.pending(ctx.owner) } catch (e) { this.setData({ pendingError: e.message, canSubmit: false }); return }
    let amountFen
    if (pending) {
      amountFen = pending.amountFen
      this.setData({ pending: pendingView(pending), amount: wallet.fenText(amountFen) })
    } else {
      this.updateForm()
      if (!this.data.canSubmit) return toast(this.data.amountError || '请核实钱包、手机号、欠款及可用余额后再申请')
      amountFen = wallet.amountFen(this.data.amount)
    }
    this.setData({ busy: true, canSubmit: false, submitError: '' })
    try {
      const confirm = await modal({ title: 'MOCK 模拟提现确认',
        content: (pending ? '原申请结果待核实，将使用同一请求编号和金额重试，不新建申请。原申请冻结金额为' : '申请将冻结可用余额')
          + ' ¥' + wallet.fenText(amountFen) + '。后台审核通过仅作 MOCK 模拟出账，非真实微信转账，不代表真钱到账；驳回解冻先抵扣欠款。确认继续？', confirmText: pending ? '重试原申请' : '确认冻结' })
      if (!confirm.confirm || !active(this, ctx)) return
      if (!pending) {
        const requestId = await wallet.requestId()
        if (!active(this, ctx)) return
        // 弹窗/随机数回调期间，其他钱包实例也可能已保存申请，不能覆盖或换编号。
        const existing = wallet.pending(ctx.owner)
        if (existing) {
          this.setData({ pending: pendingView(existing), amount: wallet.fenText(existing.amountFen) })
          throw new Error('已有待核实提现，请重试原申请')
        }
        pending = { owner: ctx.owner, amountFen, requestId }
        wallet.savePending(pending, ctx.owner, ctx.revision) // 必须成功持久化后才允许发送。
        this.setData({ pending: pendingView(pending) })
      }
      const result = await api.forSession(ctx.revision).requestWithdrawal({ amountFen: pending.amountFen, requestId: pending.requestId })
      if (!active(this, ctx)) return // hide/unload/切号后的回调不改页面，也不清理任何storage。
      wallet.withdrawalView(result, ctx.owner)
      if (result.requestId !== pending.requestId || result.amountFen !== pending.amountFen) throw new Error('提现响应与原申请不一致，请重试原申请核实')
      if (!wallet.clearPending(pending, ctx.owner, ctx.revision)) throw new Error('待核实申请已变化，请重新进入钱包核实')
      this.setData({ pending: null, amount: '', submitError: '', canSubmit: false })
      toast('提现状态已确认（MOCK），请查看记录')
      // 不从响应推算金额，不缓存成功余额；服务端钱包与两类记录全部实时刷新。
      await Promise.all([this.loadWallet(ctx), this.loadRecords('entries', true, ctx), this.loadRecords('withdrawals', true, ctx)])
    } catch (e) {
      if (!active(this, ctx)) return
      this.setData({ submitError: (e.message || '提现结果待核实') + (this.data.pending ? '；原编号与金额已保留，请重试原申请，勿另建申请。' : '') })
      // 403可能是未绑手机或其他资格拒绝；重新查询me，不把任意403冒充手机号未绑定。
      if (e.status === 403 || e.code === 403) await this.loadWallet(ctx)
    } finally { if (active(this, ctx)) { this.setData({ busy: false }); this.updateForm() } }
  }
})
