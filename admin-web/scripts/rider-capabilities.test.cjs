// Offline regression tests execute the real RiderList setup and compile its template.
// API and Element Plus prompts are doubled; no database or live HTTP requests are made.
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
const rider = (overrides = {}) => ({ riderId: 9, name: '骑手甲', creditScore: 110, onlineStatus: 1, status: 'ACTIVE', rideEnabled: false, drivingEnabled: false, ...overrides })
function replaceImports(source) {
  const imports = babelParse(source, { sourceType: 'module' }).program.body.filter(node => node.type === 'ImportDeclaration')
  for (const node of imports.reverse()) {
    const declarations = node.specifiers.map(specifier => `const ${specifier.local.name} = __imports[${JSON.stringify(node.source.value)}][${JSON.stringify(specifier.type === 'ImportDefaultSpecifier' ? 'default' : specifier.imported.name)}];`).join('\n')
    source = source.slice(0, node.start) + declarations + source.slice(node.end)
  }
  return source
}
const filename = path.join(root, 'src/views/RiderList.vue')
const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename })
assert.equal(errors.length, 0)
const compiled = compileScript(descriptor, { id: 'RiderList', genDefaultAs: '__component' })
const template = compileTemplate({ source: descriptor.template.content, filename, id: 'RiderList', compilerOptions: { bindingMetadata: compiled.bindings } })
assert.equal(template.errors.length, 0)
const script = new vm.Script(replaceImports(compiled.content) + '\n__component', { filename })
function harness(t, { menus = ['riders'], provideMenus = true, overrides = {} } = {}) {
  const menuCodes = vue.ref(menus), calls = [], confirmations = [], messages = [], stops = []
  let beforeUnmount = () => {}
  const implementations = {
    riders: async () => [rider(), rider({ riderId: 10, name: '骑手乙' })],
    updateRiderCapability: async (id, mode, body) => rider({ riderId: id, [mode === 'RIDE' ? 'rideEnabled' : 'drivingEnabled']: body.enabled }),
    ...overrides
  }
  const api = Object.fromEntries(Object.keys(implementations).map(method => [method, async (...args) => {
    calls.push({ method, args: snapshot(args) }); return implementations[method](...args)
  }]))
  const box = { response: async () => ({ value: '  核实平台授权  ' }), async prompt(...args) { confirmations.push(args); return box.response() } }
  const component = script.runInNewContext({ __imports: {
    vue: { ...vue, inject: (key, fallback) => provideMenus ? menuCodes : fallback, onBeforeUnmount: callback => { beforeUnmount = callback }, watch(...args) { const stop = vue.watch(...args); stops.push(stop); return stop } },
    'element-plus': { ElMessage: { success: text => messages.push(text) }, ElMessageBox: box }, '../api': { adminApi: api }
  }, localStorage: { getItem: () => '["riders"]' } })
  const state = component.setup({}, { expose() {} })
  let unmounted = false
  const unmount = () => { if (!unmounted) { unmounted = true; beforeUnmount(); stops.forEach(stop => stop()) } }
  t.after(unmount)
  return { state, menuCodes, calls, confirmations, messages, box, implementations, unmount }
}
const called = (h, method) => h.calls.filter(call => call.method === method)

test('capability API uses safe riderId endpoints and only the three agreed body fields', () => {
  const source = replaceImports(readFileSync(path.join(root, 'src/api/index.js'), 'utf8')).replace('export const adminApi', 'const adminApi')
  const calls = [], request = Object.fromEntries(['get', 'post', 'put', 'delete'].map(method => [method, (...args) => calls.push({ method, args: snapshot(args) })]))
  const api = new vm.Script(source + '\nadminApi').runInNewContext({ __imports: { './request': { default: request } } })
  api.riders()
  api.updateRiderCapability(9, 'RIDE', { enabled: true, expectedEnabled: false, reason: '授权接送', role: 'ADMIN', status: 'ACTIVE', creditScore: 999 })
  api.updateRiderCapability(9, 'DRIVING', { enabled: false, expectedEnabled: true, reason: '撤销代驾' })
  assert.deepEqual(calls, [
    { method: 'get', args: ['/v1/admin/riders'] },
    { method: 'put', args: ['/v1/admin/riders/9/capabilities/RIDE', { enabled: true, expectedEnabled: false, reason: '授权接送' }] },
    { method: 'put', args: ['/v1/admin/riders/9/capabilities/DRIVING', { enabled: false, expectedEnabled: true, reason: '撤销代驾' }] }
  ])
})

test('riders fail closed without live injected grants, ignoring cached permission', async t => {
  for (const provideMenus of [true, false]) {
    const h = harness(t, { menus: [], provideMenus }); await tick()
    await h.state.load(); await h.state.changeCapability(rider(), 'RIDE', true)
    assert.equal(h.calls.length, 0); assert.equal(h.confirmations.length, 0)
    assert.equal(h.state.canManage.value, false)
  }
  const h = harness(t, { menus: [] }); h.menuCodes.value = ['riders']; await tick()
  assert.equal(called(h, 'riders').length, 1); assert.equal(h.state.rows.value.length, 2)
  h.menuCodes.value = []; assert.equal(h.state.rows.value.length, 0)
  await h.state.load(); assert.equal(called(h, 'riders').length, 1)
})

test('all four independent capability combinations remain explicit booleans', async t => {
  for (const rideEnabled of [false, true]) for (const drivingEnabled of [false, true]) {
    const h = harness(t, { overrides: { riders: async () => [rider({ rideEnabled, drivingEnabled })] } }); await tick()
    assert.equal(h.state.rows.value[0].rideEnabled, rideEnabled); assert.equal(h.state.rows.value[0].drivingEnabled, drivingEnabled)
  }
  assert.match(descriptor.template.content, /:model-value="row\[mode.field\] === true"/)
  assert.doesNotMatch(descriptor.template.content, /v-model=/)
  assert.match(descriptor.template.content, /不代表驾驶资质审核/)
  assert.match(descriptor.template.content, /pendingIds.has\(row.riderId\)/)
})

test('confirmation locks the whole rider row while other riders may proceed, with no optimistic success', async t => {
  const h = harness(t); await tick()
  const row = h.state.rows.value[0], gate = deferred()
  h.box.response = () => gate.promise
  const changing = h.state.changeCapability(row, 'RIDE', true)
  await h.state.changeCapability(row, 'RIDE', true); await h.state.changeCapability(row, 'DRIVING', true)
  assert.equal(h.confirmations.length, 1); assert.equal(called(h, 'updateRiderCapability').length, 0)
  assert.equal(row.rideEnabled, false); assert.equal(h.state.pendingIds.has(9), true)
  h.box.response = async () => ({ value: '独立授权代驾' })
  await h.state.changeCapability(h.state.rows.value[1], 'DRIVING', true)
  assert.equal(called(h, 'updateRiderCapability').length, 1)
  const apiGate = deferred(); h.implementations.updateRiderCapability = () => apiGate.promise
  gate.resolve({ value: '  授权接送  ' }); await tick()
  assert.deepEqual(called(h, 'updateRiderCapability')[1].args, [9, 'RIDE', { enabled: true, expectedEnabled: false, reason: '授权接送' }])
  assert.equal(h.state.rows.value[0].rideEnabled, false); assert.equal(h.messages.length, 1)
  await h.state.changeCapability(row, 'DRIVING', true); assert.equal(h.confirmations.length, 2)
  apiGate.resolve(rider({ rideEnabled: true, drivingEnabled: true, creditScore: 99, onlineStatus: 0, status: 'FROZEN' })); await changing
  assert.deepEqual(snapshot(h.state.rows.value[0]), rider({ rideEnabled: true, drivingEnabled: true, creditScore: 99, onlineStatus: 0, status: 'FROZEN' }))
  assert.equal(h.state.pendingIds.has(9), false); assert.equal(h.state.needsRefresh.has(9), false)
  h.implementations.updateRiderCapability = async () => rider({ rideEnabled: true, drivingEnabled: false })
  await h.state.changeCapability(h.state.rows.value[0], 'DRIVING', false)
  assert.deepEqual(called(h, 'updateRiderCapability').at(-1).args, [9, 'DRIVING', { enabled: false, expectedEnabled: true, reason: '独立授权代驾' }])
})

test('cancelled prompts and invalid reasons including the 160 character boundary never send invalid writes', async t => {
  const h = harness(t); await tick()
  for (const error of ['cancel', 'close']) {
    h.box.response = async () => { throw error }; await h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
  }
  for (const value of ['', '   ', '字'.repeat(161), '\u{1F600}'.repeat(81), '\u{1F600}'.repeat(160), null, 123]) {
    h.box.response = async () => ({ value }); await h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
  }
  assert.equal(called(h, 'updateRiderCapability').length, 0); assert.equal(h.messages.length, 0)
  const validator = h.confirmations[0][2].inputValidator
  assert.notEqual(validator(' '), true); assert.notEqual(validator('字'.repeat(161)), true); assert.equal(validator('字'.repeat(160)), true)
  assert.notEqual(validator('\u{1F600}'.repeat(81)), true); assert.equal(validator('\u{1F600}'.repeat(80)), true)
  h.box.response = async () => ({ value: ' ' + '字'.repeat(160) + ' ' })
  await h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
  assert.equal(called(h, 'updateRiderCapability')[0].args[2].reason.length, 160)
  h.box.response = async () => ({ value: '\u{1F600}'.repeat(80) })
  await h.state.changeCapability(h.state.rows.value[0], 'RIDE', false)
  assert.equal(called(h, 'updateRiderCapability')[1].args[2].reason.length, 160)
  assert.equal(h.state.pendingIds.size, 0)
})

test('malformed rows, stale row callbacks, unknown modes and nonboolean targets cannot mutate capabilities', async t => {
  const h = harness(t); await tick()
  for (const [row, code, enabled] of [[rider(), 'RIDE', true], [h.state.rows.value[0], 'RIDE', false], [h.state.rows.value[0], 'INVALID', true], [h.state.rows.value[0], 'RIDE', 1], [null, 'RIDE', true]]) await h.state.changeCapability(row, code, enabled)
  h.state.rows.value = [rider({ rideEnabled: undefined })]
  await h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
  assert.equal(h.confirmations.length, 0); assert.equal(called(h, 'updateRiderCapability').length, 0)
})

test('network, denied and malformed writes never fake success and block blind retries until a fresh list', async t => {
  for (const response of [undefined, {}, rider({ riderId: 10, rideEnabled: true }), rider({ rideEnabled: true, drivingEnabled: undefined }), rider({ rideEnabled: false }), rider({ rideEnabled: 1 })]) {
    const h = harness(t, { overrides: { updateRiderCapability: async () => response } }); await tick()
    await h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
    assert.equal(h.state.rows.value[0].rideEnabled, false); assert.equal(h.messages.length, 0); assert.equal(h.state.needsRefresh.has(9), true)
  }
  for (const error of [new Error('timeout'), { response: { status: 403 } }, { code: 500 }]) {
    const h = harness(t, { overrides: { updateRiderCapability: async () => { throw error } } }); await tick()
    await h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
    await h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
    assert.equal(called(h, 'updateRiderCapability').length, 1); assert.equal(h.messages.length, 0); assert.ok(h.state.rowErrors[9])
    h.implementations.riders = async () => { throw new Error('offline') }; await h.state.load()
    assert.equal(h.state.needsRefresh.has(9), true); assert.ok(h.state.listError.value)
    h.implementations.riders = async () => [rider({ rideEnabled: true })]; await h.state.load()
    assert.equal(h.state.needsRefresh.has(9), false); assert.equal(h.state.rows.value[0].rideEnabled, true)
  }
})

test('409 business and HTTP conflicts refresh authoritative rows without claiming success or retrying', async t => {
  for (const error of [{ code: 409 }, { response: { status: 409 } }]) {
    const h = harness(t, { overrides: { updateRiderCapability: async () => { throw error } } }); await tick()
    h.implementations.riders = async () => [rider({ rideEnabled: true, drivingEnabled: true })]
    await h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
    assert.equal(called(h, 'riders').length, 2); assert.equal(called(h, 'updateRiderCapability').length, 1)
    assert.equal(h.state.rows.value[0].rideEnabled, true); assert.match(h.state.rowErrors[9], /409/)
    assert.equal(h.state.needsRefresh.has(9), false); assert.equal(h.messages.length, 0)
  }
  const h = harness(t, { overrides: { updateRiderCapability: async () => { throw { code: 409 } } } }); await tick()
  h.implementations.riders = async () => { throw new Error('offline') }
  await h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
  assert.equal(h.state.needsRefresh.has(9), true); assert.match(h.state.rowErrors[9], /刷新失败/)
})

test('permission is rechecked after prompts and writes, including revoke/regrant before callbacks', async t => {
  for (const phase of ['prompt', 'write']) for (const action of ['revoke', 'regrant', 'unmount']) {
    const h = harness(t); await tick(); const gate = deferred()
    if (phase === 'prompt') h.box.response = () => gate.promise
    else h.implementations.updateRiderCapability = () => gate.promise
    const changing = h.state.changeCapability(h.state.rows.value[0], 'RIDE', true); await tick()
    if (action === 'unmount') h.unmount()
    else { h.menuCodes.value = []; if (action === 'regrant') h.menuCodes.value = ['riders'] }
    gate.resolve(phase === 'prompt' ? { value: '已核实' } : rider({ rideEnabled: true })); await changing
    assert.equal(called(h, 'updateRiderCapability').length, phase === 'prompt' ? 0 : 1)
    assert.equal(h.messages.length, 0); assert.equal(h.state.pendingIds.size, 0)
    if (action === 'revoke') assert.equal(h.state.rows.value.length, 0)
    if (action === 'regrant') assert.equal(h.state.rows.value[0].rideEnabled, false)
  }
})

test('list refreshes reject late, malformed and revoked responses and recover from errors', async t => {
  const h = harness(t); await tick()
  const delayed = deferred(); h.implementations.riders = () => delayed.promise
  const old = h.state.load()
  h.implementations.riders = async () => [rider({ name: '最新结果' })]; await h.state.load()
  delayed.resolve([rider({ name: '过期结果' })]); await old
  assert.equal(h.state.rows.value[0].name, '最新结果')
  for (const response of [{}, [null], [{ id: 9 }]]) {
    h.implementations.riders = async () => response; await h.state.load()
    assert.equal(h.state.rows.value.length, 0); assert.ok(h.state.listError.value); assert.equal(h.state.loading.value, false)
  }
  const revoked = deferred(); h.implementations.riders = () => revoked.promise
  const pending = h.state.load(); h.menuCodes.value = []
  revoked.resolve([rider()]); await pending
  assert.equal(h.state.rows.value.length, 0); assert.equal(h.state.loading.value, false)
})

test('a refresh started before an uncertain write cannot unlock a blind retry', async t => {
  const h = harness(t); await tick()
  const prompt = deferred(), staleList = deferred(), write = deferred()
  h.box.response = () => prompt.promise; h.implementations.updateRiderCapability = () => write.promise
  const saving = h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
  h.implementations.riders = () => staleList.promise; const oldRefresh = h.state.load()
  prompt.resolve({ value: '授权' }); await tick(); write.resolve({}); await saving
  assert.equal(h.state.needsRefresh.has(9), true)
  staleList.resolve([rider()]); await oldRefresh
  assert.equal(h.state.needsRefresh.has(9), true)
  await h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
  assert.equal(called(h, 'updateRiderCapability').length, 1)
  h.implementations.riders = async () => [rider({ rideEnabled: true })]; await h.state.load()
  assert.equal(h.state.needsRefresh.has(9), false); assert.equal(h.state.rows.value[0].rideEnabled, true)
})

test('authorization changes while confirming and pre-write list refreshes cannot overwrite newer state', async t => {
  const h = harness(t); await tick(); const gate = deferred(); h.box.response = () => gate.promise
  const changing = h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
  h.implementations.riders = async () => [rider({ rideEnabled: true })]; await h.state.load()
  gate.resolve({ value: '授权' }); await changing
  assert.equal(called(h, 'updateRiderCapability').length, 0); assert.equal(h.messages.length, 0)
  h.implementations.riders = async () => [rider()]; await h.state.load()
  const prompt = deferred(), staleList = deferred(); h.box.response = () => prompt.promise
  const saving = h.state.changeCapability(h.state.rows.value[0], 'RIDE', true)
  h.implementations.riders = () => staleList.promise; const refresh = h.state.load()
  prompt.resolve({ value: '授权' }); await saving
  staleList.resolve([rider()]); await refresh
  assert.equal(h.state.rows.value[0].rideEnabled, true); assert.equal(h.state.loading.value, false)
})
