const session = require('../../utils/session')
const { definePage, toast } = require('../../utils/page')
const fmt = require('../../utils/format')
const draftState = require('../../utils/draft')
const addresses = require('../../utils/address')
const { amountFen, fenText } = require('../../utils/wallet')
const goodsBudget = require('../../utils/goods-budget')
const initial = (type, transport = false) => type === 'CUSTOM'
  ? { orderType: type, taskTitle: '', taskContent: '', taskTagIds: '', taskMode: transport ? 'RIDE' : 'ONSITE', contact: '', phone: '', locationAddress: '', serviceTime: '' }
  : { orderType: type, fromContact: '', fromPhone: '', fromAddress: '', fromLng: null, fromLat: null, toContact: '', toPhone: '', toAddress: '', toLng: null, toLat: null, goodsDesc: '', remark: '' }
const deliveryOptions = ['SEND', 'BUY'].map(code => ({ code, label: code === 'SEND' ? '飞毛腿' : fmt.types[code] }))
const sceneOptions = [{ code: 'ONSITE', label: '上门服务', example: '日常清洁 / 物品整理' }, { code: 'PART_TIME', label: '兼职任务', example: '搬运协助 / 临时帮手' }, { code: 'ONLINE', label: '线上任务', example: '资料整理 / 信息录入' }]
const transportOptions = [{ code: 'RIDE', label: '接送', example: '从约定起点接送' }, { code: 'DRIVING', label: '代驾', example: '从约定起点提供代驾' }]
const allScenes = sceneOptions.concat(transportOptions)
const legacyCustom = payload => payload.orderType === 'CUSTOM' && !allScenes.some(s => s.code === payload.taskMode)
definePage({
  data: { form: initial('SEND'), typeText: '飞毛腿', distanceKm: '', weightKg: '1', busy: false, quote: null,
    fromContactEditing: false, toContactEditing: false, moreVisible: false, restored: false,
    goodsPlaceholder: '例如：送文件、代取快递、代排队', goodsSuggestions: ['文件', '鲜花', '代取快递', '代排队', '代送资料'],
    custom: false, transport: false, taskPrice: '', goodsBudget: '', budgetRequired: false, deliveryOptions, sceneOptions, legacyDraft: false,
    taskTags: [], tagsLoading: false, tagsLoaded: false, tagsError: '', selectedTagCount: 0,
    addressOptions: [], addressLoading: false, addressError: '', locationAddressId: null, locationAddressIndex: 0,
    fromAddressId: null, toAddressId: null, fromAddressIndex: 0, toAddressIndex: 0 },
  onLoad(q) {
    const type = q.type === 'CUSTOM' ? 'CUSTOM' : q.type === 'BUY' ? 'BUY' : 'SEND'
    this._transport = type === 'CUSTOM' && q.service === 'transport'
    this.setType(type)
    this._version = 0
    this._defaultRevision = session.revision()
    this.restoreDraft(type)
  },
  setType(type) {
    this._type = type
    this._group = type === 'CUSTOM' ? this._transport ? 'TRANSPORT' : 'CUSTOM' : 'RUNNER'
    this._allTaskTags = []
    this.setData({ form: initial(type, this._transport), typeText: this._transport ? '滴滴接送' : type === 'SEND' ? '飞毛腿' : fmt.types[type],
      custom: type === 'CUSTOM', transport: !!this._transport, sceneOptions: this._transport ? transportOptions : sceneOptions, quote: null, taskPrice: '', goodsBudget: '', budgetRequired: false,
      taskTags: [], tagsLoaded: false, tagsError: '', selectedTagCount: 0,
      distanceKm: '', weightKg: '1', moreVisible: false, restored: false, legacyDraft: false, fromContactEditing: false, toContactEditing: false,
      fromAddressId: null, toAddressId: null, fromAddressIndex: 0, toAddressIndex: 0, locationAddressId: null, locationAddressIndex: 0,
      goodsPlaceholder: type === 'BUY' ? '需要买什么、数量多少？' : '例如：送文件、代取快递、代排队',
      goodsSuggestions: type === 'BUY' ? ['日用品', '食品饮料', '文具'] : ['文件', '鲜花', '代取快递', '代排队', '代送资料'] })
  },
  typeChoice(e) {
    const choice = e.currentTarget.dataset.type
    const type = choice === 'ERRAND' ? 'SEND' : choice
    if (this._working || this.data.custom || !['SEND', 'BUY'].includes(type)) return
    if (type === this._type) { if (type === 'SEND') this.safeSet({ goodsBudget: '' }); return }
    this.persistDraft()
    this._version++; this._defaultApplied = false
    this.setType(type)
    this.restoreDraft(type)
    return this.loadAddresses()
  },
  sceneChoice(e) {
    const mode = e.currentTarget.dataset.mode
    if (this._working || !this.data.custom || !this.data.sceneOptions.some(s => s.code === mode) || mode === this.data.form.taskMode) return
    // 普通场景清理不适用地点/时间；接送和代驾均为单起点，可保留填写内容但必须重新报价。
    this.safeSet(this.data.transport ? { 'form.taskMode': mode } : { 'form.taskMode': mode, 'form.locationAddress': '', 'form.serviceTime': '', locationAddressId: null, locationAddressIndex: 0 })
    this.filterTaskTags()
    this.invalidate()
    if (mode !== 'ONLINE') return this.loadAddresses()
  },
  restoreDraft(type) {
    const owner = wx.getStorageSync('feilv_user_id')
    const draft = draftState.read(owner, this._group, type)
    if (!session.token() || !draft || draft.owner !== owner || !draft.payload) return
    const compatibleType = draft.payload.orderType === type || type === 'SEND' && draft.payload.orderType === 'ERRAND'
    if (!compatibleType) return
    if (legacyCustom(draft.payload)) { this.safeSet({ legacyDraft: true }); return }
    const form = initial(type, this._transport)
    for (const key of Object.keys(form)) if (draft.payload[key] !== undefined) form[key] = draft.payload[key]
    // 只将未创建历史代办表单带入合并入口；旧订单续办仍使用原始 ERRAND payload。
    form.orderType = type
    if (this.data.transport) { form.taskTitle = ''; form.taskTagIds = '' }
    if (type === 'CUSTOM' && form.taskMode === 'ONLINE') { form.locationAddress = ''; form.serviceTime = '' }
    this.safeSet({ form, distanceKm: Number(draft.payload.distanceM) > 0 ? String(Number(draft.payload.distanceM) / 1000) : draft.distanceKm || '', weightKg: String(draft.payload.weightKg || draft.weightKg || 1),
      fromAddressId: draft.fromAddressId == null ? null : draft.fromAddressId, toAddressId: draft.toAddressId == null ? null : draft.toAddressId,
      locationAddressId: draft.locationAddressId == null ? null : draft.locationAddressId,
      taskPrice: type === 'CUSTOM' ? (Number.isSafeInteger(draft.payload.taskPriceFen) ? fenText(draft.payload.taskPriceFen) : draft.taskPrice || '') : '',
      goodsBudget: type === 'BUY' ? goodsBudget.valid(draft.payload.goodsBudgetFen) ? fenText(draft.payload.goodsBudgetFen) : draft.goodsBudget || '' : '',
      budgetRequired: type === 'BUY' && !goodsBudget.valid(draft.payload.goodsBudgetFen) && !draft.goodsBudget,
      restored: true, selectedTagCount: 0 })
    if (type === 'CUSTOM' && this.data.tagsLoaded) this.filterTaskTags()
  },
  persistDraft() {
    if (!session.token() || this._sessionRevision !== session.revision()) return
    const owner = wx.getStorageSync('feilv_user_id')
    const previous = wx.getStorageSync('native_draft')
    const pending = wx.getStorageSync('native_pending_order')
    // pending 保留自己的子槽和活动身份；其他子类/服务组仍可保存未发布编辑内容。
    const pendingWork = previous && previous.owner === owner && draftState.matches(pending, owner, previous.savedAt)
    if (pendingWork && draftState.slot(previous.payload) === draftState.slot(this.data.form)) return
    const saved = { owner, savedAt: draftState.timestamp(), payload: Object.assign({}, this.data.form), quote: null,
      taskPrice: this.data.taskPrice, goodsBudget: this.data.goodsBudget, distanceKm: this.data.distanceKm, weightKg: this.data.weightKg,
      locationAddressId: this.data.locationAddressId, fromAddressId: this.data.fromAddressId, toAddressId: this.data.toAddressId }
    draftState.save(saved, !pendingWork && (!previous || previous.owner !== owner || draftState.group(previous.payload) === this._group))
  },
  async loadTaskTags() {
    if (!this.data.custom || this.data.transport || !session.token()) return
    return this.run('tagsLoading', api => this.fetchTaskTags(api))
  },
  async fetchTaskTags(api) {
    const revision = session.revision()
    try {
      const list = await api.taskTags()
      if (this._dead || revision !== session.revision()) return
      if (!Array.isArray(list)) throw new Error('任务标签返回异常，请重试')
      // taskModes 必须由后端明确分类；旧空分类、未知分类和停用标签不猜测归属。
      this._allTaskTags = list.filter(t => t && Number.isSafeInteger(t.id) && t.id > 0 && typeof t.name === 'string' && t.name.trim()
        && (t.enabled === true || t.enabled === 1) && Array.isArray(t.taskModes) && t.taskModes.length > 0 && t.taskModes.length <= 5
        && new Set(t.taskModes).size === t.taskModes.length && t.taskModes.every(mode => allScenes.some(s => s.code === mode)))
        .sort((a, b) => Number(a.sort || 0) - Number(b.sort || 0) || a.id - b.id)
        .map(t => ({ id: t.id, name: t.name, taskModes: t.taskModes.slice() }))
      this.safeSet({ tagsLoaded: true, tagsError: '' })
      if (this.filterTaskTags()) this.invalidate()
    } catch (e) {
      this.safeSet({ taskTags: [], tagsLoaded: false, tagsError: e.message || '标签读取失败，请重试' })
      throw e
    }
  },
  filterTaskTags() {
    const selected = String(this.data.form.taskTagIds || '').split(',').filter(Boolean)
    const rows = (this._allTaskTags || []).filter(t => t.taskModes.includes(this.data.form.taskMode))
      .map(t => Object.assign({}, t, { selected: selected.includes(String(t.id)) }))
    const ids = rows.filter(t => t.selected).map(t => t.id).sort((a, b) => a - b).slice(0, 5)
    this.safeSet({ taskTags: rows.map(t => Object.assign({}, t, { selected: ids.includes(t.id) })),
      'form.taskTagIds': ids.join(','), selectedTagCount: ids.length })
    return ids.join(',') !== selected.join(',')
  },
  tagChoice(e) {
    if (this._working || !this.data.custom || this.data.transport || !this.data.tagsLoaded) return
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
      this.setType(this._type)
      this.restoreDraft(this._type)
    }
    if (this._working) { this._showAfterRun = true; return }
    const revision = session.revision()
    if (!this.data.custom || this.data.form.taskMode !== 'ONLINE') await this.loadAddresses()
    if (revision === session.revision() && this.data.custom && !this.data.transport && !this._dead && this._visible) return this.loadTaskTags()
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
        for (const side of this.data.custom ? (this.data.form.taskMode === 'ONLINE' ? [] : ['location']) : ['from', 'to']) {
          const selected = rows.findIndex(a => String(a.id) === String(this.data[side + 'AddressId']))
          patch[side + 'AddressIndex'] = selected < 0 ? 0 : selected
          // 已选记录在地址簿中被删除或编辑时，不能继续静默提交旧地址。
          if (this.data[side + 'AddressId'] != null) {
            if (selected < 0) {
              patch[side + 'AddressId'] = null
              if (side === 'location') patch['form.locationAddress'] = ''
              else {
                for (const key of ['Contact', 'Phone', 'Address']) patch['form.' + side + key] = ''
                patch['form.' + side + 'Lng'] = null; patch['form.' + side + 'Lat'] = null
              }
              changed = true
            } else {
              const value = rows[selected]
              const location = addresses.orderAddress(value)
              if (location !== this.data.form[side + 'Address'] || side !== 'location' && (value.lng !== this.data.form[side + 'Lng'] || value.lat !== this.data.form[side + 'Lat'])) {
                patch['form.' + side + 'Address'] = location
                if (side !== 'location') { patch['form.' + side + 'Lng'] = value.lng; patch['form.' + side + 'Lat'] = value.lat }
                changed = true
              }
            }
          }
        }
        this.safeSet(patch)
        if (changed) this.invalidate()
        // 仅首次空白取件地址使用默认地址，终点仍由用户明确选择，不覆盖已填草稿。
        if (!this.data.custom && !this.data.form.fromAddress && this.data.fromAddressId == null && !this._defaultApplied) {
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
    if (!(this.data.custom ? this.data.form.taskMode !== 'ONLINE' && side === 'location' : ['from', 'to'].includes(side)) || !Number.isInteger(index) || index < 0) return
    const value = this.data.addressOptions[index]
    if (!value) return
    this.applyAddress(side, value)
    this.safeSet({ [side + 'AddressIndex']: index })
  },
  applyAddress(side, value) {
    if (!addresses.orderAddress(value) || addresses.orderAddress(value).length > 255) return toast('此地址过长或为空，请先到地址簿修改')
    if (this.data.custom) {
      if (side !== 'location' || this.data.form.taskMode === 'ONLINE') return
      this.safeSet({ locationAddressId: value.id == null ? null : value.id, 'form.locationAddress': addresses.orderAddress(value),
        'form.contact': value.contact || '', 'form.phone': value.phone || '' })
      this.invalidate()
      return
    }
    if (!['from', 'to'].includes(side)) return
    this.safeSet({ [side + 'AddressId']: value.id == null ? null : value.id,
      ['form.' + side + 'Contact']: value.contact, ['form.' + side + 'Phone']: value.phone,
      ['form.' + side + 'Address']: addresses.orderAddress(value),
      ['form.' + side + 'Lng']: value.lng == null ? null : value.lng, ['form.' + side + 'Lat']: value.lat == null ? null : value.lat,
      [side + 'ContactEditing']: !value.contact || !fmt.mobile(value.phone) })
    this.invalidate()
  },
  invalidate() { this._version++; this.setData({ quote: null }); this._quotedPayload = null; this.persistDraft() },
  input(e) {
    const field = e.currentTarget.dataset.field
    if (this._working || !['fromContact', 'fromPhone', 'toContact', 'toPhone', 'goodsDesc', 'remark', 'taskTitle', 'taskContent', 'contact', 'phone', 'serviceTime'].includes(field)) return
    this.safeSet({ ['form.' + field]: e.detail.value })
    this.invalidate()
  },
  number(e) { const field = e.currentTarget.dataset.field; if (!this._working && (this.data.custom ? field === 'taskPrice' : (['distanceKm', 'weightKg'].includes(field) || field === 'goodsBudget' && this._type === 'BUY'))) { this.safeSet({ [field]: e.detail.value }); this.invalidate() } },
  address(e) {
    if (this._working) return
    const prefix = e.currentTarget.dataset.side
    if (!(this.data.custom ? this.data.form.taskMode !== 'ONLINE' && prefix === 'location' : ['from', 'to'].includes(prefix))) return
    const revision = session.revision()
    const version = this._version
    wx.navigateTo({ url: '/pages/address/address?select=1', events: {
      addressSelected: value => {
        if (this._dead || revision !== session.revision() || version !== this._version || !value) return
        this.applyAddress(prefix, value)
      }
    } })
  },
  buildPayload() {
    const data = Object.assign({}, this.data.form)
    if (data.orderType === 'CUSTOM') {
      data.taskTitle = String(data.taskTitle || '').trim()
      data.taskContent = String(data.taskContent || '').trim()
      if (!this.data.sceneOptions.some(s => s.code === data.taskMode)) throw new Error('请选择有效任务场景')
      if (this.data.transport) {
        data.taskTitle = ''; data.taskTagIds = ''
        if (data.taskContent.length > 1000) throw new Error('备注最多1000字')
      } else {
        if (!data.taskTitle || data.taskTitle.length > 64) throw new Error('任务标题请填写1至64字')
        if (!data.taskContent || data.taskContent.length > 1000) throw new Error('任务内容请填写1至1000字')
        if (!this.data.tagsLoaded || this.data.tagsError) throw new Error('请先重试读取任务标签')
        const ids = String(data.taskTagIds || '').split(',').filter(Boolean).map(Number)
        if (ids.length > 5 || new Set(ids).size !== ids.length || ids.some(id => !this.data.taskTags.some(t => t.id === id && t.taskModes.includes(data.taskMode)))) throw new Error('任务标签已变化，请重新选择')
        data.taskTagIds = ids.sort((a, b) => a - b).join(',')
      }
      data.taskPriceFen = amountFen(this.data.taskPrice)
      ;['contact', 'phone', 'locationAddress', 'serviceTime'].forEach(k => { data[k] = String(data[k] || '').trim() })
      if (!data.contact || data.contact.length > 30 || !fmt.mobile(data.phone)) throw new Error('请填写1至30字联系人及11位手机号')
      if (data.taskMode === 'ONLINE') { data.locationAddress = ''; data.serviceTime = '' }
      else if (!data.locationAddress || data.locationAddress.length > 255) throw new Error('请从地址簿选择一个有效服务地点（最多255字）')
      if (data.serviceTime.length > 100 || (data.taskMode === 'PART_TIME' || this.data.transport) && !data.serviceTime) throw new Error(this.data.transport ? '请填写服务时间，最多100字' : '兼职必须填写工作时间；时间说明最多100字')
      // 仅保留服务契约字段，绝不携带旧配送地址、重量或距离。
      return { orderType: 'CUSTOM', taskTitle: data.taskTitle, taskContent: data.taskContent, taskTagIds: data.taskTagIds,
        taskMode: data.taskMode, taskPriceFen: data.taskPriceFen, contact: data.contact, phone: data.phone, locationAddress: data.locationAddress, serviceTime: data.serviceTime }
    }
    data.orderType = this._type === 'BUY' ? 'BUY' : 'SEND'
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
    delete data.goodsBudgetFen
    if (data.orderType === 'BUY') {
      try { data.goodsBudgetFen = amountFen(this.data.goodsBudget) }
      catch { throw new Error('请填写预计商品总价：0.01至1000000元，最多两位小数') }
    }
    return data
  },
  next() {
    if (this._working || !session.requireLogin()) return
    const owner = wx.getStorageSync('feilv_user_id')
    const previous = wx.getStorageSync('native_draft')
    const pending = wx.getStorageSync('native_pending_order')
    // 先续办上一笔，再校验新表单，旧CUSTOM配送草稿也不能误发为服务任务。
    if (previous && previous.owner === owner && draftState.matches(pending, owner, previous.savedAt)) {
      toast('上一笔任务尚待处理，请先核实后再发布新任务')
      wx.navigateTo({ url: '/pages/order/confirm' })
      return
    }
    const data = this.buildPayload()
    const reuse = previous && previous.owner === owner && draftState.samePayload(previous.payload, data)
    const saved = { payload: data, quote: reuse ? previous.quote : null, owner, savedAt: reuse ? previous.savedAt : draftState.timestamp(),
      fromAddressId: this.data.fromAddressId, toAddressId: this.data.toAddressId, locationAddressId: this.data.locationAddressId,
      tagNames: this.data.custom ? this.data.taskTags.filter(t => t.selected).map(t => t.name) : [] }
    draftState.save(saved)
    return this.run('busy', async (api) => {
      const version = this._version
      const active = () => !this._dead && this._visible && version === this._version && draftState.current(owner, saved.savedAt)
      if (!await session.requireOrderProfile(api, active) || !active()) return
      const result = await api.quote(this.data.custom ? { orderType: 'CUSTOM', taskPriceFen: data.taskPriceFen }
        : { orderType: data.orderType, distanceM: data.distanceM, weightKg: data.weightKg })
      if (!active()) return
      if (!result || !Number.isSafeInteger(result.totalFen) || result.totalFen < 0 || !Array.isArray(result.items)) throw new Error('计价结果异常，请重试；未创建任务')
      if (this.data.custom && (result.totalFen !== data.taskPriceFen || !fmt.validShares(result, result.totalFen))) throw new Error('任务总价或分成响应异常，请重试；未创建任务')
      const quote = fmt.quote(result)
      draftState.save(Object.assign({}, saved, { quote }))
      this.safeSet({ quote })
      wx.navigateTo({ url: '/pages/order/confirm' })
    })
  }
})
