const { definePage } = require('../../utils/page')
const session = require('../../utils/session')
definePage({
  data: { types: [
    { code: 'SEND', label: '跑腿服务', desc: '送件、代买、代办，一个入口按需切换' },
    { code: 'CUSTOM', label: '自定义任务', desc: '上门、兼职或线上服务，自己填写任务总价' }
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
  }
})
