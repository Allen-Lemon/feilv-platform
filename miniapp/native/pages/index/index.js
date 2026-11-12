const { definePage } = require('../../utils/page')
const session = require('../../utils/session')
definePage({
  data: { types: [
    { code: 'SEND', label: '跑腿送', desc: '文件、鲜花、蛋糕与日常物品同城送达' },
    { code: 'BUY', label: '跑腿买', desc: '商超代购，购买金额与骑手另行确认' },
    { code: 'ERRAND', label: '跑腿办', desc: '代取件、代排队与日常事务代办' }
  ] },
  create(e) { if (session.requireLogin()) wx.navigateTo({ url: '/pages/order/create?type=' + e.currentTarget.dataset.type }) },
  orders() { wx.switchTab({ url: '/pages/order/list' }) },
  settings() { wx.navigateTo({ url: '/pages/settings/settings' }) }
})
