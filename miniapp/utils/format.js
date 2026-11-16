const goodsBudget = require('./goods-budget')
const types = { SEND: '跑腿送', BUY: '跑腿买', ERRAND: '跑腿办', CUSTOM: '自定义任务' }
const complaintStatuses = { OPEN: '待受理', PROCESSING: '处理中', APPROVED: '审核通过', REJECTED: '审核驳回', DONE: '已结案' }
const activeComplaintStatuses = ['OPEN', 'PROCESSING']
const statuses = { WAIT_PAY: '待支付', WAIT_GRAB: '待接单', ACCEPTED: '已接单', PICKING: '取件中', DELIVERING: '配送中', DELIVERED: '已送达', FINISHED: '已完成', CANCELED: '已取消' }
function money(fen) { return (Number(fen || 0) / 100).toFixed(2) }
function time(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '暂无' }
const taskModes = { DELIVERY: '配送任务', ONLINE: '线上任务', ONSITE: '上门服务', PART_TIME: '兼职任务' }
function service(value) { return value.orderType === 'CUSTOM' && ['ONLINE', 'ONSITE', 'PART_TIME'].includes(value.taskMode) }
function order(value) {
  const isService = service(value)
  return Object.assign({}, value, {
    goodsBudgetText: value.orderType === 'BUY' ? goodsBudget.text(value.goodsBudgetFen) : '',
    isService, modeText: taskModes[value.taskMode] || taskModes.DELIVERY,
    typeText: types[value.orderType] || value.orderType,
    statusText: isService ? ({ PICKING: '服务中', DELIVERED: '待确认完成' }[value.status] || value.statusLabel || statuses[value.status] || value.status) : value.statusLabel || statuses[value.status] || value.status,
    feeText: money(value.realFeeFen == null ? value.estimateFeeFen : value.realFeeFen),
    createdText: time(value.createdTime),
    canPay: value.owner !== false && value.status === 'WAIT_PAY',
    canCancel: value.owner !== false && ['WAIT_PAY', 'WAIT_GRAB', 'ACCEPTED', 'PICKING'].includes(value.status),
    canConfirm: value.owner !== false && value.status === 'DELIVERED',
    canReview: value.owner !== false && value.status === 'FINISHED',
    canComplain: ['ACCEPTED', 'PICKING', 'DELIVERING', 'DELIVERED', 'FINISHED'].includes(value.status)
  })
}
function quote(value) { return { totalFen: value.totalFen, totalText: money(value.totalFen),
  riderShareFen: value.riderShareFen, platformShareFen: value.platformShareFen,
  riderShareText: money(value.riderShareFen), platformShareText: money(value.platformShareFen),
  items: (value.items || []).map((item, index) => Object.assign({}, item, { key: index, amountText: money(item.amountFen) })) } }
function validShares(value, total) { return !!value && Number.isSafeInteger(total) && total > 0 && total <= 100000000
  && Number.isSafeInteger(value.riderShareFen) && value.riderShareFen >= 0
  && Number.isSafeInteger(value.platformShareFen) && value.platformShareFen >= 0
  && value.riderShareFen + value.platformShareFen === total }
function task(value) {
  return { title: value.title, content: value.content, tags: value.tags.map(t => ({ id: t.id, name: t.name })),
    taskMode: value.taskMode || 'DELIVERY', modeText: taskModes[value.taskMode] || taskModes.DELIVERY,
    contact: value.contact || '', phone: value.phone || '', locationAddress: value.locationAddress || '', serviceTime: value.serviceTime || '',
    priceText: Number.isSafeInteger(value.priceFen) && value.priceFen > 0 ? money(value.priceFen) : '',
    hasShares: validShares(value, value.priceFen), riderShareText: money(value.riderShareFen), platformShareText: money(value.platformShareFen) }
}
function mobile(value) { return /^1\d{10}$/.test(value) }
function complaint(value) {
  // 只把售后展示必需字段送入页面，不透传身份、扣减审计及内部操作明细。
  return { id: value.id, type: value.type, description: value.description, status: value.status,
    statusText: complaintStatuses[value.status] || '状态待核实', result: value.result || '',
    active: activeComplaintStatuses.includes(value.status) || !Object.prototype.hasOwnProperty.call(complaintStatuses, value.status),
    compensationText: money(value.compensationFen), createdText: time(value.createdTime), handledText: time(value.handledTime) }
}
module.exports = { types, statuses, taskModes, service, money, time, order, quote, validShares, task, mobile, complaint }
