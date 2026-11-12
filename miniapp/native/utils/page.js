function toast(title) { wx.showToast({ title, icon: 'none', duration: 2800 }) }
function showError(error) { if (!error || !error.silent) toast(error && error.message || '操作失败，请重试') }
function modal(options) { return new Promise((resolve, reject) => wx.showModal(Object.assign({}, options, { success: resolve, fail: reject }))) }
function definePage(definition) {
  const hooks = {}
  ;['onLoad', 'onShow', 'onHide', 'onUnload'].forEach(k => { hooks[k] = definition[k] })
  const methods = Object.assign({
    safeSet(data) { if (!this._dead) this.setData(data) },
    async run(key, fn) {
      if (this._working || this._dead) return
      this._working = true
      this.safeSet({ [key]: true })
      try { return await fn() } finally { this._working = false; this.safeSet({ [key]: false }) }
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
  methods.onLoad = function(q) { this._dead = false; this._visible = false; this._timers = []; return hooks.onLoad && hooks.onLoad.call(this, q) }
  methods.onShow = function() { this._visible = true; return hooks.onShow && hooks.onShow.call(this) }
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
