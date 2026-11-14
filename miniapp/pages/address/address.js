const session = require('../../utils/session')
const { definePage, toast, modal } = require('../../utils/page')
const { mobile } = require('../../utils/format')
const addresses = require('../../utils/address')
const empty = () => addresses.payload({})
const emptySelection = () => ({ regionValue: addresses.DEFAULT_REGION.slice(), regionText: '', legacyAddress: '' })
definePage({
  data: Object.assign({ list: [], form: empty(), editingId: null, selecting: false, busy: false, loading: false, error: '' }, emptySelection()),
  onLoad(q) { this.setData({ selecting: q.select === '1' }) },
  onShow() { if (session.requireLogin()) return this.load() },
  async load() {
    return this.run('loading', async (api) => {
      try { const list = await api.addressList(); this.safeSet({ list: (list || []).map(a => Object.assign({}, a, { address: addresses.fullAddress(a), defaultFlag: Number(a.isDefault) === 1 })), error: '' }) }
      catch (e) { this.safeSet({ error: e.message }); throw e }
    })
  },
  input(e) {
    if (this._working) return
    const k = e.currentTarget.dataset.field
    if (!['contact', 'phone', 'poiName', 'detailAddress'].includes(k)) return
    const patch = { ['form.' + k]: e.detail.value }
    // 地点或门牌改变后不携带旧坐标；省市区选择本身也不等于已地理编码。
    if (k === 'detailAddress' || k === 'poiName') { patch['form.lng'] = null; patch['form.lat'] = null }
    this.safeSet(patch)
  },
  regionChange(e) {
    if (this._working) return
    const selected = addresses.region(e.detail.value)
    if (!selected) return toast('请选择完整的省、市、区县')
    const changed = selected.join('') !== [this.data.form.province, this.data.form.city, this.data.form.district].join('')
    this.safeSet({ regionValue: selected, regionText: selected.join(' / '), 'form.province': selected[0], 'form.city': selected[1], 'form.district': selected[2],
      'form.detailAddress': changed ? '' : this.data.form.detailAddress, 'form.lng': changed ? null : this.data.form.lng, 'form.lat': changed ? null : this.data.form.lat })
  },
  defaultChange(e) { if (!this._working) this.safeSet({ 'form.isDefault': e.detail.value ? 1 : 0 }) },
  edit(e) {
    if (this._working) return
    const value = this.data.list.find(a => String(a.id) === String(e.currentTarget.dataset.id))
    if (!value) return
    const selected = addresses.regionOf(value)
    this.safeSet({ form: addresses.payload(value), editingId: value.id, regionValue: selected || addresses.DEFAULT_REGION.slice(), regionText: selected ? selected.join(' / ') : '', legacyAddress: selected ? '' : addresses.fullAddress(value) })
  },
  reset() { this.safeSet(Object.assign({ form: empty(), editingId: null }, emptySelection())) },
  save() {
    const data = addresses.payload(this.data.form)
    const selected = addresses.regionOf(data)
    if (!data.contact || !mobile(data.phone)) return toast('请填写联系人与正确的11位手机号')
    if (!selected || !data.detailAddress) return toast('请选择省市区并填写街道、楼栋和门牌')
    if (data.detailAddress.length > 160) return toast('详细地址不能超过160个字符')
    data.address = addresses.fullAddress(data)
    if (data.address.length > 255) return toast('完整地址不能超过255个字符')
    return this.run('busy', async (api) => {
      if (this.data.editingId) await api.updateAddress(this.data.editingId, data)
      else await api.addAddress(data)
      this.reset(); toast('地址已保存')
    }).then(() => this.load())
  },
  setDefault(e) {
    const value = this.data.list.find(a => String(a.id) === String(e.currentTarget.dataset.id))
    if (!value) return
    return this.run('busy', async (api) => { await api.updateAddress(value.id, Object.assign(addresses.payload(value), { isDefault: 1 })); toast('已设为默认') }).then(() => this.load())
  },
  remove(e) {
    const id = e.currentTarget.dataset.id
    return this.run('busy', async (api) => {
      if (!(await modal({ title: '删除地址', content: '确认删除此地址？此操作不可撤回。' })).confirm) return
      await api.deleteAddress(id)
      if (String(this.data.editingId) === String(id)) this.reset()
      toast('地址已删除')
    }).then(() => this.load())
  },
  select(e) {
    if (!this.data.selecting || this._working) return
    const value = this.data.list.find(a => String(a.id) === String(e.currentTarget.dataset.id))
    if (!value) return
    const channel = this.getOpenerEventChannel()
    channel.emit('addressSelected', Object.assign(addresses.payload(value), { id: value.id }))
    wx.navigateBack()
  }
})
