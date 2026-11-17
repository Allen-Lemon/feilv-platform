const session = require('./session')
const { modal, toast } = require('./page')
const { wxCode } = require('./phone-authorization')
const client = require('../config').CLIENT
module.exports = {
  data: { linkCode: '', linkExpiresText: '', linkTarget: '', linkBusy: false },
  clearLinkCode() {
    this._linkGeneration = (this._linkGeneration || 0) + 1
    this.safeSet({ linkCode: '', linkExpiresText: '', linkTarget: '' })
  },
  generateLinkCode() {
    if (!session.requireLogin() || this._working) return
    this.clearLinkCode()
    const generation = this._linkGeneration; const revision = session.revision()
    const current = () => this._visible && !this._dead && revision === session.revision() && generation === this._linkGeneration && !!session.token()
    return this.run('linkBusy', async api => {
      if (!(await modal({ title: '确认关联另一小程序', content: '仅为您本人在另一端关联当前平台账号生成一次性临时码。关联后两端共用此账号的订单、钱包与骑手档案。请勿向他人分享、截图传播或使用他人的码；本页离开后不再显示。', confirmText: '本人确认' })).confirm || !current()) return
      const code = await wxCode()
      if (!current()) return
      const value = await api.miniappLinkCode(code)
      if (!current()) return
      const expires = value && Date.parse(value.expiresAt)
      const target = client === 'USER' ? 'RIDER' : 'USER'
      if (!value || value.targetClient !== target || typeof value.linkCode !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(value.linkCode) || !Number.isFinite(expires) || expires <= Date.now()) throw new Error('临时关联码响应无效，请重试')
      // 仅页面内存展示；不写本机存储、URL、日志或剪贴板。
      this.safeSet({ linkCode: value.linkCode, linkExpiresText: value.expiresAt, linkTarget: target === 'USER' ? '用户端' : '骑手端' })
      this.later(() => { if (current()) { this.clearLinkCode(); toast('关联码已过期') } }, Math.min(expires - Date.now(), 2147483647))
    })
  }
}
