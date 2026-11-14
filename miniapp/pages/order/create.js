const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, toast } = require('../../utils/page')
const fmt = require('../../utils/format')
const draftState = require('../../utils/draft')
const addresses = require('../../utils/address')
const initial = type => ({ orderType: type, fromContact: '', fromPhone: '', fromAddress: '', fromLng: null, fromLat: null, toContact: '', toPhone: '', toAddress: '', toLng: null, toLat: null, goodsDesc: '', remark: '' })
definePage({
  data: { form: initial('SEND'), typeText: '跑腿送', distanceKm: '', weightKg: '5', busy: false, quote: null,
    addressOptions: [], addressLoading: false, addressError: '', fromAddressId: null, toAddressId: null, fromAddressIndex: 0, toAddressIndex: 0 },
  onLoad(q) {
    const type = fmt.types[q.type] ? q.type : 'SEND'
    this.setData({ form: initial(type), typeText: fmt.types[type] })
    this._version = 0
  },
  onShow() {
    if (!session.requireLogin()) return
    if (this._working) { this._showAfterRun = true; return }
    return this.loadAddresses()
  },
  loadAddresses() {
    if (!session.token()) return
    return this.run('addressLoading', async (api) => {
      const revision = session.revision()
      try {
        const list = await api.addressList()
        if (this._dead || revision !== session.revision()) return
        const rows = (list || []).filter(a => a && a.id != null && addresses.orderAddress(a) && addresses.orderAddress(a).length <= 255)
          .map(a => Object.assign({ id: a.id }, addresses.payload(a), { label: addresses.label(a) }))
        const patch = { addressOptions: rows, addressError: '' }
        let changed = false
        for (const side of ['from', 'to']) {
          const selected = rows.findIndex(a => String(a.id) === String(this.data[side + 'AddressId']))
          patch[side + 'AddressIndex'] = selected < 0 ? 0 : selected
          // 已选记录在地址簿中被删除或编辑时，不能继续静默提交旧地址。
          if (this.data[side + 'AddressId'] != null) {
            if (selected < 0) {
              patch[side + 'AddressId'] = null
              for (const key of ['Contact', 'Phone', 'Address']) patch['form.' + side + key] = ''
              patch['form.' + side + 'Lng'] = null; patch['form.' + side + 'Lat'] = null
              changed = true
            } else {
              const value = rows[selected]
              const location = addresses.orderAddress(value)
              if (location !== this.data.form[side + 'Address'] || value.lng !== this.data.form[side + 'Lng'] || value.lat !== this.data.form[side + 'Lat']) {
                patch['form.' + side + 'Address'] = location
                patch['form.' + side + 'Lng'] = value.lng; patch['form.' + side + 'Lat'] = value.lat
                changed = true
              }
            }
          }
        }
        this.safeSet(patch)
        if (changed) this.invalidate()
      } catch (e) {
        this.safeSet({ addressOptions: [], addressError: e.message || '地址读取失败，请重试' })
        throw e
      }
    })
  },
  addressChange(e) {
    if (this._working) return
    const side = e.currentTarget.dataset.side
    const index = Number(e.detail.value)
    if (!['from', 'to'].includes(side) || !Number.isInteger(index) || index < 0) return
    const value = this.data.addressOptions[index]
    if (!value) return
    this.applyAddress(side, value)
    this.safeSet({ [side + 'AddressIndex']: index })
  },
  applyAddress(side, value) {
    if (!addresses.orderAddress(value) || addresses.orderAddress(value).length > 255) return toast('此地址过长或为空，请先到地址簿修改')
    this.safeSet({ [side + 'AddressId']: value.id == null ? null : value.id,
      ['form.' + side + 'Contact']: value.contact, ['form.' + side + 'Phone']: value.phone,
      ['form.' + side + 'Address']: addresses.orderAddress(value),
      ['form.' + side + 'Lng']: value.lng == null ? null : value.lng, ['form.' + side + 'Lat']: value.lat == null ? null : value.lat })
    this.invalidate()
  },
  invalidate() { this._version++; this.setData({ quote: null }); this._quotedPayload = null },
  input(e) {
    const field = e.currentTarget.dataset.field
    if (this._working || !['fromContact', 'fromPhone', 'toContact', 'toPhone', 'goodsDesc', 'remark'].includes(field)) return
    this.safeSet({ ['form.' + field]: e.detail.value })
    this.invalidate()
  },
  number(e) { const field = e.currentTarget.dataset.field; if (['distanceKm', 'weightKg'].includes(field)) { this.setData({ [field]: e.detail.value }); this.invalidate() } },
  address(e) {
    if (this.data.busy) return
    const prefix = e.currentTarget.dataset.side
    if (!['from', 'to'].includes(prefix)) return
    const revision = session.revision()
    wx.navigateTo({ url: '/pages/address/address?select=1', events: {
      addressSelected: value => {
        if (this._dead || revision !== session.revision() || !value) return
        this.applyAddress(prefix, value)
      }
    } })
  },
  buildPayload() {
    const data = Object.assign({}, this.data.form)
    ;['fromContact', 'fromPhone', 'fromAddress', 'toContact', 'toPhone', 'toAddress', 'goodsDesc', 'remark'].forEach(k => { data[k] = data[k].trim() })
    if (!data.fromContact || !data.toContact || !fmt.mobile(data.fromPhone) || !fmt.mobile(data.toPhone) || !data.fromAddress || !data.toAddress) throw new Error('请完整填写取送联系人、11位手机号与地址')
    if (data.fromAddress.length > 255 || data.toAddress.length > 255) throw new Error('完整地址不能超过255个字符，请修改地址簿')
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
    if (!this._quotedPayload || !this.data.quote || this._working) return toast('请先计算最新费用')
    if (!session.requireLogin()) return
    this.buildPayload()
    const owner = wx.getStorageSync('feilv_user_id')
    const previous = wx.getStorageSync('native_draft')
    const reuse = previous && previous.owner === owner && draftState.samePayload(previous.payload, this._quotedPayload)
    // 门禁之前保存草稿，拒绝/网络失败/资料页返回均不能丢失填写内容。
    const saved = { payload: this._quotedPayload, quote: this.data.quote, owner, savedAt: reuse ? previous.savedAt : draftState.timestamp() }
    wx.setStorageSync('native_draft', saved)
    return this.run('busy', async (api) => {
      const version = this._version
      const active = () => !this._dead && this._visible && version === this._version && draftState.current(owner, saved.savedAt)
      if (await session.requireOrderProfile(api, active) && active()) wx.navigateTo({ url: '/pages/order/confirm' })
    })
  }
})
