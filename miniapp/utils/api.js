const transport = require('./request')
const root = '/api/v1'
function validPrice(value) { return Number.isSafeInteger(value) && value >= 1 && value <= 100000000 }
function customBody(data) {
  const tagIds = data.taskTagIds ? String(data.taskTagIds).split(',').map(Number) : []
  if (tagIds.length > 5 || tagIds.some(id => !Number.isSafeInteger(id) || id <= 0) || new Set(tagIds).size !== tagIds.length) throw new Error('任务标签无效，请重新选择')
  const body = { title: String(data.taskTitle || '').trim(), content: String(data.taskContent || '').trim(), tagIds: tagIds.sort((a, b) => a - b),
    taskMode: data.taskMode, priceFen: data.taskPriceFen, contact: String(data.contact || '').trim(), phone: String(data.phone || '').trim(),
    locationAddress: data.taskMode === 'ONLINE' ? '' : String(data.locationAddress || '').trim(), serviceTime: data.taskMode === 'ONLINE' ? '' : String(data.serviceTime || '').trim() }
  if (!['ONLINE', 'ONSITE', 'PART_TIME'].includes(body.taskMode) || !validPrice(body.priceFen)) throw new Error('任务场景或总价无效，请重新填写')
  if (!body.title || body.title.length > 64 || !body.content || body.content.length > 1000) throw new Error('任务标题或内容无效')
  if (!body.contact || body.contact.length > 30 || !/^1\d{10}$/.test(body.phone)) throw new Error('请填写联系人及11位手机号')
  if ((body.taskMode !== 'ONLINE' && !body.locationAddress) || body.locationAddress.length > 255 || body.serviceTime.length > 100
      || (body.taskMode === 'PART_TIME' && !body.serviceTime)) throw new Error('请核对服务地点与时间说明')
  return body
}
function createApi(r) {
  return {
    wechatLogin: code => r.post(root + '/auth/wechat-login', { code, agreed: true }, { public: true }),
    smsCode: mobile => r.post(root + '/auth/sms-code', { mobile }, { public: true }),
    login: (mobile, code) => r.post(root + '/auth/login', { mobile, code }, { public: true }),
    riderRegister: data => r.post(root + '/auth/rider-register', data, { public: true }),
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
      if (data.orderType !== 'CUSTOM') return r.post(root + '/orders/quote', data)
      if (!validPrice(data.taskPriceFen)) return Promise.reject(new Error('任务总价无效，请重新填写'))
      return r.post(root + '/custom-tasks/quote', { priceFen: data.taskPriceFen })
    },
    createOrder: data => {
      if (data.orderType !== 'CUSTOM') return r.post(root + '/orders', data)
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
    riderMe: () => r.get(root + '/rider/me'),
    riderOnline: onlineStatus => r.post(root + '/rider/online', { onlineStatus }),
    grabList: () => r.get(root + '/grab/list'),
    grab: id => r.post(root + '/grab/' + id),
    riderOrders: page => r.get(root + '/rider/orders', { page }),
    requestOrderTransfer: (orderId, { targetRiderId, reason }) => {
      const text = typeof reason === 'string' ? reason.trim() : ''
      if (!Number.isSafeInteger(orderId) || orderId <= 0 || !Number.isSafeInteger(targetRiderId) || targetRiderId <= 0 || !text || text.length > 255) return Promise.reject(new Error('请填写有效订单、正整数骑手ID及1至255字转派原因'))
      return r.post(root + '/rider/orders/' + orderId + '/transfers', { targetRiderId, reason: text })
    },
    riderTransfers: (page = 0, direction = 'INCOMING', status) => {
      if (!Number.isInteger(page) || page < 0 || !['INCOMING', 'OUTGOING'].includes(direction)
          || status && !['PENDING', 'ACCEPTED', 'REJECTED', 'CANCELED', 'EXPIRED'].includes(status)) return Promise.reject(new Error('转派查询条件无效'))
      return r.get(root + '/rider/transfers', Object.assign({ page, direction }, status ? { status } : {}))
    },
    respondOrderTransfer: (id, { decision, reason = '' }) => {
      if (!Number.isSafeInteger(id) || id <= 0 || !['ACCEPTED', 'REJECTED'].includes(decision)) return Promise.reject(new Error('转派处理参数无效'))
      return r.post(root + '/rider/transfers/' + id + '/respond', { decision, reason })
    },
    cancelOrderTransfer: (id, { reason = '' } = {}) => {
      if (!Number.isSafeInteger(id) || id <= 0) return Promise.reject(new Error('转派编号无效'))
      return r.post(root + '/rider/transfers/' + id + '/cancel', { reason })
    },
    orderTransfers: orderId => r.get(root + '/orders/' + orderId + '/transfers'),
    pickup: (id, pickupCode) => r.post(root + '/orders/' + id + '/pickup', { pickupCode }),
    depart: id => r.post(root + '/orders/' + id + '/depart'),
    deliver: id => r.post(root + '/orders/' + id + '/deliver'),
    serviceStart: id => r.post(root + '/orders/' + id + '/service-start'),
    serviceComplete: id => r.post(root + '/orders/' + id + '/service-complete'),
    reportTrack: data => r.post(root + '/track/report', data),
    creditLogs: () => r.get(root + '/credit/logs')
  }
}
module.exports = Object.assign(createApi(transport.request), {
  // 将多步异步操作绑定到开始时的会话，不能在切换账号后发出后续请求。
  forSession: revision => createApi(transport.bindSession(revision))
})
