function valid(value) { return Number.isSafeInteger(value) && value >= 1 && value <= 100000000 }
// 历史订单缺失预算不是零元；预算仅展示，绝不参与报价、支付或分账。
function text(value) { return valid(value) ? '¥' + require('./wallet').fenText(value) : '未记录' }
function payload(value) {
  const result = Object.assign({}, value)
  if (result.orderType !== 'BUY') delete result.goodsBudgetFen
  return result
}
module.exports = { valid, text, payload }
