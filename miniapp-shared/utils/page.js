const session = require('./session')
const api = require('./api')
function toast(title) { wx.showToast({ title, icon: 'none', duration: 2800 }) }
function showError(error) { if (!error || !error.silent) toast(error && error.message || '操作失败，请重试') }
function modal(options) { return new Promise((resolve, reject) => wx.showModal(Object.assign({}, options, { success: resolve, fail: reject }))) }
function definePage(definition) {
  const hooks = {}
  ;['onLoad', 'onShow', 'onHide', 'onUnload'].forEach(k => { hooks[k] = definition[k] })
  const methods = Object.assign({
    safeSet(data) {
      const revision = session.revision()
      if (!this._dead && (this._sessionRevision === undefined || this._sessionRevision === revision)
          && (!this._working || this._runRevision === revision)) this.setData(data)
    },
    async run(key, fn) {
      if (this._working || this._dead) return
      const revision = session.revision()
      this._runRevision = revision
      this._working = true
      this.safeSet({ [key]: true })
      try { return await fn(api.forSession(revision)) } finally {
        this._working = false
        if (!this._dead) this.setData({ [key]: false })
        if (this._showAfterRun && this._visible && !this._dead) {
          this._showAfterRun = false
          this.later(() => this.onShow(), 0)
        }
      }
    },
    clearTimers() { (this._timers || []).forEach(clearTimeout); this._timers = [] },
    later(fn, delay) {
      const timer = setTimeout(() => {
        this._timers = (this._timers || []).filter(t => t !== timer)
        if (this._visible && !this._dead) Promise.resolve().then(() => fn.call(this)).catch(showError)
      }, delay)
      this._timers.push(timer)
      return timer
    }
  }, definition)
  methods.onLoad = function(q) {
    this._dead = false; this._visible = false; this._timers = []; this._sessionRevision = session.revision()
    this._initialData = JSON.parse(JSON.stringify(definition.data || {}))
    return hooks.onLoad && hooks.onLoad.call(this, q)
  }
  methods.onShow = function() {
    this._visible = true
    if (this._sessionRevision !== session.revision()) {
      // 先清已渲染的旧账户数据，再发起新账户请求；请求失败也不能保留上一账户内容。
      this.setData(JSON.parse(JSON.stringify(this._initialData || {})))
      this._nextPage = 0; this._refreshPending = false
      this._sessionRevision = session.revision()
    }
    if (this._working && this._runRevision !== session.revision()) {
      this._showAfterRun = true
      return
    }
    return hooks.onShow && hooks.onShow.call(this)
  }
  methods.onHide = function() { this._visible = false; this.clearTimers(); return hooks.onHide && hooks.onHide.call(this) }
  methods.onUnload = function() { this._visible = false; this._dead = true; this.clearTimers(); return hooks.onUnload && hooks.onUnload.call(this) }
  Object.keys(methods).forEach(key => {
    const original = methods[key]
    if (typeof original !== 'function' || ['run', 'safeSet', 'later', 'clearTimers', 'buildPayload'].includes(key)) return
    methods[key] = function(...args) {
      try {
        const result = original.apply(this, args)
        if (result && typeof result.catch === 'function') return result.catch(e => { if (!this._dead) showError(e) })
        return result
      } catch (e) { if (!this._dead) showError(e) }
    }
  })
  Page(methods)
}
module.exports = { definePage, toast, modal, showError }
