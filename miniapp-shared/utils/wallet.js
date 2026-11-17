const session = require('./session')
const { time } = require('./format')
const PENDING_KEY = 'native_pending_withdrawal'
const MAX_FEN = 100000000
const statuses = { PENDING: '待审核（已冻结）', APPROVED: '已通过 · MOCK 模拟出账', REJECTED: '已驳回（解冻先抵欠款）' }
const types = { ORDER_INCOME: '订单收入', ORDER_SETTLEMENT: '订单结算收入', COMPLAINT_COMPENSATION: '投诉赔付收入', COMPLAINT_DEDUCTION: '投诉扣减', DEBT_OFFSET: '欠款抵扣', WITHDRAW_FREEZE: '提现冻结', WITHDRAW_REQUEST: '提现申请冻结', WITHDRAW_APPROVED: '提现模拟出账', WITHDRAW_REJECTED: '提现驳回解冻', WITHDRAWAL_FREEZE: '提现冻结', WITHDRAWAL_APPROVED: '提现模拟出账', WITHDRAWAL_REJECTED: '提现驳回解冻' }
// 只解析十进制文本；不使用 parseFloat、元数乘100或浮点四舍五入。
function amountFen(text) {
  if (typeof text !== 'string' || !/^\d{1,7}(?:\.\d{1,2})?$/.test(text)) throw new Error('金额须为0.01至1000000元，最多两位小数')
  const [yuan, fraction = ''] = text.split('.')
  const result = Number(yuan) * 100 + Number((fraction + '00').slice(0, 2))
  if (!validAmount(result)) throw new Error('金额须为0.01至1000000元，最多两位小数')
  return result
}
function validAmount(value) { return Number.isSafeInteger(value) && value >= 1 && value <= MAX_FEN }
function fenText(value) {
  if (!Number.isSafeInteger(value)) throw new Error('钱包金额响应无效，请重试')
  const absolute = Math.abs(value)
  return (value < 0 ? '-' : '') + Math.floor(absolute / 100) + '.' + String(absolute % 100).padStart(2, '0')
}
function nonnegative(value) { return Number.isSafeInteger(value) && value >= 0 }
function walletView(value, owner) {
  if (!value || String(value.userId) !== String(owner) || value.channel !== 'MOCK' || typeof value.withdrawAllowed !== 'boolean'
      || !['availableFen', 'frozenFen', 'debtFen', 'totalIncomeFen', 'totalWithdrawnFen'].every(k => nonnegative(value[k]))) throw new Error('钱包响应无效，请重试核实')
  return { availableFen: value.availableFen, frozenFen: value.frozenFen, debtFen: value.debtFen, withdrawAllowed: value.withdrawAllowed,
    availableText: fenText(value.availableFen), frozenText: fenText(value.frozenFen), debtText: fenText(value.debtFen) }
}
function entryView(value) {
  if (!value || !value.id || typeof value.type !== 'string' || !Number.isSafeInteger(value.amountFen)
      || !['availableAfterFen', 'frozenAfterFen', 'debtAfterFen'].every(k => nonnegative(value[k]))) throw new Error('钱包明细响应无效，请重试')
  return { id: value.id, typeText: types[value.type] || value.type, amountText: (value.amountFen > 0 ? '+' : '') + fenText(value.amountFen),
    availableText: fenText(value.availableAfterFen), frozenText: fenText(value.frozenAfterFen), debtText: fenText(value.debtAfterFen),
    orderId: value.orderId, complaintId: value.complaintId, withdrawalId: value.withdrawalId, remark: value.remark || '', createdText: time(value.createdTime) }
}
function withdrawalView(value, owner) {
  if (!value || !value.id || String(value.userId) !== String(owner) || value.channel !== 'MOCK' || !validAmount(value.amountFen)
      || !Object.prototype.hasOwnProperty.call(statuses, value.status)) throw new Error('提现记录响应无效，请重试核实')
  // 不向模板透传 userId、reviewedBy 等内部身份字段。
  return { id: value.id, amountText: fenText(value.amountFen), status: value.status, statusText: statuses[value.status],
    reason: value.reason || '', createdText: time(value.createdTime), reviewedText: time(value.reviewedTime) }
}
function pageRows(value, page, map) {
  if (!value || !Array.isArray(value.content) || value.content.length > 15 || typeof value.last !== 'boolean'
      || (value.number !== undefined && value.number !== page)) throw new Error('分页响应无效，请重试')
  return { rows: value.content.map(map), hasMore: !value.last && value.content.length > 0 }
}
function owned(owner, revision) { return !!session.token() && session.revision() === revision && String(wx.getStorageSync('feilv_user_id')) === String(owner) }
function pending(owner) {
  const value = wx.getStorageSync(PENDING_KEY)
  if (!value || String(value.owner) !== String(owner)) return null
  if (!validAmount(value.amountFen) || typeof value.requestId !== 'string' || value.requestId.length < 8 || value.requestId.length > 64) throw new Error('待核实提现信息异常，请联系客服核实，勿重复申请')
  return { owner: String(owner), amountFen: value.amountFen, requestId: value.requestId }
}
function savePending(value, owner, revision) {
  if (!owned(owner, revision) || String(value.owner) !== String(owner)) throw new Error('账户状态已变化')
  const previous = pending(owner)
  if (previous && (previous.requestId !== value.requestId || previous.amountFen !== value.amountFen)) throw new Error('已有待核实提现，请先重试原申请')
  wx.setStorageSync(PENDING_KEY, value)
}
function clearPending(value, owner, revision) {
  if (!owned(owner, revision)) return false
  const previous = pending(owner)
  if (!previous || previous.requestId !== value.requestId || previous.amountFen !== value.amountFen) return false
  wx.removeStorageSync(PENDING_KEY)
  return true
}
function requestId() {
  return new Promise(resolve => {
    // 随机值只用于幂等标识，不承担鉴权或支付安全职责。旧基础库使用时间+多段随机值降级。
    const fallback = () => resolve('wd-' + Date.now().toString(36) + '-' + Array.from({ length: 4 }, () => Math.random().toString(36).slice(2, 10)).join('-'))
    if (typeof wx.getRandomValues !== 'function') return fallback()
    try {
      wx.getRandomValues({ length: 16, success(result) {
        try {
          const bytes = new Uint8Array(result.randomValues)
          if (bytes.length !== 16) return fallback()
          bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
          const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
          resolve(hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20))
        } catch { fallback() }
      }, fail: fallback })
    } catch { fallback() }
  })
}
module.exports = { amountFen, fenText, walletView, entryView, withdrawalView, pageRows, pending, savePending, clearPending, requestId }
