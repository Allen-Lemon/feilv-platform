// 所有草稿/待核实标记均按 owner + savedAt 隔离，旧异步任务不得清理新任务。
let sequence = 0
function matches(value, owner, savedAt) { return !!value && value.owner === owner && value.savedAt === savedAt }
function samePayload(a, b) {
  if (!a || !b) return false
  const keys = Object.keys(a).sort()
  return keys.length === Object.keys(b).length && keys.every(k => a[k] === b[k])
}
function timestamp() { sequence = Math.max(Date.now(), sequence + 1); return sequence }
function group(payload) {
  if (!payload) return ''
  if (payload.orderType === 'CUSTOM') return ['RIDE', 'DRIVING'].includes(payload.taskMode) ? 'TRANSPORT' : 'CUSTOM'
  return ['SEND', 'BUY', 'ERRAND'].includes(payload.orderType) ? 'RUNNER' : ''
}
function slot(payload) {
  const key = group(payload)
  return key === 'RUNNER' ? 'RUNNER_' + (payload.orderType === 'BUY' ? 'BUY' : 'SEND') : key
}
function storedDrafts(owner) {
  const stored = wx.getStorageSync('native_draft_groups')
  const drafts = stored && stored.owner === owner ? Object.assign({}, stored.drafts) : {}
  // 兼容原 RUNNER 单槽；历史 ERRAND 与 SEND 共用子槽，不修改原 payload。
  const legacy = drafts.RUNNER
  if (legacy && legacy.owner === owner && group(legacy.payload) === 'RUNNER') {
    const key = slot(legacy.payload)
    if (!drafts[key]) drafts[key] = legacy
  }
  delete drafts.RUNNER
  return drafts
}
function read(owner, key, type) {
  const active = wx.getStorageSync('native_draft')
  const drafts = storedDrafts(owner)
  const requested = key === 'RUNNER' && type ? 'RUNNER_' + (type === 'BUY' ? 'BUY' : 'SEND') : key
  const accepts = value => value && value.owner === owner && (key === 'RUNNER' && !type ? group(value.payload) === key : slot(value.payload) === requested)
  if (accepts(active)) return active
  if (key === 'RUNNER' && !type) return [drafts.RUNNER_SEND, drafts.RUNNER_BUY].filter(accepts).sort((a, b) => b.savedAt - a.savedAt)[0]
  if (accepts(drafts[requested])) return drafts[requested]
}
function save(draft, activate = true) {
  const key = draft && slot(draft.payload)
  if (!key) return false
  const previous = wx.getStorageSync('native_draft')
  const pending = wx.getStorageSync('native_pending_order')
  const sameOwner = previous && previous.owner === draft.owner
  if (sameOwner && matches(pending, draft.owner, previous.savedAt)) {
    // pending 只锁住自己的子槽；其他子类/服务组可以编辑保存，但不能替换活动草稿。
    if (slot(previous.payload) === key) return false
    activate = false
  }
  const drafts = storedDrafts(draft.owner)
  if (sameOwner) {
    const previousKey = slot(previous.payload)
    // 活动草稿可能比其他入口刚保存的编辑内容旧，不能反向覆盖分组草稿。
    if (previousKey && !drafts[previousKey]) drafts[previousKey] = previous
  }
  drafts[key] = draft
  wx.setStorageSync('native_draft_groups', { owner: draft.owner, drafts })
  if (activate) wx.setStorageSync('native_draft', draft)
  return true
}
function current(owner, savedAt) { return matches(wx.getStorageSync('native_draft'), owner, savedAt) }
function clearPending(owner, savedAt) {
  if (matches(wx.getStorageSync('native_pending_order'), owner, savedAt)) wx.removeStorageSync('native_pending_order')
}
function complete(owner, savedAt, orderId) {
  const pending = wx.getStorageSync('native_pending_order')
  if (current(owner, savedAt)) wx.removeStorageSync('native_draft')
  const stored = wx.getStorageSync('native_draft_groups')
  if (stored && stored.owner === owner && stored.drafts) {
    const drafts = storedDrafts(owner)
    Object.keys(drafts).forEach(key => { if (matches(drafts[key], owner, savedAt)) delete drafts[key] })
    wx.setStorageSync('native_draft_groups', { owner, drafts })
  }
  if (matches(pending, owner, savedAt) && pending.id === orderId) wx.removeStorageSync('native_pending_order')
}
module.exports = { matches, samePayload, timestamp, group, slot, read, save, current, clearPending, complete }
