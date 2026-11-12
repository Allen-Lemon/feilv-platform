import request from './request'

export const adminApi = {
  login: (data) => request.post('/v1/auth/admin-login', data),
  profile: () => request.post('/v1/auth/admin-profile'),
  stats: () => request.get('/v1/admin/stats'),
  orders: (params) => request.get('/v1/admin/orders', { params }),
  cancelOrder: (id, reason) => request.post(`/v1/admin/orders/${id}/cancel`, { reason }),
  completeOrder: (id, reason) => request.post(`/v1/admin/orders/${id}/complete`, { reason }),
  handleComplaint: (id, data) => request.post(`/v1/admin/complaints/${id}/handle`, data),
  riders: () => request.get('/v1/admin/riders'),
  settlements: () => request.get('/v1/admin/settlements'),
  complaints: (status = 'OPEN') => request.get('/v1/admin/complaints', { params: { status } }),
  systemMenus: () => request.get('/v1/admin/system/menus'),
  saveMenu: (data) => request.post('/v1/admin/system/menus', data),
  deleteMenu: (id) => request.delete(`/v1/admin/system/menus/${id}`),
  systemRoles: () => request.get('/v1/admin/system/roles'),
  saveRole: (data) => request.post('/v1/admin/system/roles', data),
  assignRoleMenus: (roleId, menuIds) => request.post(`/v1/admin/system/roles/${roleId}/menus`, { menuIds }),
  systemUsers: () => request.get('/v1/admin/system/users'),
  changeUserStatus: (id, status) => request.post(`/v1/admin/system/users/${id}/status`, { status }),
  assignUserRoles: (id, roleIds) => request.post(`/v1/admin/system/users/${id}/roles`, { roleIds }),
  createAdmin: (data) => request.post('/v1/admin/system/users', data)
}
