const { definePage } = require('../../utils/page')
const session = require('../../utils/session')
definePage({
  data: { types: [
    { code: 'SEND', label: '飞毛腿', desc: '送件与代办合并，仍可切换跑腿买' },
    { code: 'TRANSPORT', label: '滴滴接送', desc: '接送 / 代驾，一个起点，自填时间与总价' },
    { code: 'CUSTOM', label: '自定义任务', desc: '上门清洁、兼职搬运、线上资料整理等；自填需求与服务总价' }
  ] },
  onShow() {
    if (this._pendingType && session.token()) {
      const type = this._pendingType; this._pendingType = null
      wx.navigateTo({ url: type === 'TRANSPORT' ? '/pages/order/create?type=CUSTOM&service=transport' : '/pages/order/create?type=' + type })
    }
  },
  create(e) {
    const type = e.currentTarget.dataset.type
    if (!this.data.types.some(item => item.code === type)) return
    if (!session.token()) { this._pendingType = type; session.login(); return }
    this._pendingType = null
    wx.navigateTo({ url: type === 'TRANSPORT' ? '/pages/order/create?type=CUSTOM&service=transport' : '/pages/order/create?type=' + type })
  }
})
