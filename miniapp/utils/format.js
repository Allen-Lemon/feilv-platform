const types = { SEND: '跑腿送', BUY: '跑腿买', ERRAND: '跑腿办' }
const statuses = { WAIT_PAY: '待支付', WAIT_GRAB: '待接单', ACCEPTED: '已接单', PICKING: '取件中', DELIVERING: '配送中', DELIVERED: '已送达', FINISHED: '已完成', CANCELED: '已取消' }
function money(fen) { return (Number(fen || 0) / 100).toFixed(2) }
function time(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '暂无' }
function order(value) {
  return Object.assign({}, value, {
    typeText: types[value.orderType] || value.orderType,
    statusText: value.statusLabel || statuses[value.status] || value.status,
    feeText: money(value.realFeeFen == null ? value.estimateFeeFen : value.realFeeFen),
    createdText: time(value.createdTime),
    canPay: value.status === 'WAIT_PAY',
    canCancel: ['WAIT_PAY', 'WAIT_GRAB', 'ACCEPTED', 'PICKING'].includes(value.status),
    canConfirm: value.status === 'DELIVERED',
    canReview: value.status === 'FINISHED',
    canComplain: ['DELIVERED', 'FINISHED'].includes(value.status)
  })
}
function quote(value) { return { totalText: money(value.totalFen), items: (value.items || []).map((item, index) => Object.assign({}, item, { key: index, amountText: money(item.amountFen) })) } }
function mobile(value) { return /^1\d{10}$/.test(value) }
module.exports = { types, statuses, money, time, order, quote, mobile }
