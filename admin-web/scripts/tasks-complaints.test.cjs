// Offline regression tests run production Vue setup scripts with API/router doubles.
// SFC templates compile too; no browser, real payment, database or HTTP is involved.
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')
const vue = require('vue')
const { babelParse, compileScript, compileTemplate, parse } = require('@vue/compiler-sfc')
const root = path.resolve(__dirname, '..')
const snapshot = value => JSON.parse(JSON.stringify(value))
const tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
const pageOf = (content, number = 0, totalElements = 31) => ({ content, number, totalElements })
const tag = (overrides = {}) => ({ id: 3, name: '代办', enabled: true, sort: 2, version: 0, ...overrides })
const task = (overrides = {}) => ({ id: 11, orderId: 21, orderNo: 'T20261115', status: 'WAIT_PAY', userId: 42, title: '线上协助', content: '整理资料', tags: [tag()], amountFen: 1234, taskMode: 'ONLINE', priceFen: 1234, contact: '张三', phone: '13800000000', riderShareFen: 800, platformShareFen: 434, ...overrides })
// Deliberately not a 70/30 split: the UI must preserve the server's allocation.
const serverQuote = (totalFen = 1234, overrides = {}) => ({ totalFen, items: [{ feeType: 'CUSTOM_PRICE', label: '任务总价', amountFen: totalFen }], riderShareFen: totalFen, platformShareFen: 0, ...overrides })
const complaint = (overrides = {}) => ({ id: 8, orderId: 21, orderNo: 'T20261115', userId: 42, riderId: 9, type: 'DAMAGE', description: '物品损坏', status: 'OPEN', deductedFen: 0, orderDeductedFen: 50, maxDeductibleFen: 1234, compensationFen: 0, paymentChannel: 'MOCK', actions: [{ action: 'OPEN', detail: '提交', operatorId: 42, createdTime: '2026-11-15' }], ...overrides })
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
const money = new vm.Script(readFileSync(path.join(root, 'src/utils/money.js'), 'utf8').replace(/export /g, '') + '\n({ formatFen, isFen, yuanToFen })').runInNewContext()
const components = Object.fromEntries(['Tasks', 'Complaints', 'Settlement', 'OrderList', 'Layout'].map(name => {
  const filename = path.join(root, `src/${name === 'Layout' ? 'layout' : 'views'}/${name}.vue`)
  const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename })
  assert.equal(errors.length, 0)
  const compiled = compileScript(descriptor, { id: name, genDefaultAs: '__component' })
  const template = compileTemplate({ source: descriptor.template.content, filename, id: name, compilerOptions: { bindingMetadata: compiled.bindings } })
  assert.equal(template.errors.length, 0)
  return [name, { template: descriptor.template.content, script: new vm.Script(replaceImports(compiled.content) + '\n__component', { filename }) }]
}))
function harness(t, name, { menus = ['tasks', 'complaints'], query = {}, overrides = {} } = {}) {
  const route = vue.reactive({ path: `/${name.toLowerCase()}`, query })
  const menuCodes = vue.ref(menus)
  const calls = [], navigations = [], messages = [], confirmations = [], stops = [], provided = {}, storage = new Map()
  let mounted = () => {}, beforeUnmount = () => {}
  const implementations = {
    taskTags: async () => [tag(), tag({ id: 4, name: '停用标签', enabled: false })],
    createTaskTag: async body => ({ ...tag(), ...body }), updateTaskTag: async (id, body) => ({ id, ...body, version: body.version + 1 }),
    customTasks: async params => pageOf([task()], params.page), customTask: async orderId => task({ orderId }),
    quoteCustomTask: async ({ priceFen }) => serverQuote(priceFen),
    createCustomTask: async () => task(),
    customerUsers: async params => pageOf([{ userId: 42, nickname: '用户', mobile: '138****0000' }], params.page),
    complaints: async params => pageOf([complaint()], params.page), reviewComplaint: async () => complaint(),
    settlements: async () => [{ riderId: 9, name: '骑手', totalShareFen: 2000, totalShareYuan: '20.00', deductedFen: 300, netShareFen: 1700, compensationFen: 300 }],
    orders: async params => pageOf([{ id: 21, status: 'WAIT_PAY', orderType: 'CUSTOM' }], params.page),
    profile: async () => ({ nickname: '管理员', menus: [], roles: [] }),
    ...overrides
  }
  const api = Object.fromEntries(Object.keys(implementations).map(method => [method, async (...args) => {
    calls.push({ method, args: snapshot(args) }); return implementations[method](...args)
  }]))
  const box = { response: async () => true, async confirm(...args) { confirmations.push(args); return box.response() } }
  const router = { push(target) { navigations.push(snapshot(target)) }, async replace(target) { navigations.push(snapshot(target)); route.query = target.query } }
  const component = components[name].script.runInNewContext({ __imports: {
    vue: { ...vue, inject: () => menuCodes, provide: (key, value) => { provided[key] = value }, onMounted: callback => { mounted = callback }, onBeforeUnmount: callback => { beforeUnmount = callback }, watch(...args) { const stop = vue.watch(...args); stops.push(stop); return stop } },
    'vue-router': { useRoute: () => route, useRouter: () => router },
    'element-plus': { ElMessage: { success: text => messages.push(text) }, ElMessageBox: box },
    '../api': { adminApi: api }, '../utils/money': money, '../components/AppIcon.vue': { default: {} }
  }, localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) } })
  const state = component.setup({}, { expose() {} })
  let unmounted = false
  const unmount = () => { if (!unmounted) { unmounted = true; beforeUnmount(); stops.forEach(stop => stop()) } }
  t.after(unmount)
  return { state, route, menuCodes, calls, navigations, messages, confirmations, implementations, box, provided, storage, mount: mounted, unmount }
}
const called = (h, method) => h.calls.filter(call => call.method === method)
function fillTask(h, overrides = {}) {
  h.state.openCreate()
  Object.assign(h.state.form, { userId: '42', title: ' 线上协助 ', content: ' 整理资料 ', tagIds: [3], taskMode: 'ONLINE', priceYuan: '12.34', contact: ' 张三 ', phone: ' 13800000000 ', locationAddress: '', serviceTime: '', ...overrides })
}

test('API contracts use versioned admin endpoints and retain legacy complaint APIs', () => {
  const calls = []
  const request = Object.fromEntries(['get', 'post', 'put', 'delete'].map(method => [method, (...args) => calls.push({ method, args: snapshot(args) })]))
  const source = replaceImports(readFileSync(path.join(root, 'src/api/index.js'), 'utf8')).replace('export const adminApi', 'const adminApi')
  const api = new vm.Script(source + '\nadminApi').runInNewContext({ __imports: { './request': { default: request } } })
  const tagBody = { name: '代办', enabled: false, sort: -2, version: 0 }, review = { decision: 'REJECTED', reason: '不成立', amountFen: 0 }
  api.taskTags(); api.createTaskTag({ name: '代办', enabled: true, sort: 0 }); api.updateTaskTag(3, tagBody)
  api.customTasks({ page: 1, status: 'WAIT_PAY', userId: '42' }); api.customTask(21)
  const taskBody = { userId: '42', title: '线上协助', content: '整理资料', tagIds: [3], taskMode: 'ONLINE', priceFen: 1234, contact: '张三', phone: '13800000000' }
  api.quoteCustomTask({ priceFen: 1234 }); api.createCustomTask(taskBody)
  api.complaints({ page: 0, orderId: '21' }); api.reviewComplaint(8, review); api.complaints('OPEN'); api.complaints(); api.handleComplaint(8, { status: 'DONE' }); api.settlements()
  assert.deepEqual(calls, [
    { method: 'get', args: ['/v1/admin/task-tags'] }, { method: 'post', args: ['/v1/admin/task-tags', { name: '代办', enabled: true, sort: 0 }] }, { method: 'put', args: ['/v1/admin/task-tags/3', tagBody] },
    { method: 'get', args: ['/v1/admin/custom-tasks', { params: { page: 1, status: 'WAIT_PAY', userId: '42' } }] }, { method: 'get', args: ['/v1/admin/custom-tasks/21'] },
    { method: 'post', args: ['/v1/admin/custom-tasks/quote', { priceFen: 1234 }] }, { method: 'post', args: ['/v1/admin/custom-tasks', taskBody] },
    { method: 'get', args: ['/v1/admin/complaints', { params: { page: 0, orderId: '21' } }] }, { method: 'post', args: ['/v1/admin/complaints/8/review', review] },
    { method: 'get', args: ['/v1/admin/complaints', { params: { status: 'OPEN' } }] }, { method: 'get', args: ['/v1/admin/complaints', { params: { status: 'OPEN' } }] },
    { method: 'post', args: ['/v1/admin/complaints/8/handle', { status: 'DONE' }] }, { method: 'get', args: ['/v1/admin/settlements'] }
  ])
})

test('money conversion is exact, rejects fractional cents, negatives and unsafe amounts', () => {
  for (const [yuan, fen] of [['0', 0], ['0.01', 1], ['0.29', 29], ['1.1', 110], ['12.34', 1234], [' 001.20 ', 120], ['90071992547409.91', Number.MAX_SAFE_INTEGER]]) assert.equal(money.yuanToFen(yuan), fen)
  for (const value of ['', '-1', '-0', '1.001', '0.009', '1e2', 'Infinity', 'NaN', '.5', '1.', '+1', '90071992547409.92']) assert.equal(money.yuanToFen(value), null, value)
  assert.equal(money.formatFen(29), '0.29'); assert.equal(money.formatFen(1234), '12.34'); assert.equal(money.formatFen(null), '—'); assert.equal(money.formatFen(1.1), '—')
  assert.equal(money.formatFen(Number.MAX_SAFE_INTEGER), '90071992547409.91')
})

test('tasks fail closed on missing grants and user search independently requires users permission', async t => {
  const h = harness(t, 'Tasks', { menus: [] })
  await tick(); await h.state.load(); await h.state.loadTags(); h.state.openCreate(); await h.state.getQuote(); await h.state.saveTag(); await h.state.searchUsers('用户')
  assert.equal(h.calls.length, 0); assert.equal(h.state.createOpen.value, false)
  h.menuCodes.value = ['tasks']; await tick(); fillTask(h); await h.state.searchUsers('用户')
  assert.equal(called(h, 'customTasks').length, 1); assert.equal(called(h, 'taskTags').length, 1); assert.equal(called(h, 'customerUsers').length, 0)
  assert.equal(h.state.canSearchUsers.value, false)
  h.menuCodes.value = ['tasks', 'users']; await tick(); await h.state.searchUsers('  用户  ')
  assert.deepEqual(called(h, 'customerUsers')[0].args, [{ page: 0, keyword: '用户' }]); assert.equal(h.state.form.userId, '42')
  h.menuCodes.value = []; await tick(); assert.equal(h.state.rows.value.length, 0); assert.equal(h.state.createOpen.value, false)
})

test('task filters paginate 15 rows with zero-based API and invalid IDs never query all users', async t => {
  const h = harness(t, 'Tasks'); await tick()
  Object.assign(h.state.query, { status: 'WAIT_PAY', userId: ' 42 ' }); await h.state.load(2)
  assert.deepEqual(called(h, 'customTasks')[1].args, [{ page: 1, status: 'WAIT_PAY', userId: '42' }]); assert.equal(h.state.page.value, 2)
  for (const userId of ['0', '-1', 'abc']) { h.state.query.userId = userId; await h.state.load(1); assert.ok(h.state.listError.value) }
  assert.equal(called(h, 'customTasks').length, 2)
  await h.state.resetFilters(); assert.deepEqual(called(h, 'customTasks')[2].args, [{ page: 0 }])
  assert.match(components.Tasks.template, /:page-size="15"/)
})

test('task publishing validates every bounded field without obtaining a quote or creating', async t => {
  const h = harness(t, 'Tasks'); await tick()
  const invalid = [{ userId: '' }, { userId: '0' }, { title: ' ' }, { title: '字'.repeat(65) }, { title: '\u{1F600}'.repeat(65) }, { content: '' }, { content: '字'.repeat(1001) }, { content: '\u{1F600}'.repeat(1001) }, { tagIds: [3, 3] }, { tagIds: [4] }, { tagIds: [1, 2, 3, 4, 5, 6] }, { contact: '' }, { phone: '123' }, { phone: '1390000000x' }, { taskMode: 'DELIVERY' }, { taskMode: 'PARTTIME' }, { taskMode: '' }, { priceYuan: '0' }, { priceYuan: '1000000.01' }, { priceYuan: '1.001' }, { priceYuan: '-1' }, { taskMode: 'ONSITE', locationAddress: '' }, { taskMode: 'PART_TIME', locationAddress: '工地', serviceTime: '' }]
  for (const draft of invalid) { fillTask(h, draft); await h.state.getQuote(); await h.state.publish(); assert.ok(h.state.createError.value, JSON.stringify(draft)) }
  assert.equal(called(h, 'quoteCustomTask').length, 0); assert.equal(called(h, 'createCustomTask').length, 0)
  fillTask(h, { title: '\u{1F600}'.repeat(64), content: '\u{1F600}'.repeat(1000), priceYuan: '1000000.00' }); await h.state.getQuote()
  assert.deepEqual(called(h, 'quoteCustomTask')[0].args, [{ priceFen: 100000000 }])
})

test('publish requires server quote, snapshots exact payload and never calls payment APIs', async t => {
  const h = harness(t, 'Tasks'); await tick(); fillTask(h)
  await h.state.publish(); assert.equal(h.confirmations.length, 0)
  await h.state.getQuote(); assert.equal(h.state.quote.value.totalFen, 1234)
  const gate = deferred(); h.box.response = () => gate.promise
  const publishing = h.state.publish(); await h.state.publish()
  assert.equal(h.confirmations.length, 1); assert.equal(called(h, 'createCustomTask').length, 0)
  assert.match(h.confirmations[0][0], /12\.34/); assert.match(h.confirmations[0][0], /小程序支付/)
  gate.resolve(true); await publishing
  assert.deepEqual(called(h, 'createCustomTask')[0].args, [{ userId: '42', title: '线上协助', content: '整理资料', tagIds: [3], taskMode: 'ONLINE', priceFen: 1234, contact: '张三', phone: '13800000000' }])
  assert.equal(h.state.createOpen.value, false); assert.match(h.messages[0], /后台未代付/)
  assert.equal(h.calls.some(c => /pay/i.test(c.method)), false)
})

test('quote invalidates on any edit, drops late responses, blocks duplicate quote and malformed totals', async t => {
  const h = harness(t, 'Tasks'); await tick(); fillTask(h); await h.state.getQuote()
  h.state.form.content = '新内容'; assert.equal(h.state.quote.value, null); await h.state.publish(); assert.equal(h.confirmations.length, 0)
  const pending = deferred(); h.implementations.quoteCustomTask = () => pending.promise
  const quoting = h.state.getQuote(); await h.state.getQuote(); h.state.form.priceYuan = '9.99'
  pending.resolve(serverQuote()); await quoting
  assert.equal(h.state.quote.value, null); assert.equal(called(h, 'quoteCustomTask').length, 2)
  for (const totalFen of [null, -1, 1.1]) { h.implementations.quoteCustomTask = async () => serverQuote(999, { totalFen }); await h.state.getQuote(); assert.equal(h.state.quote.value, null); assert.ok(h.state.createError.value) }
})

test('creation cancellation, failed responses, revoked grants and unmount never claim payment success', async t => {
  const h = harness(t, 'Tasks'); await tick(); fillTask(h); await h.state.getQuote()
  h.box.response = async () => { throw 'cancel' }; await h.state.publish(); assert.equal(called(h, 'createCustomTask').length, 0); assert.ok(h.state.quote.value)
  h.box.response = async () => true; h.implementations.createCustomTask = async () => { throw new Error('timeout') }
  await h.state.publish(); assert.match(h.state.createError.value, /核对任务列表/); assert.equal(h.state.quote.value, null); assert.equal(h.messages.length, 0)
  assert.equal(h.state.createNeedsCheck.value, true); await h.state.getQuote(); assert.equal(called(h, 'quoteCustomTask').length, 1)
  h.state.createOpen.value = false; h.state.openCreate(); assert.equal(h.state.createOpen.value, false)
  await h.state.load(1); assert.equal(h.state.createNeedsCheck.value, false); h.state.openCreate(); assert.equal(h.state.createOpen.value, true)
  for (const action of ['revoke', 'unmount']) {
    const h2 = harness(t, 'Tasks'); await tick(); fillTask(h2); await h2.state.getQuote()
    const pending = deferred(); h2.box.response = () => pending.promise
    const publishing = h2.state.publish(); if (action === 'revoke') h2.menuCodes.value = []; else h2.unmount()
    pending.resolve(true); await publishing; assert.equal(called(h2, 'createCustomTask').length, 0)
  }
})

test('online tasks need no address and never submit stale location or legacy delivery fields', async t => {
  const h = harness(t, 'Tasks'); await tick()
  fillTask(h, { locationAddress: '不应提交的旧服务地点', serviceTime: '不应提交的旧时间', fromAddress: '旧取件地址', toAddress: '旧送达地址', distanceM: 1250, weightKg: 1 })
  await h.state.getQuote(); await h.state.publish()
  assert.deepEqual(called(h, 'quoteCustomTask')[0].args, [{ priceFen: 1234 }])
  const body = called(h, 'createCustomTask')[0].args[0]
  assert.equal(body.taskMode, 'ONLINE'); assert.equal(body.priceFen, 1234)
  for (const key of ['order', 'orderType', 'locationAddress', 'serviceTime', 'fromAddress', 'toAddress', 'distanceM', 'weightKg']) assert.equal(Object.hasOwn(body, key), false, key)
  assert.match(components.Tasks.template, /v-if="form.taskMode !== 'ONLINE'"/)
  assert.doesNotMatch(components.Tasks.template, /v-model="form\.(from\w*|to\w*|distanceM|weightKg)"/)
})

test('onsite services use a single hand-entered location and optional service time', async t => {
  const h = harness(t, 'Tasks'); await tick()
  fillTask(h, { taskMode: 'ONSITE', title: ' 通马桶 ', locationAddress: ' 德江县某小区 1 栋 101 ', serviceTime: '' })
  await h.state.getQuote(); await h.state.publish()
  assert.deepEqual(called(h, 'createCustomTask')[0].args, [{ userId: '42', title: '通马桶', content: '整理资料', tagIds: [3], taskMode: 'ONSITE', priceFen: 1234, contact: '张三', phone: '13800000000', locationAddress: '德江县某小区 1 栋 101' }])
  fillTask(h, { taskMode: 'ONSITE', locationAddress: ' 小区 ', serviceTime: ' 11 月 20 日下午 ' })
  await h.state.getQuote(); await h.state.publish()
  assert.equal(called(h, 'createCustomTask')[1].args[0].serviceTime, '11 月 20 日下午')
  assert.match(components.Tasks.template, /管理员手填，无需地址簿/)
})

test('part-time labor requires one work location and a nonblank time of at most 100 characters', async t => {
  const h = harness(t, 'Tasks'); await tick()
  for (const draft of [{ locationAddress: ' ', serviceTime: '明天上午' }, { locationAddress: '工地', serviceTime: ' ' }, { locationAddress: '工地', serviceTime: '时'.repeat(101) }]) {
    fillTask(h, { taskMode: 'PART_TIME', ...draft }); await h.state.getQuote(); assert.ok(h.state.createError.value)
  }
  fillTask(h, { taskMode: 'ONSITE', locationAddress: '小区', serviceTime: '时'.repeat(101) }); await h.state.getQuote(); assert.ok(h.state.createError.value)
  assert.equal(called(h, 'quoteCustomTask').length, 0)
  fillTask(h, { taskMode: 'PART_TIME', title: '搬水泥', locationAddress: ' 工地 A ', serviceTime: '时'.repeat(100) })
  await h.state.getQuote(); await h.state.publish()
  const body = called(h, 'createCustomTask')[0].args[0]
  assert.equal(body.taskMode, 'PART_TIME'); assert.equal(body.locationAddress, '工地 A'); assert.equal(body.serviceTime, '时'.repeat(100))
  assert.equal(Object.hasOwn(body, 'order'), false)
})

test('task yuan input produces bounded exact integer fen in both quote and create requests', async t => {
  const h = harness(t, 'Tasks'); await tick()
  for (const priceYuan of ['', '-0', '-1', '0', '0.00', '0.001', '0.009', '1.001', '1e2', '.5', '1.', 'Infinity', 'NaN', '1000000.01', '90071992547409.92']) {
    fillTask(h, { priceYuan }); await h.state.getQuote(); assert.ok(h.state.createError.value, priceYuan)
  }
  assert.equal(called(h, 'quoteCustomTask').length, 0)
  for (const [priceYuan, priceFen] of [['0.01', 1], ['0.29', 29], [' 001.20 ', 120], ['12.34', 1234], ['1000000.00', 100000000]]) {
    fillTask(h, { priceYuan }); await h.state.getQuote(); await h.state.publish()
    assert.deepEqual(called(h, 'quoteCustomTask').at(-1).args, [{ priceFen }])
    assert.equal(called(h, 'createCustomTask').at(-1).args[0].priceFen, priceFen)
    assert.ok(Number.isSafeInteger(called(h, 'createCustomTask').at(-1).args[0].priceFen))
  }
})

test('quote split is displayed and confirmed exactly as returned by the server, never locally calculated', async t => {
  const allocation = serverQuote(1234, { riderShareFen: 800, platformShareFen: 434 })
  const h = harness(t, 'Tasks', { overrides: { quoteCustomTask: async () => allocation } }); await tick(); fillTask(h)
  await h.state.getQuote(); assert.deepEqual(snapshot(h.state.quote.value), allocation)
  await h.state.publish()
  for (const text of ['线上任务', '12.34', '骑手预估收入 ¥8.00', '平台分成 ¥4.34']) assert.ok(h.confirmations[0][0].includes(text), text)
  for (const field of ['quote.totalFen', 'quote.riderShareFen', 'quote.platformShareFen', 'item.amountFen']) assert.ok(components.Tasks.template.includes(`formatFen(${field})`), field)
  const body = called(h, 'createCustomTask')[0].args[0]
  for (const key of ['riderShareFen', 'platformShareFen', 'priceYuan']) assert.equal(Object.hasOwn(body, key), false, key)
})

test('missing, fractional, negative, mismatched or inconsistent server quotes cannot enable creation', async t => {
  const h = harness(t, 'Tasks'); await tick(); fillTask(h)
  const invalid = [null, serverQuote(999), serverQuote(1234, { totalFen: '1234' }), serverQuote(1234, { riderShareFen: undefined }), serverQuote(1234, { platformShareFen: undefined }), serverQuote(1234, { riderShareFen: null }), serverQuote(1234, { platformShareFen: '0' }), serverQuote(1234, { riderShareFen: 1233.5, platformShareFen: 0.5 }), serverQuote(1234, { riderShareFen: 1235, platformShareFen: -1 }), serverQuote(1234, { riderShareFen: 800, platformShareFen: 400 }), serverQuote(1234, { items: null }), ...[null, { feeType: 'CUSTOM', label: '总价', amountFen: 1.1 }, { feeType: 'CUSTOM', label: '总价', amountFen: -1 }, { label: '总价', amountFen: 1234 }, { feeType: 'CUSTOM', amountFen: 1234 }].map(item => serverQuote(1234, { items: [item] }))]
  for (const data of invalid) {
    h.implementations.quoteCustomTask = async () => data
    await h.state.getQuote(); await h.state.publish()
    assert.equal(h.state.quote.value, null); assert.ok(h.state.createError.value)
  }
  assert.equal(h.confirmations.length, 0); assert.equal(called(h, 'createCustomTask').length, 0)
})

test('only publishing form edits invalidate a quote; filters, tags, tabs and detail reads do not', async t => {
  const h = harness(t, 'Tasks'); await tick(); fillTask(h); await h.state.getQuote()
  const quoted = h.state.quote.value
  h.state.tab.value = 'tags'; await h.state.loadTags(); h.state.editTag(); h.state.tagForm.name = '管理草稿'
  Object.assign(h.state.query, { status: 'WAIT_PAY', userId: '42' }); await h.state.load(2); await h.state.openDetail(21)
  assert.equal(h.state.quote.value, quoted); assert.equal(called(h, 'quoteCustomTask').length, 1)
  for (const [field, value] of [['userId', '43'], ['title', '修改标题'], ['content', '修改内容'], ['tagIds', []], ['priceYuan', '0.29'], ['contact', '李四'], ['phone', '13900000000'], ['taskMode', 'PART_TIME'], ['locationAddress', '新地点'], ['serviceTime', '明天上午']]) {
    fillTask(h, { taskMode: 'ONSITE', locationAddress: '原地点' }); await h.state.getQuote(); assert.ok(h.state.quote.value)
    h.state.form[field] = value; assert.equal(h.state.quote.value, null, field)
  }
  fillTask(h); await h.state.getQuote(); h.state.form.tagIds.splice(0, 1); assert.equal(h.state.quote.value, null)
})

test('switching scenarios clears online-only hidden fields, rejects stale quotes and revalidates labor time', async t => {
  const h = harness(t, 'Tasks'); await tick()
  fillTask(h, { taskMode: 'ONSITE', locationAddress: '服务地点', serviceTime: '周一' }); await h.state.getQuote()
  h.state.form.taskMode = 'ONLINE'
  assert.equal(h.state.quote.value, null); assert.equal(h.state.form.locationAddress, ''); assert.equal(h.state.form.serviceTime, '')
  await h.state.getQuote(); await h.state.publish()
  assert.equal(Object.hasOwn(called(h, 'createCustomTask')[0].args[0], 'locationAddress'), false)
  fillTask(h); await h.state.getQuote(); h.state.form.taskMode = 'PART_TIME'; h.state.form.locationAddress = '工地'
  await h.state.getQuote(); assert.equal(h.state.quote.value, null); assert.match(h.state.createError.value, /工作时间/)
  h.state.form.serviceTime = '明天 08:00–18:00'; await h.state.getQuote(); await h.state.publish()
  assert.equal(called(h, 'createCustomTask')[1].args[0].taskMode, 'PART_TIME')
  fillTask(h); const pending = deferred(); h.implementations.quoteCustomTask = () => pending.promise
  const quoting = h.state.getQuote(); h.state.form.taskMode = 'ONSITE'; h.state.form.taskMode = 'ONLINE'
  pending.resolve(serverQuote()); await quoting; assert.equal(h.state.quote.value, null)
})

test('editing price or scenario while confirming blocks publishing even if the edit is reverted', async t => {
  for (const field of ['priceYuan', 'taskMode', 'contact']) {
    const h = harness(t, 'Tasks'); await tick(); fillTask(h); await h.state.getQuote()
    const pending = deferred(); h.box.response = () => pending.promise
    const publishing = h.state.publish(), original = h.state.form[field]
    h.state.form[field] = field === 'priceYuan' ? '99.99' : field === 'taskMode' ? 'ONSITE' : '李四'
    h.state.form[field] = original; pending.resolve(true); await publishing
    assert.equal(called(h, 'createCustomTask').length, 0); assert.match(h.state.createError.value, /重新报价/)
  }
})

test('legacy delivery task detail preserves old addresses and uses amountFen only when priceFen is nullish', async t => {
  const h = harness(t, 'Tasks'); await tick()
  for (const taskMode of ['DELIVERY', undefined, null]) {
    const legacy = task({ taskMode, priceFen: null, amountFen: 29, fromAddress: '历史取件地址', toAddress: '历史送达地址' })
    h.implementations.customTask = async () => legacy; await h.state.openDetail(21)
    assert.equal(h.state.isLegacyTask(h.state.detail.value), true); assert.equal(h.state.taskModeLabel(taskMode), '配送任务（历史）')
    assert.equal(h.state.taskPriceFen(h.state.detail.value), 29); assert.equal(h.state.detail.value.fromAddress, '历史取件地址'); assert.equal(h.state.detail.value.toAddress, '历史送达地址')
  }
  assert.equal(h.state.taskPriceFen(task({ priceFen: 1, amountFen: 29 })), 1)
  assert.equal(h.state.taskPriceFen(task({ priceFen: 0, amountFen: 29 })), 0)
  for (const [taskMode, label] of [['ONLINE', '线上任务'], ['ONSITE', '上门服务'], ['PART_TIME', '兼职劳务']]) {
    const current = task({ taskMode, locationAddress: '新服务地点', serviceTime: '周一', fromAddress: 'ONLINE_PLACEHOLDER' })
    h.implementations.customTask = async () => current; await h.state.openDetail(21)
    assert.equal(h.state.isLegacyTask(h.state.detail.value), false); assert.equal(h.state.taskModeLabel(taskMode), label)
  }
  assert.match(components.Tasks.template, /v-if="isLegacyTask\(detail\)"/)
  assert.match(components.Tasks.template, /v-if="detail.taskMode !== 'ONLINE'"/)
  assert.match(components.Tasks.template, /formatFen\(taskPriceFen\(row\)\)/)
  assert.match(components.Tasks.template, /formatFen\(taskPriceFen\(detail\)\)/)
})

test('order list labels new custom scenarios without exposing online placeholders as pickup/delivery', async t => {
  const h = harness(t, 'OrderList'); await tick()
  const base = { orderType: 'CUSTOM', fromAddress: 'ONLINE_PLACEHOLDER', toAddress: 'ONLINE_PLACEHOLDER', fromContact: '旧寄件人', toContact: '旧收件人', contact: '任务联系人' }
  const online = { ...base, taskMode: 'ONLINE', locationAddress: '不应显示的地址', serviceTime: '不应显示的时间' }
  assert.equal(h.state.typeLabel(online.orderType), '自定义任务'); assert.equal(h.state.taskModeLabel(online.taskMode), '线上任务')
  assert.equal(h.state.orderLocation(online), '线上任务（无需地址）'); assert.equal(h.state.orderDestination(online), '—'); assert.equal(h.state.orderContact(online), '任务联系人')
  for (const [taskMode, label, placeLabel, timeLabel] of [['ONSITE', '上门服务', '服务地点', '服务时间'], ['PART_TIME', '兼职劳务', '工作地点', '工作时间']]) {
    const row = { ...base, taskMode, locationAddress: '工地', serviceTime: '周一' }
    assert.equal(h.state.taskModeLabel(taskMode), label); assert.equal(h.state.orderLocation(row), `${placeLabel}：工地`); assert.equal(h.state.orderDestination(row), `${timeLabel}：周一`)
  }
  assert.equal(h.state.orderLocation({ ...base, taskMode: 'ONSITE', fromAddress: '兼容服务地址' }), '服务地点：兼容服务地址')
  assert.equal(h.state.orderLocation({ ...base, taskMode: 'PART_TIME', fromAddress: '兼容工作地址' }), '工作地点：兼容工作地址')
  assert.equal(h.state.orderLocation({ ...base, taskMode: 'ONSITE', fromAddress: '' }), '服务地点：—')
  assert.equal(h.state.orderDestination({ ...base, taskMode: 'PART_TIME' }), '工作时间：—')
  for (const row of [{ ...base, taskMode: 'DELIVERY' }, { ...base }, { ...base, orderType: 'SEND', taskMode: 'ONLINE' }]) {
    assert.equal(h.state.isServiceTask(row), false); assert.equal(h.state.orderLocation(row), 'ONLINE_PLACEHOLDER'); assert.equal(h.state.orderDestination(row), 'ONLINE_PLACEHOLDER'); assert.equal(h.state.orderContact(row), '旧寄件人')
  }
  assert.match(components.OrderList.template, /v-if="isServiceTask\(row\)"/)
  assert.match(components.OrderList.template, /v-if="!isServiceTask\(row\) && row.toContact"/)
})

test('unknown creation outcomes require a fresh post-close list check, not a stale or failed refresh', async t => {
  const h = harness(t, 'Tasks'); await tick(); fillTask(h); await h.state.getQuote()
  const stale = deferred(); h.implementations.customTasks = () => stale.promise
  const oldLoad = h.state.load(1)
  h.implementations.createCustomTask = async () => { throw new Error('timeout') }; await h.state.publish()
  assert.equal(h.state.createNeedsCheck.value, true); h.state.createOpen.value = false
  stale.resolve(pageOf([])); await oldLoad; assert.equal(h.state.createNeedsCheck.value, true)
  h.state.openCreate(); await h.state.getQuote(); await h.state.publish(); assert.equal(called(h, 'createCustomTask').length, 1)
  h.implementations.customTasks = async () => { throw new Error('offline') }; await h.state.load(1)
  assert.equal(h.state.createNeedsCheck.value, true); h.state.openCreate(); assert.equal(h.state.createOpen.value, false)
  h.implementations.customTasks = async () => pageOf([task()]); await h.state.load(1)
  assert.equal(h.state.createNeedsCheck.value, false); h.state.openCreate(); assert.equal(h.state.createOpen.value, true)
})

test('malformed creation responses never claim success or permit a blind retry', async t => {
  const h = harness(t, 'Tasks', { overrides: { createCustomTask: async () => ({}) } }); await tick(); fillTask(h); await h.state.getQuote(); await h.state.publish()
  assert.equal(h.messages.length, 0); assert.equal(h.state.createNeedsCheck.value, true); assert.equal(h.state.quote.value, null)
  await h.state.getQuote(); await h.state.publish(); assert.equal(called(h, 'createCustomTask').length, 1)
})

test('task detail uses order ID rather than task ID, ignores closed and stale responses', async t => {
  const h = harness(t, 'Tasks'); await tick(); await h.state.openDetail(21)
  assert.deepEqual(called(h, 'customTask')[0].args, [21]); assert.equal(h.state.detail.value.id, 11)
  const pending = deferred(); h.implementations.customTask = () => pending.promise
  const loading = h.state.openDetail(22); h.state.closeDetail(); pending.resolve(task({ orderId: 22 })); await loading
  assert.equal(h.state.detail.value, null); assert.equal(h.state.detailOpen.value, false); assert.equal(h.state.detailLoading.value, false)
  h.implementations.customTask = async () => { throw new Error('404') }; await h.state.openDetail(21); assert.ok(h.state.detailError.value)
})

test('tags create/edit/status updates retain bool, int and version=0 without any delete', async t => {
  const h = harness(t, 'Tasks'); await tick(); h.state.editTag()
  Object.assign(h.state.tagForm, { name: ' 新标签 ', enabled: false, sort: 100000 }); await h.state.saveTag()
  assert.deepEqual(called(h, 'createTaskTag')[0].args, [{ name: '新标签', enabled: false, sort: 100000 }])
  h.state.editTag(tag()); h.state.tagForm.name = '已改'; await h.state.saveTag()
  assert.deepEqual(called(h, 'updateTaskTag')[0].args, [3, { name: '已改', enabled: true, sort: 2, version: 0 }])
  await h.state.toggleTag(tag()); assert.deepEqual(called(h, 'updateTaskTag')[1].args, [3, { name: '代办', enabled: false, sort: 2, version: 0 }])
  assert.match(h.confirmations[0][0], /不会删除已引用标签/)
  for (const draft of [{ name: '' }, { name: '字'.repeat(33) }, { name: '\u{1F600}'.repeat(33) }, { sort: 1.2 }, { sort: -1 }, { sort: 100001 }, { enabled: 1 }, { version: null }]) {
    h.state.editTag(tag(draft)); await h.state.saveTag(); assert.ok(h.state.tagError.value)
  }
  assert.equal(called(h, 'updateTaskTag').length, 2)
  h.state.editTag(); h.state.tagForm.name = '\u{1F600}'.repeat(32); await h.state.saveTag()
  assert.equal(called(h, 'createTaskTag').length, 2)
})

test('tag concurrency conflicts refresh list but block stale draft overwrite until explicit refresh', async t => {
  for (const error of [{ code: 409 }, { response: { status: 409 } }]) {
    const h = harness(t, 'Tasks'); await tick(); h.state.editTag(tag()); h.state.tagForm.name = '草稿'
    h.implementations.updateTaskTag = async () => { throw error }; h.implementations.taskTags = async () => [tag({ version: 1, name: '其他管理员' })]
    await h.state.saveTag(); assert.equal(h.state.tagConflict.value, true); assert.equal(h.state.tagForm.name, '草稿'); assert.equal(h.state.tags.value[0].version, 1)
    await h.state.saveTag(); assert.equal(called(h, 'updateTaskTag').length, 1)
    await h.state.reloadTagDraft(); assert.equal(h.state.tagConflict.value, false); assert.equal(h.state.tagForm.version, 1); assert.equal(h.state.tagForm.name, '其他管理员')
  }
})

test('tag duplicate operations are locked during confirmation and API execution', async t => {
  const h = harness(t, 'Tasks'); await tick(); const pending = deferred(); h.box.response = () => pending.promise
  const toggling = h.state.toggleTag(tag()); await h.state.toggleTag(tag()); await h.state.saveTag()
  assert.equal(h.confirmations.length, 1); pending.resolve(true); await toggling; assert.equal(called(h, 'updateTaskTag').length, 1)
})

test('complaint deep links, all statuses, pagination and resets preserve order filter correctly', async t => {
  const h = harness(t, 'Complaints', { query: { orderId: '21', keep: 'yes' } }); await tick()
  assert.deepEqual(called(h, 'complaints')[0].args, [{ orderId: '21', page: 0 }])
  assert.deepEqual(snapshot(h.state.statuses.map(s => s.code)), ['OPEN', 'PROCESSING', 'APPROVED', 'REJECTED', 'DONE'])
  h.state.query.status = 'PROCESSING'; await h.state.load(2)
  assert.deepEqual(called(h, 'complaints')[1].args, [{ status: 'PROCESSING', orderId: '21', page: 1 }]); assert.equal(h.state.page.value, 2)
  h.route.query.orderId = '22'; await tick(); assert.deepEqual(called(h, 'complaints')[2].args, [{ status: 'PROCESSING', orderId: '22', page: 0 }])
  await h.state.resetFilters(); await tick(); assert.deepEqual(snapshot(h.route.query), { keep: 'yes' }); assert.deepEqual(called(h, 'complaints')[3].args, [{ page: 0 }])
  assert.match(components.Complaints.template, /:page-size="15"/)
})

test('invalid complaint deep links and missing grants never silently query every order', async t => {
  for (const orderId of ['', '0', '-1', 'text', ['21', '22']]) {
    const h = harness(t, 'Complaints', { query: { orderId } }); await tick(); assert.equal(called(h, 'complaints').length, 0); assert.ok(h.state.listError.value); assert.equal(h.state.loading.value, false)
  }
  const h = harness(t, 'Complaints', { menus: [] }); await tick(); h.state.openDetail(complaint()); await h.state.submitReview(); assert.equal(h.calls.length, 0)
  h.menuCodes.value = ['complaints']; await tick(); assert.equal(called(h, 'complaints').length, 1)
})

test('complaint detail preserves review history and exact account balances', async t => {
  const h = harness(t, 'Complaints'); await tick(); h.state.openDetail(h.state.rows.value[0])
  assert.equal(h.state.detail.value.orderDeductedFen, 50); assert.equal(h.state.detail.value.maxDeductibleFen, 1234); assert.equal(h.state.detail.value.actions[0].operatorId, 42)
  for (const field of ['orderNo', 'riderId', 'description', 'actions', 'deductedFen', 'maxDeductibleFen', 'handledBy']) assert.ok(components.Complaints.template.includes(field), field)
  assert.match(components.Complaints.template, /MOCK/)
})

test('approved review converts yuan to exact integer fen, confirms accounts/limit/MOCK and blocks duplicates', async t => {
  const h = harness(t, 'Complaints'); await tick(); h.state.openDetail(h.state.rows.value[0])
  Object.assign(h.state.reviewForm, { decision: 'APPROVED', reason: '  已核实  ', amountYuan: '12.34' })
  const pending = deferred(); h.box.response = () => pending.promise
  const reviewing = h.state.submitReview(); await h.state.submitReview()
  assert.equal(h.confirmations.length, 1); assert.equal(called(h, 'reviewComplaint').length, 0)
  for (const text of ['12.34', '骑手 ID 9', '发布者 ID 42', '上限', 'MOCK', '不会真实转账']) assert.ok(h.confirmations[0][0].includes(text), text)
  pending.resolve(true); await reviewing
  assert.deepEqual(called(h, 'reviewComplaint')[0].args, [8, { decision: 'APPROVED', reason: '已核实', amountFen: 1234 }])
  assert.equal(h.state.detailOpen.value, false); assert.match(h.messages[0], /未真实转账/)
  h.state.openDetail(complaint()); await h.state.submitReview(); assert.equal(called(h, 'reviewComplaint').length, 1)
})

test('review validates nonnegative cents, upper limit, reason and missing account data before confirmation', async t => {
  const h = harness(t, 'Complaints'); await tick()
  for (const amountYuan of ['-1', '1.001', '0.009', '12.35', '1e2', '', '90071992547409.92']) {
    h.state.openDetail(complaint()); Object.assign(h.state.reviewForm, { decision: 'APPROVED', reason: '已核实', amountYuan }); await h.state.submitReview(); assert.ok(h.state.reviewError.value, amountYuan)
  }
  for (const row of [complaint({ maxDeductibleFen: null }), complaint({ maxDeductibleFen: 1.1 }), complaint({ riderId: null }), complaint({ userId: null })]) {
    h.state.openDetail(row); Object.assign(h.state.reviewForm, { decision: 'APPROVED', reason: '核实', amountYuan: '0.01' }); await h.state.submitReview(); assert.ok(h.state.reviewError.value)
  }
  h.state.openDetail(complaint()); h.state.reviewForm.reason = ' '; await h.state.submitReview(); assert.ok(h.state.reviewError.value)
  assert.equal(h.confirmations.length, 0); assert.equal(called(h, 'reviewComplaint').length, 0)
})

test('processing/rejection use amountFen=0; terminal states and repeated processing are not reviewable', async t => {
  for (const decision of ['PROCESSING', 'REJECTED']) {
    const h = harness(t, 'Complaints'); await tick(); h.state.openDetail(complaint()); Object.assign(h.state.reviewForm, { decision, reason: '核实', amountYuan: '999.99' })
    await h.state.submitReview(); assert.deepEqual(called(h, 'reviewComplaint')[0].args, [8, { decision, reason: '核实', amountFen: 0 }])
  }
  const h = harness(t, 'Complaints'); await tick()
  for (const status of ['APPROVED', 'REJECTED', 'DONE', 'UNKNOWN']) { h.state.openDetail(complaint({ status })); assert.equal(h.state.isReviewable(h.state.detail.value), false); await h.state.submitReview() }
  h.state.openDetail(complaint({ status: 'PROCESSING' })); Object.assign(h.state.reviewForm, { decision: 'PROCESSING', reason: '再次受理' }); await h.state.submitReview()
  assert.equal(called(h, 'reviewComplaint').length, 0); assert.ok(h.state.reviewError.value)
})

test('cancelled reviews preserve drafts; uncertain failures block retries until server refresh', async t => {
  const h = harness(t, 'Complaints'); await tick(); h.state.openDetail(complaint()); Object.assign(h.state.reviewForm, { decision: 'APPROVED', reason: '核实', amountYuan: '0.29' })
  h.box.response = async () => { throw 'cancel' }; await h.state.submitReview(); assert.equal(called(h, 'reviewComplaint').length, 0); assert.equal(h.state.reviewForm.amountYuan, '0.29')
  h.box.response = async () => true; h.implementations.reviewComplaint = async () => { throw { code: 409 } }
  await h.state.submitReview(); assert.equal(h.state.needsRefresh.value, true); assert.equal(h.messages.length, 0)
  await h.state.submitReview(); assert.equal(called(h, 'reviewComplaint').length, 1)
  h.state.detailOpen.value = false; h.state.openDetail(complaint()); h.state.reviewForm.reason = '重试'
  assert.equal(h.state.needsRefresh.value, true); await h.state.submitReview(); assert.equal(called(h, 'reviewComplaint').length, 1)
  h.implementations.complaints = async () => pageOf([complaint({ status: 'APPROVED', maxDeductibleFen: 1205, compensationFen: 29, deductedFen: 29 })])
  await h.state.refreshDetail(); assert.equal(h.state.detail.value.status, 'APPROVED'); await h.state.submitReview(); assert.equal(called(h, 'reviewComplaint').length, 1)
})

test('permission revocation and unmount while confirming prevent complaint mutations', async t => {
  for (const action of ['revoke', 'unmount']) {
    const h = harness(t, 'Complaints'); await tick(); h.state.openDetail(complaint()); h.state.reviewForm.reason = '核实'
    const pending = deferred(); h.box.response = () => pending.promise
    const reviewing = h.state.submitReview(); if (action === 'revoke') h.menuCodes.value = []; else h.unmount()
    pending.resolve(true); await reviewing; assert.equal(called(h, 'reviewComplaint').length, 0); assert.equal(h.messages.length, 0)
  }
})

test('list errors clear stale rows and recover; stale responses cannot replace newer filters', async t => {
  for (const [name, method] of [['Tasks', 'customTasks'], ['Complaints', 'complaints']]) {
    const h = harness(t, name); await tick(); const normal = h.implementations[method]
    h.implementations[method] = async () => { throw new Error('network') }; await h.state.load(); assert.equal(h.state.rows.value.length, 0); assert.ok(h.state.listError.value); assert.equal(h.state.loading.value, false)
    h.implementations[method] = normal; await h.state.load(); assert.equal(h.state.listError.value, '')
    const pending = deferred(); h.implementations[method] = () => pending.promise; const old = h.state.load(1)
    h.implementations[method] = async () => pageOf([name === 'Tasks' ? task({ id: 99 }) : complaint({ id: 99 })], 1)
    await h.state.load(2); pending.resolve(pageOf([])); await old; assert.equal(h.state.rows.value[0].id, 99); assert.equal(h.state.page.value, 2)
  }
})

test('orders and settlements complaint links react to live grants; custom type label is added', async t => {
  const order = harness(t, 'OrderList', { menus: [] }), settlement = harness(t, 'Settlement', { menus: [] }); await tick(); await settlement.mount()
  order.state.viewComplaints({ id: 21 }); settlement.state.viewComplaints(); assert.equal(order.navigations.length + settlement.navigations.length, 0)
  order.menuCodes.value = ['complaints']; settlement.menuCodes.value = ['complaints']
  order.state.viewComplaints({ id: 21 }); settlement.state.viewComplaints()
  assert.deepEqual(order.navigations[0], { path: '/complaints', query: { orderId: '21' } }); assert.equal(settlement.navigations[0], '/complaints')
  assert.equal(order.state.typeLabel('CUSTOM'), '自定义任务'); assert.equal(order.state.typeLabel('SEND'), '跑腿送')
  assert.match(components.OrderList.template, /v-if="canViewComplaints"/)
  assert.equal(settlement.state.grossYuan({ totalShareFen: 29, totalShareYuan: 'wrong' }), '0.29'); assert.equal(settlement.state.grossYuan({ totalShareYuan: '12.34' }), '12.34')
  assert.doesNotMatch(components.Settlement.template, /el-tab-pane/)
  for (const field of ['totalShareFen', 'deductedFen', 'netShareFen', 'compensationFen']) assert.ok(components.Settlement.template.includes(field), field)
  assert.match(components.Settlement.template, /MOCK/); assert.equal(called(settlement, 'complaints').length, 0)
})

test('layout registers new icons and authorized menus without granting ordinary roles extra access', async t => {
  const ordinary = harness(t, 'Layout', { overrides: { profile: async () => ({ menus: [{ code: 'tasks', path: '/tasks', name: '自定义任务' }], roles: [] }) } }); await tick()
  assert.deepEqual(snapshot(ordinary.provided.adminMenuCodes.value), ['tasks']); assert.equal(ordinary.state.iconFor('tasks'), 'orders'); assert.equal(ordinary.state.iconFor('complaints'), 'shield')
  const superAdmin = harness(t, 'Layout', { overrides: { profile: async () => ({ menus: [], roles: ['SUPER_ADMIN'] }) } }); await tick()
  assert.deepEqual(snapshot(superAdmin.provided.adminMenuCodes.value), ['tasks', 'complaints', 'wallets', 'withdrawals', 'wechat-settings'])
  const failed = harness(t, 'Layout', { overrides: { profile: async () => { throw new Error('403') } } }); await tick(); assert.deepEqual(snapshot(failed.provided.adminMenuCodes.value), [])
  const router = readFileSync(path.join(root, 'src/router/index.js'), 'utf8')
  assert.match(router, /path: 'tasks'.*title: '自定义任务', menu: 'tasks'/); assert.match(router, /path: 'complaints'.*title: '投诉审核', menu: 'complaints'/)
})
