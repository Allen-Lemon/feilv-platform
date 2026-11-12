const config = require('../../config')
const { baseURL } = require('../../utils/request')
const session = require('../../utils/session')
const { definePage, modal, toast } = require('../../utils/page')
definePage({
  data: { url: '', current: '', busy: false },
  onShow() { this.setData({ url: baseURL(), current: baseURL() }) },
  input(e) { this.setData({ url: e.detail.value.trim() }) },
  save() {
    const value = this.data.url.replace(/\/+$/, '')
    // 仅允许 origin，不允许用户名、密码、查询参数或任意 API 路径。
    if (!/^https?:\/\/(?:[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*|\[[0-9a-fA-F:]+\])(?::\d{1,5})?$/.test(value)) return toast('请输入 http(s) 服务根地址，不带路径、账号或参数')
    return this.run('busy', async () => {
      if (value === baseURL()) return toast('当前地址未变化')
      if (!(await modal({ title: '切换服务', content: '只应连接可信服务。切换将清除当前登录态和订单草稿，避免将旧凭证发往其他服务。是否继续？' })).confirm) return
      session.clear(); wx.setStorageSync('native_base_url', value)
      this.safeSet({ current: value }); toast('服务地址已保存，请重新登录')
    })
  },
  reset() { this.setData({ url: config.BASE_URL }) }
})
