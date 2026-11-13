const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, toast } = require('../../utils/page')
const fmt = require('../../utils/format')
const draftState = require('../../utils/draft')
const initial = type => ({ orderType: type, fromContact: '', fromPhone: '', fromAddress: '', fromLng: null, fromLat: null, toContact: '', toPhone: '', toAddress: '', toLng: null, toLat: null, goodsDesc: '', remark: '' })
definePage({
  data: { form: initial('SEND'), typeText: '跑腿送', distanceKm: '', weightKg: '5', busy: false, quote: null },
  onLoad(q) {
    const type = fmt.types[q.type] ? q.type : 'SEND'
    this.setData({ form: initial(type), typeText: fmt.types[type] })
    this._version = 0
  },
  onShow() { session.requireLogin() },
  invalidate() { this._version++; this.setData({ quote: null }); this._quotedPayload = null },
  input(e) {
    const field = e.currentTarget.dataset.field
    if (!Object.prototype.hasOwnProperty.call(this.data.form, field)) return
    this.setData({ ['form.' + field]: e.detail.value })
    if (field === 'fromAddress' || field === 'toAddress') {
      const prefix = field === 'fromAddress' ? 'from' : 'to'
      this.setData({ ['form.' + prefix + 'Lng']: null, ['form.' + prefix + 'Lat']: null })
    }
    this.invalidate()
  },
  number(e) { const field = e.currentTarget.dataset.field; if (['distanceKm', 'weightKg'].includes(field)) { this.setData({ [field]: e.detail.value }); this.invalidate() } },
  address(e) {
    if (this.data.busy) return
    const prefix = e.currentTarget.dataset.side
    if (!['from', 'to'].includes(prefix)) return
    wx.navigateTo({ url: '/pages/address/address?select=1', events: {
      addressSelected: value => {
        if (this._dead) return
        this.setData({ ['form.' + prefix + 'Contact']: value.contact, ['form.' + prefix + 'Phone']: value.phone, ['form.' + prefix + 'Address']: [value.poiName, value.address].filter(Boolean).join(' '), ['form.' + prefix + 'Lng']: value.lng, ['form.' + prefix + 'Lat']: value.lat })
        this.invalidate()
      }
    } })
  },
  buildPayload() {
    const data = Object.assign({}, this.data.form)
    ;['fromContact', 'fromPhone', 'fromAddress', 'toContact', 'toPhone', 'toAddress', 'goodsDesc', 'remark'].forEach(k => { data[k] = data[k].trim() })
    if (!data.fromContact || !data.toContact || !fmt.mobile(data.fromPhone) || !fmt.mobile(data.toPhone) || !data.fromAddress || !data.toAddress) throw new Error('请完整填写取送联系人、11位手机号与地址')
    if (!data.goodsDesc) throw new Error('请填写物品或代办事项')
    if (data.goodsDesc.length > 128 || data.remark.length > 255) throw new Error('物品说明最多128字，备注最多255字')
    const distance = Number(this.data.distanceKm)
    const weight = Number(this.data.weightKg)
    if (!Number.isFinite(distance) || distance <= 0 || distance > 200) throw new Error('请填写0至200公里以内的预估距离（大于0）')
    if (!Number.isInteger(weight) || weight < 1 || weight > 100) throw new Error('重量请填写1至100公斤的整数')
    data.distanceM = Math.round(distance * 1000)
    if (data.distanceM < 1) throw new Error('预估距离至少为1米')
    data.weightKg = weight
    return data
  },
  calculate() {
    return this.run('busy', async (api) => {
      const data = this.buildPayload()
      const version = this._version
      const quote = await api.quote({ orderType: data.orderType, distanceM: data.distanceM, weightKg: data.weightKg })
      if (version !== this._version || this._dead) return
      this._quotedPayload = data
      this.safeSet({ quote: fmt.quote(quote) })
    })
  },
  next() {
    if (!this._quotedPayload || !this.data.quote || this.data.busy) return toast('请先计算最新费用')
    if (!session.requireLogin()) return
    this.buildPayload()
    const owner = wx.getStorageSync('feilv_user_id')
    const previous = wx.getStorageSync('native_draft')
    const reuse = previous && previous.owner === owner && draftState.samePayload(previous.payload, this._quotedPayload)
    // 原草稿未改变时保留标识，以恢复已创建订单或创建结果待核实状态。
    const saved = { payload: this._quotedPayload, quote: this.data.quote, owner, savedAt: reuse ? previous.savedAt : draftState.timestamp() }
    wx.setStorageSync('native_draft', saved)
    wx.navigateTo({ url: '/pages/order/confirm' })
  }
})
