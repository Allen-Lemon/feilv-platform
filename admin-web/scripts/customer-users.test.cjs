// Offline regression tests execute the real Vue setup scripts with API/router doubles.
// Templates are compiled as well; these are not browser/Element Plus integration tests.
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')
const vue = require('vue')
const { babelParse, compileScript, compileTemplate, parse } = require('@vue/compiler-sfc')

function replaceImports(source) {
  const imports = babelParse(source, { sourceType: 'module' }).program.body.filter(node => node.type === 'ImportDeclaration')
  for (const node of imports.reverse()) {
    const declarations = node.specifiers.map(specifier => {
      const name = specifier.type === 'ImportDefaultSpecifier' ? 'default' : specifier.imported.name
      return `const ${specifier.local.name} = __imports[${JSON.stringify(node.source.value)}][${JSON.stringify(name)}];`
    }).join('\n')
    source = source.slice(0, node.start) + declarations + source.slice(node.end)
  }
  return source
}
const components = Object.fromEntries(['UserList', 'OrderList'].map(name => {
  const filename = path.resolve(__dirname, `../src/views/${name}.vue`)
  const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename })
  assert.equal(errors.length, 0)
  const compiled = compileScript(descriptor, { id: name, genDefaultAs: '__component' })
  const template = compileTemplate({ source: descriptor.template.content, filename, id: name, compilerOptions: { bindingMetadata: compiled.bindings } })
  assert.equal(template.errors.length, 0)
  return [name, { template: descriptor.template.content, script: new vm.Script(replaceImports(compiled.content) + '\n__component', { filename }) }]
}))
const snapshot = value => JSON.parse(JSON.stringify(value))
const tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
const user = (overrides = {}) => ({ userId: 42, nickname: '测试用户', avatarUrl: '', role: 'USER', mobile: '138****0000', mobileBound: true, status: 1, createdTime: '2026-11-14 10:00:00', ...overrides })
const pageOf = (content, number = 0, totalElements = 31) => ({ content, number, totalElements })

function harness(t, name, { userId, menus = [], overrides = {} } = {}) {
  const route = vue.reactive({ path: name === 'UserList' ? '/users' : '/orders', query: userId === undefined ? {} : { userId } })
  const menuCodes = vue.ref(menus)
  const calls = [], navigations = [], messages = [], stops = [], confirmations = []
  let mounted = () => {}, beforeUnmount = () => {}
  const implementations = {
    customerUsers: async params => pageOf([user()], params.page),
    customerUser: async id => user({ userId: Number(id) }),
    changeCustomerStatus: async () => undefined,
    orders: async params => pageOf([{ id: 7, status: 'DELIVERED', userId: 42 }], params.page),
    cancelOrder: async () => undefined,
    completeOrder: async () => undefined,
    ...overrides
  }
  const api = Object.fromEntries(Object.keys(implementations).map(method => [method, async (...args) => {
    calls.push({ method, args: snapshot(args) })
    return implementations[method](...args)
  }]))
  const box = {
    response: async () => true,
    async confirm(...args) { confirmations.push(args); return box.response() },
    async prompt(...args) { confirmations.push(args); return box.response() }
  }
  const router = {
    push(target) { navigations.push({ kind: 'push', target: snapshot(target) }) },
    async replace(target) { navigations.push({ kind: 'replace', target: snapshot(target) }); route.query = target.query }
  }
  const component = components[name].script.runInNewContext({ __imports: {
    vue: { ...vue, inject: () => menuCodes, onMounted: callback => { mounted = callback }, onBeforeUnmount: callback => { beforeUnmount = callback }, watch(...args) { const stop = vue.watch(...args); stops.push(stop); return stop } },
    'vue-router': { useRoute: () => route, useRouter: () => router },
    'element-plus': { ElMessage: { success: text => messages.push(text) }, ElMessageBox: box },
    '../api': { adminApi: api }
  } })
  const state = component.setup({}, { expose() {} })
  let unmounted = false
  const unmount = () => { if (!unmounted) { unmounted = true; beforeUnmount(); stops.forEach(stop => stop()) } }
  t.after(unmount)
  return { state, route, menuCodes, calls, navigations, messages, confirmations, box, implementations, mount: mounted, unmount }
}
const called = (h, method) => h.calls.filter(call => call.method === method)

test('API methods use the agreed customer endpoints and status payload', async () => {
  const filename = path.resolve(__dirname, '../src/api/index.js')
  const source = replaceImports(readFileSync(filename, 'utf8')).replace('export const adminApi', 'const adminApi')
  const calls = []
  const request = Object.fromEntries(['get', 'post', 'put', 'delete'].map(method => [method, (...args) => calls.push({ method, args: snapshot(args) })]))
  const api = new vm.Script(source + '\nadminApi').runInNewContext({ __imports: { './request': { default: request } } })
  api.customerUsers({ page: 0, status: 0, mobileBound: false })
  api.customerUser(42)
  api.changeCustomerStatus(42, 0)
  api.orders({ page: 1, userId: '42' })
  assert.deepEqual(calls, [
    { method: 'get', args: ['/v1/admin/users', { params: { page: 0, status: 0, mobileBound: false } }] },
    { method: 'get', args: ['/v1/admin/users/42'] },
    { method: 'post', args: ['/v1/admin/users/42/status', { status: 0 }] },
    { method: 'get', args: ['/v1/admin/orders', { params: { page: 1, userId: '42' } }] }
  ])
})

test('users pagination converts 1-based UI to 0-based API, preserving false/zero filters', async t => {
  const h = harness(t, 'UserList')
  await h.mount()
  assert.equal(h.state.page.value, 1)
  assert.deepEqual(called(h, 'customerUsers')[0].args, [{ page: 0 }])
  Object.assign(h.state.query, { keyword: ' 13800000000 ', status: 0, mobileBound: false })
  await h.state.load(2)
  assert.deepEqual(called(h, 'customerUsers')[1].args, [{ page: 1, keyword: '13800000000', status: 0, mobileBound: false }])
  assert.equal(h.state.page.value, 2)
  await h.state.resetFilters()
  assert.deepEqual(called(h, 'customerUsers')[2].args, [{ page: 0 }])
})

test('users detail follows query changes, handles invalid IDs, and closes without dropping other query keys', async t => {
  const h = harness(t, 'UserList', { userId: '42' })
  await tick()
  assert.equal(h.state.detail.value.userId, 42)
  assert.equal(h.state.detailOpen.value, true)
  h.route.query.userId = '43'
  await tick()
  assert.equal(h.state.detail.value.userId, 43)
  h.route.query.userId = ['42', '43']
  await tick()
  assert.ok(h.state.detailError.value)
  assert.equal(h.state.detail.value, null)
  assert.equal(called(h, 'customerUser').length, 2)
  h.route.query.other = 'keep'
  h.state.closeDetail()
  await tick()
  assert.deepEqual(snapshot(h.route.query), { other: 'keep' })
})

test('users freeze/unfreeze confirms, blocks duplicates, updates detail and refreshes filters', async t => {
  const h = harness(t, 'UserList', { userId: '42' })
  await h.mount()
  await tick()
  const pending = deferred()
  h.box.response = () => pending.promise
  const changing = h.state.changeStatus(h.state.detail.value)
  await h.state.changeStatus(h.state.detail.value)
  assert.equal(h.confirmations.length, 1)
  assert.equal(called(h, 'changeCustomerStatus').length, 0)
  pending.resolve(true)
  await changing
  assert.deepEqual(called(h, 'changeCustomerStatus')[0].args, [42, 0])
  assert.equal(h.state.detail.value.status, 0)
  h.box.response = async () => true
  await h.state.changeStatus(h.state.detail.value)
  assert.deepEqual(called(h, 'changeCustomerStatus')[1].args, [42, 1])
  assert.equal(h.state.detail.value.status, 1)
  assert.equal(h.state.statusBusy.value, null)
})

test('cancelled/failed user status operations never claim success or mutate detail', async t => {
  const h = harness(t, 'UserList', { userId: '42' })
  await tick()
  h.box.response = async () => { throw 'cancel' }
  await h.state.changeStatus(h.state.detail.value)
  assert.equal(called(h, 'changeCustomerStatus').length, 0)
  h.box.response = async () => true
  h.implementations.changeCustomerStatus = async () => { throw new Error('network') }
  await h.state.changeStatus(h.state.detail.value)
  assert.equal(h.state.detail.value.status, 1)
  assert.equal(h.messages.length, 0)
  assert.ok(h.state.listError.value)
  assert.equal(h.state.statusBusy.value, null)
})

test('cross-page user/order navigation fails closed and reacts to current menu grants', async t => {
  const users = harness(t, 'UserList', { userId: '42' })
  const orders = harness(t, 'OrderList')
  await tick()
  users.state.viewOrders()
  orders.state.viewUser({ userId: 42 })
  assert.equal(users.navigations.length + orders.navigations.length, 0)
  assert.equal(users.state.canViewOrders.value, false)
  assert.equal(orders.state.canViewUsers.value, false)
  users.menuCodes.value = ['orders']
  orders.menuCodes.value = ['users']
  users.state.viewOrders()
  orders.state.viewUser({ userId: 42 })
  assert.deepEqual(users.navigations[0], { kind: 'push', target: { path: '/orders', query: { userId: '42' } } })
  assert.deepEqual(orders.navigations[0], { kind: 'push', target: { path: '/users', query: { userId: '42' } } })
  users.menuCodes.value = []
  orders.menuCodes.value = []
  users.state.viewOrders()
  orders.state.viewUser({ userId: 42 })
  assert.equal(users.navigations.length + orders.navigations.length, 2)
  assert.match(components.UserList.template, /v-if="canViewOrders && detail"/)
  assert.match(components.OrderList.template, /v-if="canViewUsers && row.userId != null"/)
})

test('orders route filter and pagination preserve userId and reset to all users', async t => {
  const h = harness(t, 'OrderList', { userId: '42' })
  await tick()
  assert.deepEqual(called(h, 'orders')[0].args, [{ page: 0, userId: '42' }])
  h.state.query.status = 'DELIVERED'
  await h.state.load(2)
  assert.deepEqual(called(h, 'orders')[1].args, [{ page: 1, status: 'DELIVERED', userId: '42' }])
  assert.equal(h.state.page.value, 2)
  h.route.query.userId = '43'
  await tick()
  assert.deepEqual(called(h, 'orders')[2].args, [{ page: 0, status: 'DELIVERED', userId: '43' }])
  await h.state.resetFilters()
  await tick()
  assert.deepEqual(called(h, 'orders')[3].args, [{ page: 0 }])
  assert.equal(h.state.page.value, 1)
})

test('invalid orders userId cannot silently query all users', async t => {
  for (const userId of ['', '0', '-1', 'not-an-id', ['42', '43']]) {
    const h = harness(t, 'OrderList', { userId })
    await tick()
    assert.equal(called(h, 'orders').length, 0)
    assert.ok(h.state.listError.value)
    assert.equal(h.state.loading.value, false)
  }
})

test('orders use status for actions, including delivered completion and terminal safeguards', async t => {
  const h = harness(t, 'OrderList', { userId: '42' })
  await tick()
  h.box.response = async () => ({ value: '  已核实  ' })
  await h.state.complete({ id: 7, status: 'DELIVERED' })
  assert.deepEqual(called(h, 'completeOrder')[0].args, [7, '已核实'])
  await h.state.cancel({ id: 8, status: 'FINISHED' })
  await h.state.cancel({ id: 9, status: 'CANCELED' })
  await h.state.complete({ id: 10, status: 'WAIT_GRAB' })
  assert.equal(called(h, 'cancelOrder').length, 0)
  assert.equal(called(h, 'completeOrder').length, 1)
  assert.equal(h.confirmations[0][2].inputValidator('  '), '原因必填')
  await h.state.cancel({ id: 11, status: 'WAIT_GRAB' })
  assert.deepEqual(called(h, 'cancelOrder')[0].args, [11, '已核实'])
  assert.equal(h.state.actionBusy.value, null)
  assert.doesNotMatch(components.OrderList.template, /statusCode/)
  assert.match(components.OrderList.template, /row\.status === 'DELIVERED'/)
  assert.match(components.OrderList.template, /非寄收联系人/)
})

test('list failures clear stale rows, end loading, expose retry, and recover', async t => {
  for (const [name, method] of [['UserList', 'customerUsers'], ['OrderList', 'orders']]) {
    const h = harness(t, name)
    if (name === 'UserList') await h.mount()
    await tick()
    const previous = h.implementations[method]
    h.implementations[method] = async () => { throw new Error('network') }
    await h.state.load()
    assert.equal(h.state.rows.value.length, 0)
    assert.equal(h.state.loading.value, false)
    assert.ok(h.state.listError.value)
    h.implementations[method] = previous
    await h.state.load()
    assert.equal(h.state.listError.value, '')
    assert.ok(h.state.rows.value.length)
  }
})

test('stale list/detail responses cannot overwrite newer results or closed dialogs', async t => {
  const h = harness(t, 'UserList')
  const pendingList = deferred()
  h.implementations.customerUsers = () => pendingList.promise
  const oldList = h.state.load(1)
  h.implementations.customerUsers = async () => pageOf([user({ userId: 99 })], 1)
  await h.state.load(2)
  pendingList.resolve(pageOf([user()], 0))
  await oldList
  assert.equal(h.state.rows.value[0].userId, 99)
  assert.equal(h.state.page.value, 2)
  const pendingDetail = deferred()
  h.implementations.customerUser = () => pendingDetail.promise
  const oldDetail = h.state.openDetail('42')
  h.state.closeDetail()
  pendingDetail.resolve(user())
  await oldDetail
  assert.equal(h.state.detail.value, null)
  assert.equal(h.state.detailOpen.value, false)
  assert.equal(h.state.detailLoading.value, false)
})

test('detail failures are retryable and unmount during confirmation prevents mutations', async t => {
  const h = harness(t, 'UserList', { overrides: { customerUser: async () => { throw new Error('404') } } })
  await h.state.openDetail('42')
  assert.ok(h.state.detailError.value)
  assert.equal(h.state.detailLoading.value, false)
  h.implementations.customerUser = async () => user()
  await h.state.openDetail('42')
  assert.equal(h.state.detailError.value, '')
  const pending = deferred()
  h.box.response = () => pending.promise
  const changing = h.state.changeStatus(h.state.detail.value)
  h.unmount()
  pending.resolve(true)
  await changing
  assert.equal(called(h, 'changeCustomerStatus').length, 0)
})
