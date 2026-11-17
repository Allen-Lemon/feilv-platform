const transport = require('./request')
const goodsBudget = require('./goods-budget')
const root = '/api/v1'
const client = require('../config').CLIENT
const authRoot = root + '/auth/miniapps/' + client
function validPrice(value) { return Number.isSafeInteger(value) && value >= 1 && value <= 100000000 }
function customBody(data) {
  const transport = ['RIDE', 'DRIVING'].includes(data.taskMode)
  const tagIds = !transport && data.taskTagIds ? String(data.taskTagIds).split(',').map(Number) : []
  if (tagIds.length > 5 || tagIds.some(id => !Number.isSafeInteger(id) || id <= 0) || new Set(tagIds).size !== tagIds.length) throw new Error('任务标签无效，请重新选择')
  const body = { title: String(data.taskTitle || '').trim(), content: String(data.taskContent || '').trim(), tagIds: tagIds.sort((a, b) => a - b),
    taskMode: data.taskMode, priceFen: data.taskPriceFen, contact: String(data.contact || '').trim(), phone: String(data.phone || '').trim(),
    locationAddress: data.taskMode === 'ONLINE' ? '' : String(data.locationAddress || '').trim(), serviceTime: data.taskMode === 'ONLINE' ? '' : String(data.serviceTime || '').trim() }
  if (!['ONLINE', 'ONSITE', 'PART_TIME', 'RIDE', 'DRIVING'].includes(body.taskMode) || !validPrice(body.priceFen)) throw new Error('任务场景或总价无效，请重新填写')
  // 接送/代驾标题由服务端根据 taskMode 生成，备注可空，不依赖标签接口。
  if (transport) { body.title = ''; body.tagIds = [] }
  if (body.title.length > 64 || body.content.length > 1000 || !transport && (!body.title || !body.content)) throw new Error('任务标题或内容无效')
  if (!body.contact || body.contact.length > 30 || !/^1\d{10}$/.test(body.phone)) throw new Error('请填写联系人及11位手机号')
  if ((body.taskMode !== 'ONLINE' && !body.locationAddress) || body.locationAddress.length > 255 || body.serviceTime.length > 100
      || ((body.taskMode === 'PART_TIME' || transport) && !body.serviceTime)) throw new Error('请核对服务地点与时间说明')
  return body
}
function createApi(r) {
  return {
    wechatLogin: code => r.post(authRoot + '/login', { code, agreed: true }, { public: true }),
    loginOptions: () => r.get(authRoot + '/options', {}, { public: true }),
    createAccount: (loginTicket, phoneCode) => r.post(authRoot + '/accounts', { loginTicket, phoneCode, agreed: true }, { public: true }),
    linkIdentity: (loginTicket, linkCode) => r.post(authRoot + '/identity-link', { loginTicket, linkCode, agreed: true }, { public: true }),
    miniappLinkCode: code => r.post(root + '/user/miniapp-link-codes', { code, agreed: true }),
    smsCode: mobile => r.post(root + '/auth/sms-code', { mobile }, { public: true }),
    login: (mobile, code) => r.post(root + '/auth/login', { mobile, code }, { public: true }),
    me: () => r.get(root + '/user/me'),
    wallet: () => r.get(root + '/wallet'),
    walletEntries: (page = 0) => r.get(root + '/wallet/entries', { page }),
    withdrawals: (page = 0) => r.get(root + '/wallet/withdrawals', { page }),
    requestWithdrawal: ({ amountFen, requestId }) => {
      if (!Number.isSafeInteger(amountFen) || amountFen < 1 || amountFen > 100000000
          || typeof requestId !== 'string' || requestId.length < 8 || requestId.length > 64) return Promise.reject(new Error('提现金额或请求编号无效'))
      return r.post(root + '/wallet/withdrawals', { amountFen, requestId })
    },
    updateProfile: nickname => r.put(root + '/user/profile', { nickname }),
    uploadAvatar: filePath => r.uploadAvatar(filePath),
    bindWechatPhone: code => r.post(root + '/user/wechat-phone', { code }),
    phoneBindingOptions: () => r.get(root + '/user/phone/options'),
    bindDevelopmentPhone: mobile => r.post(root + '/user/phone/development', { mobile }),
    phoneSmsCode: mobile => r.post(root + '/user/phone/sms-code', { mobile }),
    bindPhone: (mobile, code) => r.post(root + '/user/phone', { mobile, code }),
    changeWechatPhone: code => r.post(root + '/user/wechat-phone/change', { code }),
    changePhoneSmsCode: mobile => r.post(root + '/user/phone/change/sms-code', { mobile }),
    changePhone: (mobile, code) => r.post(root + '/user/phone/change', { mobile, code }),
    changeDevelopmentPhone: mobile => r.post(root + '/user/phone/development/change', { mobile }),
    addressList: () => r.get(root + '/user/address'),
    addAddress: data => r.post(root + '/user/address', data),
    updateAddress: (id, data) => r.put(root + '/user/address/' + id, data),
    deleteAddress: id => r.delete(root + '/user/address/' + id),
    taskTags: () => r.get(root + '/task-tags'),
    quote: data => {
      if (data.orderType !== 'CUSTOM') return r.post(root + '/orders/quote', { orderType: data.orderType, distanceM: data.distanceM, weightKg: data.weightKg })
      if (!validPrice(data.taskPriceFen)) return Promise.reject(new Error('任务总价无效，请重新填写'))
      return r.post(root + '/custom-tasks/quote', { priceFen: data.taskPriceFen })
    },
    createOrder: data => {
      if (data.orderType !== 'CUSTOM') {
        if (data.orderType === 'BUY' && !goodsBudget.valid(data.goodsBudgetFen)) return Promise.reject(new Error('请返回填写预计商品总价'))
        return r.post(root + '/orders', goodsBudget.payload(data))
      }
      try { return r.post(root + '/custom-tasks', customBody(data)) } catch (e) { return Promise.reject(e) }
    },
    customTask: id => r.get(root + '/orders/' + id + '/custom-task'),
    orderComplaints: id => r.get(root + '/orders/' + id + '/complaints'),
    payOrder: id => r.post(root + '/orders/' + id + '/pay'),
    cancelOrder: (id, reason) => r.post(root + '/orders/' + id + '/cancel', { reason }),
    confirmOrder: id => r.post(root + '/orders/' + id + '/confirm'),
    myOrders: page => r.get(root + '/orders', { page, size: 10 }),
    orderDetail: id => r.get(root + '/orders/' + id),
    orderTrack: id => r.get(root + '/orders/' + id + '/track'),
    review: data => r.post(root + '/reviews', data),
    complaint: data => r.post(root + '/complaints', data),

  }
}
module.exports = Object.assign(createApi(transport.request), {
  // 将多步异步操作绑定到开始时的会话，不能在切换账号后发出后续请求。
  forSession: revision => createApi(transport.bindSession(revision))
})
