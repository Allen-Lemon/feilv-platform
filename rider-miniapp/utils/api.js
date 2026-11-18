const transport = require('./request')
const root = '/api/v1'
const client = require('../config').CLIENT
const authRoot = root + '/auth/miniapps/' + client
function createApi(r) {
  return {
    wechatLogin: code => r.post(authRoot + '/login', { code, agreed: true }, { public: true }),
    loginOptions: () => r.get(authRoot + '/options', {}, { public: true }),
    createAccount: (loginTicket, phoneCode) => r.post(authRoot + '/accounts', { loginTicket, phoneCode, agreed: true }, { public: true }),
    createDevelopmentAccount: (loginTicket, mobile) => r.post(authRoot + '/accounts', { loginTicket, mobile, agreed: true }, { public: true }),
    linkIdentity: (loginTicket, linkCode) => r.post(authRoot + '/identity-link', { loginTicket, linkCode, agreed: true }, { public: true }),
    miniappLinkCode: code => r.post(root + '/user/miniapp-link-codes', { code, agreed: true }),
    riderEnrollment: ({ name, plateNo, phoneCode, mobile }) => r.post(root + '/rider/enrollment',
      Object.assign({ name, plateNo, agreed: true }, phoneCode != null ? { phoneCode } : { mobile })),
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
    customTask: id => r.get(root + '/rider/orders/' + id + '/custom-task'),
    orderDetail: id => r.get(root + '/rider/orders/' + id),
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
