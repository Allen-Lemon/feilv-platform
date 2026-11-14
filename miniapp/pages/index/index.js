const { definePage } = require('../../utils/page')
const session = require('../../utils/session')
definePage({
  data: { types: [
    { code: 'SEND', label: '跑腿送', desc: '文件、鲜花、蛋糕与日常物品同城送达' },
    { code: 'BUY', label: '跑腿买', desc: '商超代购，购买金额与骑手另行确认' },
    { code: 'ERRAND', label: '跑腿办', desc: '代取件、代排队与日常事务代办' }
  ] },
  onShow() {
    if (this._pendingType && session.token()) {
      const type = this._pendingType; this._pendingType = null
      wx.navigateTo({ url: '/pages/order/create?type=' + type })
    }
  },
  create(e) {
    const type = e.currentTarget.dataset.type
    if (!this.data.types.some(item => item.code === type)) return
    if (!session.token()) { this._pendingType = type; session.login(); return }
    this._pendingType = null
    wx.navigateTo({ url: '/pages/order/create?type=' + type })
  },
  orders() { wx.switchTab({ url: '/pages/order/list' }) },
  address() { if (session.requireLogin()) wx.navigateTo({ url: '/pages/address/address' }) },
  settings() { wx.navigateTo({ url: '/pages/settings/settings' }) }
})
