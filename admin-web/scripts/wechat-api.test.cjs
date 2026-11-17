// Execute the real API wrapper with an offline request spy, not a reimplementation.
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')
const { babelParse } = require('@vue/compiler-sfc')
const filename = path.resolve(__dirname, '../src/api/index.js')
const source = readFileSync(filename, 'utf8')
const ast = babelParse(source, { sourceType: 'module' })
let executable = source
for (const node of [...ast.program.body].reverse()) {
  if (node.type === 'ImportDeclaration') {
    assert.equal(node.source.value, './request')
    executable = executable.slice(0, node.start) + 'const request = __request;' + executable.slice(node.end)
  } else if (node.type === 'ExportNamedDeclaration') {
    executable = executable.slice(0, node.start) + executable.slice(node.declaration.start)
  }
}
const script = new vm.Script(executable + '\nadminApi', { filename })
const snapshot = value => JSON.parse(JSON.stringify(value))
function harness() {
  const calls = []
  const request = Object.fromEntries(['get', 'post', 'put', 'delete'].map(method => [method, (...args) => {
    calls.push({ method, args: snapshot(args) })
    return Promise.resolve({ offline: true })
  }]))
  return { api: script.runInNewContext({ __request: request }), calls }
}

test('legacy USER alias, explicit USER and RIDER use exact independent endpoints and CAS payload', async () => {
  const { api, calls } = harness()
  const body = { enabled: false, appId: 'wx0123456789abcdef', appSecret: '', clearAppSecret: true, version: 7 }
  await api.saveWechatLogin(body)
  await api.saveWechatLogin(body, 'USER')
  await api.saveWechatLogin({ ...body, version: 11 }, 'RIDER')
  assert.deepEqual(calls, [
    { method: 'put', args: ['/v1/admin/system/wechat/login', body, { silentError: true }] },
    { method: 'put', args: ['/v1/admin/system/wechat/login/USER', body, { silentError: true }] },
    { method: 'put', args: ['/v1/admin/system/wechat/login/RIDER', { ...body, version: 11 }, { silentError: true }] }
  ])
})

test('validate sends only section/clientType; legacy default and payment remain compatible', async () => {
  const { api, calls } = harness()
  await api.validateWechat('LOGIN')
  await api.validateWechat('LOGIN', 'USER')
  await api.validateWechat('LOGIN', 'RIDER')
  await api.validateWechat('PAYMENT')
  assert.deepEqual(calls.map(call => call.args), [
    ['/v1/admin/system/wechat/validate', { section: 'LOGIN' }, { silentError: true }],
    ['/v1/admin/system/wechat/validate', { section: 'LOGIN', clientType: 'USER' }, { silentError: true }],
    ['/v1/admin/system/wechat/validate', { section: 'LOGIN', clientType: 'RIDER' }, { silentError: true }],
    ['/v1/admin/system/wechat/validate', { section: 'PAYMENT' }, { silentError: true }]
  ])
})

test('unknown/null/blank/wrong-case/path clients and irrelevant payment clients fail before HTTP', () => {
  const { api, calls } = harness()
  for (const client of [null, '', 'user', 'rider', 'ADMIN', 'USER/RIDER', '../USER', 0, {}, ' USER ']) {
    assert.throws(() => api.saveWechatLogin({}, client), /USER或RIDER/)
    assert.throws(() => api.validateWechat('LOGIN', client), /USER或RIDER/)
    assert.throws(() => api.validateWechat('PAYMENT', client), /不能指定/)
  }
  for (const client of ['USER', 'RIDER']) assert.throws(() => api.validateWechat('PAYMENT', client), /不能指定/)
  for (const section of ['LOGIN_RIDER', 'UNKNOWN', 'login', null]) assert.throws(() => api.validateWechat(section), /LOGIN或PAYMENT/)
  assert.equal(calls.length, 0)
})

test('overview/payment keep no-toast transport and unrelated rider/dashboard additions remain intact', async () => {
  const { api, calls } = harness()
  await api.wechatSettings()
  const payment = { enabled: false, version: 4, apiV3Key: '', clearApiV3Key: false }
  await api.saveWechatPayment(payment)
  await api.updateRiderCapability(42, 'RIDE', { enabled: true, expectedEnabled: false, reason: 'offline-regression' })
  await api.stats()
  assert.deepEqual(calls, [
    { method: 'get', args: ['/v1/admin/system/wechat', { silentError: true }] },
    { method: 'put', args: ['/v1/admin/system/wechat/payment', payment, { silentError: true }] },
    { method: 'put', args: ['/v1/admin/riders/42/capabilities/RIDE', { enabled: true, expectedEnabled: false, reason: 'offline-regression' }] },
    { method: 'get', args: ['/v1/admin/stats'] }
  ])
})
