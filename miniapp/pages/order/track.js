const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, toast } = require('../../utils/page')
const { time, service } = require('../../utils/format')
definePage({
  data: { points: [], includePoints: [], markers: [], polyline: [], latitude: 0, longitude: 0, loading: false, error: '', lastUpdated: '', invalid: false },
  onLoad(q) { this._id = Number(q.id); this.setData({ invalid: !Number.isSafeInteger(this._id) || this._id <= 0 }) },
  onShow() { if (session.requireLogin() && !this.data.invalid) return this.load() },
  async load() {
    if (this._working) return
    try {
      await this.run('loading', async (api) => {
        try {
          const revision = session.revision()
          if (this._trackRevision !== revision) {
            this._trackService = false
            const value = await api.orderDetail(this._id)
            if (this._dead || revision !== session.revision()) return
            this._trackService = service(value)
            this._trackRevision = revision
          }
          if (this._trackService) {
            this.clearTimers()
            this.safeSet({ points: [], includePoints: [], markers: [], polyline: [], latitude: 0, longitude: 0, lastUpdated: '', error: '服务订单无需配送轨迹，请查看服务进度。' })
            if (this._visible && !this._dead) {
              toast('服务订单无需配送轨迹')
              wx.redirectTo({ url: '/pages/order/detail?id=' + this._id })
            }
            return
          }
          const raw = await api.orderTrack(this._id)
          if (this._dead || revision !== session.revision()) return
          // 服务端最近50点倒序返回，反转后按真实时间连线；不生成虚构位置。
          const points = (raw || []).slice().reverse().filter(p => p.lng != null && p.lat != null && Number.isFinite(Number(p.lng)) && Number.isFinite(Number(p.lat)) && Math.abs(Number(p.lng)) <= 180 && Math.abs(Number(p.lat)) <= 90).map((p, i) => ({ id: i, longitude: Number(p.lng), latitude: Number(p.lat), lngText: Number(p.lng).toFixed(6), latText: Number(p.lat).toFixed(6), timeText: time(p.time) }))
          const geo = points.map(p => ({ longitude: p.longitude, latitude: p.latitude }))
          const latest = points[points.length - 1]
          this.safeSet({ points, includePoints: geo, polyline: geo.length > 1 ? [{ points: geo, color: '#2a78d6', width: 3, arrowLine: true }] : [], markers: latest ? [{ id: 1, longitude: latest.longitude, latitude: latest.latitude, callout: { content: '最近上报位置\n' + latest.timeText, display: 'ALWAYS', padding: 8, borderRadius: 6 } }] : [], latitude: latest ? latest.latitude : 0, longitude: latest ? latest.longitude : 0, lastUpdated: time(new Date().toISOString()), error: '' })
        } catch (e) { this.safeSet({ error: e.message }); throw e }
      })
    } finally {
      // 页面隐藏/卸载时由公共生命周期统一清理，不在后台轮询。
      if (this._visible && !this._dead && session.token() && !this._trackService) this.later(this.load, 5000)
    }
  },
  refresh() { this.clearTimers(); return this.load() }
})
