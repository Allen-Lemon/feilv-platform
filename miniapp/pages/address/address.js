const api = require('../../utils/api')
const session = require('../../utils/session')
const { definePage, toast, modal } = require('../../utils/page')
const { mobile } = require('../../utils/format')
const empty = () => ({ contact: '', phone: '', poiName: '', address: '', lng: null, lat: null, isDefault: 0 })
function payload(value) {
  const data = empty()
  Object.keys(data).forEach(k => { if (value[k] !== undefined) data[k] = value[k] })
  return data
}
definePage({
  data: { list: [], form: empty(), editingId: null, selecting: false, busy: false, loading: false, error: '' },
  onLoad(q) { this.setData({ selecting: q.select === '1' }) },
  onShow() { if (session.requireLogin()) return this.load() },
  async load() {
    return this.run('loading', async (api) => {
      try { const list = await api.addressList(); this.safeSet({ list: (list || []).map(a => Object.assign({}, a, { defaultFlag: Number(a.isDefault) === 1 })), error: '' }) }
      catch (e) { this.safeSet({ error: e.message }); throw e }
    })
  },
  input(e) {
    const k = e.currentTarget.dataset.field
    if (!['contact', 'phone', 'poiName', 'address'].includes(k)) return
    const patch = { ['form.' + k]: e.detail.value }
    // 手动改址后没有重新地理编码，不能继续携带旧地点坐标。
    if (k === 'address' || k === 'poiName') { patch['form.lng'] = null; patch['form.lat'] = null }
    this.setData(patch)
  },
  defaultChange(e) { this.setData({ 'form.isDefault': e.detail.value ? 1 : 0 }) },
  edit(e) { const value = this.data.list.find(a => String(a.id) === String(e.currentTarget.dataset.id)); if (value) this.setData({ form: payload(value), editingId: value.id }) },
  reset() { this.setData({ form: empty(), editingId: null }) },
  save() {
    const data = payload(this.data.form)
    ;['contact', 'phone', 'poiName', 'address'].forEach(k => { data[k] = String(data[k]).trim() })
    if (!data.contact || !mobile(data.phone) || !data.address) return toast('请完整填写联系人、11位手机号和详细地址')
    return this.run('busy', async (api) => {
      if (this.data.editingId) await api.updateAddress(this.data.editingId, data)
      else await api.addAddress(data)
      this.reset(); toast('地址已保存')
    }).then(() => this.load())
  },
  setDefault(e) {
    const value = this.data.list.find(a => String(a.id) === String(e.currentTarget.dataset.id))
    if (!value) return
    return this.run('busy', async (api) => { await api.updateAddress(value.id, Object.assign(payload(value), { isDefault: 1 })); toast('已设为默认') }).then(() => this.load())
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
    if (!this.data.selecting) return
    const value = this.data.list.find(a => String(a.id) === String(e.currentTarget.dataset.id))
    if (!value) return
    const channel = this.getOpenerEventChannel()
    channel.emit('addressSelected', payload(value))
    wx.navigateBack()
  }
})
