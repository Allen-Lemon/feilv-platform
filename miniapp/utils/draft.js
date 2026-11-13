// 所有草稿/待核实标记均按 owner + savedAt 隔离，旧异步任务不得清理新任务。
let sequence = 0
function matches(value, owner, savedAt) { return !!value && value.owner === owner && value.savedAt === savedAt }
function samePayload(a, b) {
  if (!a || !b) return false
  const keys = Object.keys(a).sort()
  return keys.length === Object.keys(b).length && keys.every(k => a[k] === b[k])
}
function timestamp() { sequence = Math.max(Date.now(), sequence + 1); return sequence }
function current(owner, savedAt) { return matches(wx.getStorageSync('native_draft'), owner, savedAt) }
function clearPending(owner, savedAt) {
  if (matches(wx.getStorageSync('native_pending_order'), owner, savedAt)) wx.removeStorageSync('native_pending_order')
}
function complete(owner, savedAt, orderId) {
  const pending = wx.getStorageSync('native_pending_order')
  if (current(owner, savedAt)) wx.removeStorageSync('native_draft')
  if (matches(pending, owner, savedAt) && pending.id === orderId) wx.removeStorageSync('native_pending_order')
}
module.exports = { matches, samePayload, timestamp, current, clearPending, complete }
