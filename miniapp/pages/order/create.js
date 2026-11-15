const session = require('../../utils/session')
const { definePage, toast } = require('../../utils/page')
const fmt = require('../../utils/format')
const draftState = require('../../utils/draft')
const addresses = require('../../utils/address')
const initial = type => Object.assign({ orderType: type, fromContact: '', fromPhone: '', fromAddress: '', fromLng: null, fromLat: null, toContact: '', toPhone: '', toAddress: '', toLng: null, toLat: null, goodsDesc: '', remark: '' }, type === 'CUSTOM' ? { taskTitle: '', taskContent: '', taskTagIds: '' } : {})
definePage({
  data: { form: initial('SEND'), typeText: '跑腿送', distanceKm: '', weightKg: '1', busy: false, quote: null,
    fromContactEditing: false, toContactEditing: false, moreVisible: false, restored: false,
    goodsPlaceholder: '例如：文件一份、鲜花一束', goodsSuggestions: ['文件', '鲜花', '日用品'],
    custom: false, taskTags: [], tagsLoading: false, tagsLoaded: false, tagsError: '', selectedTagCount: 0,
    addressOptions: [], addressLoading: false, addressError: '', fromAddressId: null, toAddressId: null, fromAddressIndex: 0, toAddressIndex: 0 },
  onLoad(q) {
    const type = fmt.types[q.type] ? q.type : 'SEND'
    this._type = type
    this.setData({ form: initial(type), typeText: fmt.types[type], custom: type === 'CUSTOM',
      goodsPlaceholder: type === 'BUY' ? '需要买什么、数量多少？' : type === 'ERRAND' ? '需要代办什么事？请简要说明' : '例如：文件一份、鲜花一束',
      goodsSuggestions: type === 'BUY' ? ['日用品', '食品饮料', '文具'] : type === 'ERRAND' ? ['代取快递', '代排队', '代送资料'] : ['文件', '鲜花', '日用品'] })
    this._version = 0
    this._defaultRevision = session.revision()
    this.restoreDraft(type)
  },
  restoreDraft(type) {
    const draft = wx.getStorageSync('native_draft')
    const owner = wx.getStorageSync('feilv_user_id')
    if (!session.token() || !draft || draft.owner !== owner || !draft.payload || draft.payload.orderType !== type) return
    const form = initial(type)
    for (const key of Object.keys(form)) if (draft.payload[key] !== undefined) form[key] = draft.payload[key]
    if (type !== 'CUSTOM' && (!Number.isFinite(Number(draft.payload.distanceM)) || Number(draft.payload.distanceM) <= 0)) return
    this.safeSet({ form, distanceKm: Number(draft.payload.distanceM) > 0 ? String(Number(draft.payload.distanceM) / 1000) : '', weightKg: String(draft.payload.weightKg || 1),
      fromAddressId: draft.fromAddressId == null ? null : draft.fromAddressId, toAddressId: draft.toAddressId == null ? null : draft.toAddressId,
      restored: true, selectedTagCount: type === 'CUSTOM' && form.taskTagIds ? String(form.taskTagIds).split(',').length : 0 })
  },
  persistCustomDraft() {
    if (!this.data.custom || !session.token() || this._sessionRevision !== session.revision()) return
    const owner = wx.getStorageSync('feilv_user_id')
    const previous = wx.getStorageSync('native_draft')
    const pending = wx.getStorageSync('native_pending_order')
    // 编辑保存不能覆盖任何已创建或结果未知订单，也不能破坏另一类型的草稿。
    if (previous && previous.owner === owner && (draftState.matches(pending, owner, previous.savedAt) || (!previous.payload || previous.payload.orderType !== 'CUSTOM'))) return
    const payload = Object.assign({}, this.data.form, { distanceM: Number(this.data.distanceKm) * 1000, weightKg: Number(this.data.weightKg) })
    wx.setStorageSync('native_draft', { owner, savedAt: draftState.timestamp(), payload, quote: null,
      fromAddressId: this.data.fromAddressId, toAddressId: this.data.toAddressId })
  },
  async loadTaskTags() {
    if (!this.data.custom || !session.token()) return
    return this.run('tagsLoading', api => this.fetchTaskTags(api))
  },
  async fetchTaskTags(api) {
    const revision = session.revision()
    try {
      const list = await api.taskTags()
      if (this._dead || revision !== session.revision()) return
      if (!Array.isArray(list)) throw new Error('任务标签返回异常，请重试')
      const selected = String(this.data.form.taskTagIds || '').split(',').filter(Boolean)
      const rows = list.filter(t => t && Number.isSafeInteger(t.id) && t.id > 0 && typeof t.name === 'string' && t.name.trim() && (t.enabled === true || t.enabled === 1))
        .sort((a, b) => Number(a.sort || 0) - Number(b.sort || 0) || a.id - b.id)
        .map(t => ({ id: t.id, name: t.name, selected: selected.includes(String(t.id)) }))
      const valid = rows.filter(t => t.selected).map(t => t.id).sort((a, b) => a - b).join(',')
      this.safeSet({ taskTags: rows, tagsLoaded: true, tagsError: '', 'form.taskTagIds': valid, selectedTagCount: rows.filter(t => t.selected).length })
      if (valid !== selected.join(',')) this.invalidate()
    } catch (e) {
      this.safeSet({ taskTags: [], tagsLoaded: false, tagsError: e.message || '标签读取失败，请重试' })
      throw e
    }
  },
  tagChoice(e) {
    if (this._working || !this.data.custom || !this.data.tagsLoaded) return
    const id = Number(e.currentTarget.dataset.id)
    const chosen = this.data.taskTags.find(t => t.id === id)
    if (!chosen) return
    if (!chosen.selected && this.data.selectedTagCount >= 5) return toast('最多选择5个标签')
    const rows = this.data.taskTags.map(t => Object.assign({}, t, { selected: t.id === id ? !t.selected : t.selected }))
    const ids = rows.filter(t => t.selected).map(t => t.id).sort((a, b) => a - b)
    this.safeSet({ taskTags: rows, 'form.taskTagIds': ids.join(','), selectedTagCount: ids.length })
    this.invalidate()
  },
  toggleContact(e) {
    const side = e.currentTarget.dataset.side
    if (this._working || !['from', 'to'].includes(side)) return
    this.safeSet({ [side + 'ContactEditing']: !this.data[side + 'ContactEditing'] })
  },
  toggleMore() { if (!this._working) this.safeSet({ moreVisible: !this.data.moreVisible }) },
  goodsChoice(e) {
    const value = e.currentTarget.dataset.value
    if (this._working || !this.data.goodsSuggestions.includes(value)) return
    this.safeSet({ 'form.goodsDesc': value })
    this.invalidate()
  },
  async onShow() {
    if (!session.requireLogin()) return
    if (this._defaultRevision !== session.revision()) {
      this._defaultRevision = session.revision()
      this._defaultApplied = false
      this._version++
      this.safeSet({ form: initial(this._type), typeText: fmt.types[this._type], custom: this._type === 'CUSTOM' })
      this.restoreDraft(this._type)
    }
    if (this._working) { this._showAfterRun = true; return }
    const revision = session.revision()
    await this.loadAddresses()
    if (revision === session.revision() && this.data.custom && !this._dead && this._visible) return this.loadTaskTags()
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
        // 仅首次空白取件地址使用默认地址，终点仍由用户明确选择，不覆盖已填草稿。
        if (!this.data.form.fromAddress && this.data.fromAddressId == null && !this._defaultApplied) {
          this._defaultApplied = true
          const index = rows.findIndex(a => Number(a.isDefault) === 1)
          if (index >= 0) { this.applyAddress('from', rows[index]); this.safeSet({ fromAddressIndex: index }) }
        }
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
      ['form.' + side + 'Lng']: value.lng == null ? null : value.lng, ['form.' + side + 'Lat']: value.lat == null ? null : value.lat,
      [side + 'ContactEditing']: !value.contact || !fmt.mobile(value.phone) })
    this.invalidate()
  },
  invalidate() { this._version++; this.setData({ quote: null }); this._quotedPayload = null; this.persistCustomDraft() },
  input(e) {
    const field = e.currentTarget.dataset.field
    if (this._working || !['fromContact', 'fromPhone', 'toContact', 'toPhone', 'goodsDesc', 'remark', 'taskTitle', 'taskContent'].includes(field)) return
    this.safeSet({ ['form.' + field]: e.detail.value })
    this.invalidate()
  },
  number(e) { const field = e.currentTarget.dataset.field; if (!this._working && ['distanceKm', 'weightKg'].includes(field)) { this.safeSet({ [field]: e.detail.value }); this.invalidate() } },
  address(e) {
    if (this._working) return
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
    if (data.orderType === 'CUSTOM') {
      data.taskTitle = String(data.taskTitle || '').trim()
      data.taskContent = String(data.taskContent || '').trim()
      if (!data.taskTitle || data.taskTitle.length > 64) throw new Error('任务标题请填写1至64字')
      if (!data.taskContent || data.taskContent.length > 1000) throw new Error('任务内容请填写1至1000字')
      if (!this.data.tagsLoaded || this.data.tagsError) throw new Error('请先重试读取任务标签')
      const ids = String(data.taskTagIds || '').split(',').filter(Boolean).map(Number)
      if (ids.length > 5 || new Set(ids).size !== ids.length || ids.some(id => !this.data.taskTags.some(t => t.id === id))) throw new Error('任务标签已变化，请重新选择')
      data.taskTagIds = ids.sort((a, b) => a - b).join(',')
      data.goodsDesc = data.taskTitle
    }
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
  next() {
    if (this._working || !session.requireLogin()) return
    const data = this.buildPayload()
    const owner = wx.getStorageSync('feilv_user_id')
    const previous = wx.getStorageSync('native_draft')
    const pending = wx.getStorageSync('native_pending_order')
    const reuse = previous && previous.owner === owner && draftState.samePayload(previous.payload, data)
    // 尚有已创建/结果未知订单时，不覆盖它的草稿和标记，即使用户修改了表单。
    if (!reuse && previous && previous.owner === owner && draftState.matches(pending, owner, previous.savedAt)) {
      toast('上一笔任务尚待处理，请先核实后再发布新任务')
      wx.navigateTo({ url: '/pages/order/confirm' })
      return
    }
    const saved = { payload: data, quote: reuse ? previous.quote : null, owner, savedAt: reuse ? previous.savedAt : draftState.timestamp(),
      fromAddressId: this.data.fromAddressId, toAddressId: this.data.toAddressId,
      tagNames: this.data.custom ? this.data.taskTags.filter(t => t.selected).map(t => t.name) : [] }
    wx.setStorageSync('native_draft', saved)
    return this.run('busy', async (api) => {
      const version = this._version
      const active = () => !this._dead && this._visible && version === this._version && draftState.current(owner, saved.savedAt)
      if (!await session.requireOrderProfile(api, active) || !active()) return
      // 对已创建/待核实任务直接续办，不能重算后替换原订单信息。
      if (reuse && draftState.matches(pending, owner, saved.savedAt)) {
        wx.navigateTo({ url: '/pages/order/confirm' })
        return
      }
      const result = await api.quote({ orderType: data.orderType, distanceM: data.distanceM, weightKg: data.weightKg })
      if (!active()) return
      if (!result || !Number.isSafeInteger(result.totalFen) || result.totalFen < 0 || !Array.isArray(result.items)) throw new Error('计价结果异常，请重试；未创建任务')
      const quote = fmt.quote(result)
      wx.setStorageSync('native_draft', Object.assign({}, saved, { quote }))
      this.safeSet({ quote })
      wx.navigateTo({ url: '/pages/order/confirm' })
    })
  }
})
