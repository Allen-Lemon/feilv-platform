const types = { SEND: '跑腿送', BUY: '跑腿买', ERRAND: '跑腿办', CUSTOM: '自定义任务' }
const complaintStatuses = { OPEN: '待受理', PROCESSING: '处理中', APPROVED: '审核通过', REJECTED: '审核驳回', DONE: '已结案' }
const activeComplaintStatuses = ['OPEN', 'PROCESSING']
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
    canComplain: ['ACCEPTED', 'PICKING', 'DELIVERING', 'DELIVERED', 'FINISHED'].includes(value.status)
  })
}
function quote(value) { return { totalText: money(value.totalFen), items: (value.items || []).map((item, index) => Object.assign({}, item, { key: index, amountText: money(item.amountFen) })) } }
function mobile(value) { return /^1\d{10}$/.test(value) }
function complaint(value) {
  // 只把售后展示必需字段送入页面，不透传身份、扣减审计及内部操作明细。
  return { id: value.id, type: value.type, description: value.description, status: value.status,
    statusText: complaintStatuses[value.status] || '状态待核实', result: value.result || '',
    active: activeComplaintStatuses.includes(value.status) || !Object.prototype.hasOwnProperty.call(complaintStatuses, value.status),
    compensationText: money(value.compensationFen), createdText: time(value.createdTime), handledText: time(value.handledTime) }
}
module.exports = { types, statuses, money, time, order, quote, mobile, complaint }
