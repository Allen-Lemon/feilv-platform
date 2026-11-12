import request from './request'

export const api = {
  // 认证
  smsCode: (mobile) => request.post('/api/v1/auth/sms-code', { mobile }),
  login: (mobile, code) => request.post('/api/v1/auth/login', { mobile, code }),
  riderRegister: (data) => request.post('/api/v1/auth/rider-register', data),
  me: () => request.get('/api/v1/user/me'),
  addressList: () => request.get('/api/v1/user/address'),
  addAddress: (data) => request.post('/api/v1/user/address', data),
  updateAddress: (id, data) => request.put(`/api/v1/user/address/${id}`, data),
  deleteAddress: (id) => request.delete(`/api/v1/user/address/${id}`),

  // 下单链路
  quote: (data) => request.post('/api/v1/orders/quote', data),
  createOrder: (data) => request.post('/api/v1/orders', data),
  payOrder: (id) => request.post(`/api/v1/orders/${id}/pay`),
  cancelOrder: (id, reason) => request.post(`/api/v1/orders/${id}/cancel`, { reason }),
  confirmOrder: (id) => request.post(`/api/v1/orders/${id}/confirm`),
  myOrders: (page = 0) => request.get(`/api/v1/orders?page=${page}&size=10`),
  orderDetail: (id) => request.get(`/api/v1/orders/${id}`),
  orderTrack: (id) => request.get(`/api/v1/orders/${id}/track`),

  // 评价/投诉
  review: (data) => request.post('/api/v1/reviews', data),
  complaint: (data) => request.post('/api/v1/complaints', data),

  // 骑手

  riderMe: () => request.get('/api/v1/rider/me'),
  riderOnline: (onlineStatus) => request.post('/api/v1/rider/online', { onlineStatus }),
  grabList: () => request.get('/api/v1/grab/list'),
  grab: (orderId) => request.post(`/api/v1/grab/${orderId}`),
  riderOrders: (page = 0) => request.get(`/api/v1/rider/orders?page=${page}`),
  pickup: (id, pickupCode) => request.post(`/api/v1/orders/${id}/pickup`, { pickupCode }),
  depart: (id) => request.post(`/api/v1/orders/${id}/depart`),
  deliver: (id) => request.post(`/api/v1/orders/${id}/deliver`),
  reportTrack: (data) => request.post('/api/v1/track/report', data),
  creditLogs: () => request.get('/api/v1/credit/logs')
}

export default api
