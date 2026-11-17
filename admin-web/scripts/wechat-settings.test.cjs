// Offline unit regression checks for the real WechatSettings.vue setup script.
// Dependencies: Node built-ins and the already installed @vue/compiler-sfc only.
// Vue primitives/lifecycle, Element Plus and adminApi are explicit test doubles;
// this is not a browser/Element Plus integration test. No HTTP, DB or real secrets.
const assert = require('node:assert/strict')
const { webcrypto } = require('node:crypto')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')
const { babelParse, compileScript, compileTemplate, parse } = require('@vue/compiler-sfc')

const filename = path.resolve(__dirname, '../src/views/WechatSettings.vue')
const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename })
assert.equal(errors.length, 0, 'Vue SFC must parse')
const compiled = compileScript(descriptor, { id: 'wechat-settings-test', genDefaultAs: '__component' })
const template = compileTemplate({ source: descriptor.template.content, filename, id: 'wechat-settings-test', compilerOptions: { bindingMetadata: compiled.bindings } })
assert.equal(template.errors.length, 0, 'Vue template must compile')

// Replace static imports using the compiler parser, not regexes or another tool.
// The rest of the compiled production script runs unchanged inside a VM with no
// require, process, filesystem, storage, console or network APIs exposed to it.
let executable = compiled.content
const imports = babelParse(executable, { sourceType: 'module' }).program.body.filter(node => node.type === 'ImportDeclaration')
for (const node of imports.reverse()) {
  const source = JSON.stringify(node.source.value)
  const declarations = node.specifiers.map(specifier => {
    const imported = specifier.type === 'ImportDefaultSpecifier' ? 'default' : specifier.imported?.name
    assert.ok(imported, 'Only default/named imports are expected in this component')
    return `const ${specifier.local.name} = __imports[${source}][${JSON.stringify(imported)}];`
  }).join('\n')
  executable = executable.slice(0, node.start) + declarations + executable.slice(node.end)
}
const componentScript = new vm.Script(executable + '\n__component', { filename })
const sections = ['LOGIN', 'LOGIN_RIDER', 'PAYMENT']
const keys = {
  LOGIN: [['appSecret', 'clearAppSecret']],
  LOGIN_RIDER: [['appSecret', 'clearAppSecret']],
  PAYMENT: [['apiV3Key', 'clearApiV3Key'], ['merchantPrivateKey', 'clearMerchantPrivateKey'], ['platformPublicKey', 'clearPlatformPublicKey']]
}
const mockSecret = 'a'.repeat(32)
const snapshot = value => JSON.parse(JSON.stringify(value))
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
const tick = () => new Promise(resolve => setImmediate(resolve))
const formFor = (state, section) => state.formFor(section)
const savedFor = (state, section) => state.savedFor(section)
const countCalls = (harness, name) => harness.calls.filter(call => call.name === name).length
const saveMethod = section => section !== 'PAYMENT' ? 'saveWechatLogin' : 'saveWechatPayment'
const overview = () => ({
  encryptionReady: true,
  paymentRuntime: 'MOCK',
  login: { enabled: true, appId: 'wx0123456789abcdef', appSecretConfigured: true, source: 'DATABASE', version: 4, updatedTime: null },
  riderLogin: { enabled: true, appId: 'wxfedcba9876543210', appSecretConfigured: true, source: 'DATABASE', version: 9, updatedTime: null },
  payment: { enabled: false, appId: '', mchId: '', merchantSerialNo: '', notifyUrl: '', refundNotifyUrl: '', apiV3KeyConfigured: false, merchantPrivateKeyConfigured: false, platformPublicKeyConfigured: false, platformPublicKeyId: '', mode: 'MOCK', version: null, updatedTime: null }
})

function createHarness(t, overrides = {}) {
  let mounted, beforeUnmount, stopped = false
  const watchers = new Map()
  const calls = [], messages = [], confirmations = []
  const implementations = {
    profile: async () => ({ roles: ['SUPER_ADMIN'], menus: [] }),
    wechatSettings: async () => overview(),
    saveWechatLogin: async (body, clientType) => ({ ...(clientType === 'RIDER' ? overview().riderLogin : overview().login), enabled: body.enabled, appId: body.appId, version: clientType === 'RIDER' ? 10 : 5 }),
    saveWechatPayment: async body => ({ ...overview().payment, enabled: body.enabled, appId: body.appId, version: 0 }),
    validateWechat: async section => ({ valid: false, messages: ['Missing saved configuration'], runtime: section === 'PAYMENT' ? 'MOCK' : 'WECHAT_CODE2SESSION' }),
    ...overrides
  }
  const api = Object.fromEntries(Object.keys(implementations).map(name => [name, async (...args) => {
    calls.push({ name, args: snapshot(args) })
    return implementations[name](...args)
  }]))
  const box = {
    response: async () => true,
    closeCount: 0,
    async confirm(message, title, options) { confirmations.push({ message, title, options }); return box.response() },
    close() { box.closeCount++ }
  }
  const vue = {
    ref(initial) {
      let value = initial
      const result = { get value() { return value }, set value(next) {
        const previous = value
        value = next
        if (!Object.is(previous, next) && !stopped) for (const callback of watchers.get(result) || []) callback(next, previous)
      } }
      return result
    },
    reactive: value => value,
    computed: getter => ({ get value() { return getter() } }),
    watch(source, callback, options) {
      assert.equal(options.flush, 'sync', 'The secret cleanup watcher must remain synchronous')
      watchers.set(source, [...(watchers.get(source) || []), callback])
    },
    defineComponent: value => value,
    h: (type, props, children) => ({ type, props, children }),
    onMounted: callback => { mounted = callback },
    onBeforeUnmount: callback => { beforeUnmount = callback }
  }
  const component = componentScript.runInNewContext({
    __imports: {
      vue,
      'element-plus': { ElAlert: {}, ElMessage: { error: text => messages.push({ type: 'error', text }), success: text => messages.push({ type: 'success', text }) }, ElMessageBox: box },
      '../api': { adminApi: api },
      '../components/AppIcon.vue': { default: {} }
    },
    URL, crypto: webcrypto, atob: value => Buffer.from(value, 'base64').toString('binary'), Uint8Array
  })
  const state = component.setup({}, { expose() {} })
  // Unlike a blanket validate:true stub, run the component's actual field rules.
  for (const section of sections) {
    const formRef = state.formRefFor(section)
    const rules = state.rulesFor(section)
    formRef.value = { clearValidate() {}, async validate() {
      const valid = await Promise.all(Object.entries(rules).map(([field, rule]) => accepts(rule, formFor(state, section)[field])))
      if (valid.some(value => !value)) throw new Error('FORM_INVALID')
      return true
    } }
  }
  function unmount() {
    if (stopped) return
    beforeUnmount()
    stopped = true
    watchers.clear()
  }
  t.after(unmount)
  return { state, calls, messages, confirmations, implementations, box, mount: () => mounted(), unmount }
}
function accepts(rules, value) {
  return new Promise(resolve => rules[0].validator({}, value, error => resolve(!error)))
}
function assertCleanSecrets(state) {
  for (const section of sections) for (const [key, clear] of keys[section]) {
    assert.equal(formFor(state, section)[key], '', `${key} must be empty`)
    assert.equal(formFor(state, section)[clear], false, `${clear} must be reset`)
  }
  assert.equal(state.pemVisible.merchantPrivateKey, false)
  assert.equal(state.pemVisible.platformPublicKey, false)
}
function assertNoSuccess(harness) { assert.equal(harness.messages.some(message => message.type === 'success'), false) }

test('01 authorized loading keeps only redacted fields and exact source/version', async t => {
  for (const source of ['DATABASE', 'ENVIRONMENT']) {
    const harness = createHarness(t, { wechatSettings: async () => {
      const data = overview()
      Object.assign(data.login, { appSecret: 'unexpected-plaintext', source, version: source === 'ENVIRONMENT' ? null : 4 })
      Object.assign(data.riderLogin, { appSecret: 'unexpected-rider-plaintext', appSecretEncrypted: 'unexpected-ciphertext', source, version: source === 'ENVIRONMENT' ? null : 9 })
      Object.assign(data.payment, { apiV3Key: 'unexpected-plaintext', merchantPrivateKey: 'unexpected-plaintext', platformPublicKey: 'unexpected-plaintext' })
      return data
    } })
    await harness.mount()
    assert.equal(harness.state.canSave.value, true)
    assert.equal(harness.state.loginSaved.value.source, source)
    assert.equal(harness.state.loginSaved.value.version, source === 'ENVIRONMENT' ? null : 4)
    assertCleanSecrets(harness.state)
    for (const section of sections) for (const [key] of keys[section]) assert.equal(savedFor(harness.state, section)[key], undefined)
    assert.equal(harness.state.buildPayload('LOGIN').version, source === 'ENVIRONMENT' ? null : 4)
  }
})

test('02 profile/menu and server authorization refusals fail closed', async t => {
  const allowed = createHarness(t, { profile: async () => ({ roles: [], menus: [{ code: 'wechat-settings' }] }) })
  await allowed.mount()
  assert.equal(allowed.state.canSave.value, true)
  const denied = createHarness(t, { profile: async () => ({ roles: [], menus: [] }) })
  await denied.mount()
  assert.equal(countCalls(denied, 'wechatSettings'), 0)
  assert.ok(denied.state.pageError.value)
  assert.equal(denied.state.canSave.value, false)
  for (const method of ['profile', 'wechatSettings']) for (const error of [{ code: 403 }, { response: { status: 403, data: {} } }]) {
    const harness = createHarness(t, { [method]: async () => { throw error } })
    await harness.mount()
    assert.equal(harness.state.canSave.value, false)
    assert.equal(harness.state.canValidate.value, false)
    assert.ok(harness.state.pageError.value)
    await harness.state.saveSection('LOGIN')
    await harness.state.validateSaved('PAYMENT')
    assert.equal(countCalls(harness, 'saveWechatLogin'), 0)
    assert.equal(countCalls(harness, 'validateWechat'), 0)
    assertNoSuccess(harness)
  }
})

test('03 loading/network failure disables actions; retry recovers without fake defaults', async t => {
  const pending = deferred()
  const harness = createHarness(t, { wechatSettings: () => pending.promise })
  const loading = harness.mount()
  await tick()
  assert.equal(harness.state.loading.value, true)
  assert.equal(harness.state.canSave.value, false)
  await harness.state.saveSection('LOGIN')
  assert.equal(countCalls(harness, 'saveWechatLogin'), 0)
  pending.resolve(overview())
  await loading
  harness.implementations.wechatSettings = async () => { throw new Error('network unavailable') }
  await harness.state.reloadSettings()
  assert.equal(harness.state.ready.value, false)
  assert.equal(harness.state.canSave.value, false)
  assert.equal(harness.state.canValidate.value, false)
  assert.ok(harness.state.pageError.value)
  harness.implementations.wechatSettings = async () => overview()
  await harness.state.reloadSettings()
  assert.equal(harness.state.canSave.value, true)
  assert.equal(harness.state.pageError.value, '')
})

test('04 encryption not ready permits draft switches/editing and validation but never saving', async t => {
  const harness = createHarness(t, { wechatSettings: async () => ({ ...overview(), encryptionReady: false }) })
  await harness.mount()
  assert.equal(harness.state.ready.value, true)
  assert.equal(harness.state.canSave.value, false)
  assert.equal(harness.state.fieldsDisabled.value, false)
  assert.equal(harness.state.canValidate.value, true)
  for (const section of sections) {
    const form = formFor(harness.state, section), saved = savedFor(harness.state, section)
    form.enabled = !saved.enabled
    form.appId = 'wxfedcba9876543210'
    await harness.state.saveSection(section)
    assert.equal(form.enabled, !saved.enabled)
    assert.equal(form.appId, 'wxfedcba9876543210')
  }
  assert.equal(harness.confirmations.length, 0)
  assert.equal(countCalls(harness, 'saveWechatLogin') + countCalls(harness, 'saveWechatPayment'), 0)
  await harness.state.validateSaved('PAYMENT')
  assert.equal(countCalls(harness, 'validateWechat'), 1)
  assert.equal(harness.state.fieldsDisabled.value, false)
  assert.equal(harness.state.canSave.value, false)
})

test('04b draft fields remain locked until loaded/authorized and during conflicts or operations', async t => {
  const harness = createHarness(t)
  const state = harness.state
  assert.equal(state.fieldsDisabled.value, true)
  await harness.mount()
  assert.equal(state.fieldsDisabled.value, false)
  for (const flag of ['loading', 'confirming', 'saving', 'validating', 'needsReload']) {
    state[flag].value = flag === 'saving' || flag === 'validating' ? 'LOGIN' : true
    assert.equal(state.fieldsDisabled.value, true, `${flag} must lock draft fields`)
    state[flag].value = flag === 'saving' || flag === 'validating' ? '' : false
    assert.equal(state.fieldsDisabled.value, false)
  }
  for (const flag of ['ready', 'authorized']) {
    state[flag].value = false
    assert.equal(state.fieldsDisabled.value, true, `${flag} is required to edit drafts`)
    state[flag].value = true
    assert.equal(state.fieldsDisabled.value, false)
  }
  assert.equal((descriptor.template.content.match(/:disabled="fieldsDisabled"/g) || []).length, 2, 'Both forms must use the draft editing guard')
})

test('05 blank secrets retain original values; clear flags are explicit and independent', async t => {
  const harness = createHarness(t)
  await harness.mount()
  const state = harness.state
  for (const section of sections) {
    for (const [key] of keys[section]) formFor(state, section)[key] = ' \n\t '
    const body = state.buildPayload(section)
    assert.equal(body.enabled, formFor(state, section).enabled)
    assert.equal(body.version, savedFor(state, section).version)
    assert.equal('source' in body, false)
    for (const [key, clear] of keys[section]) { assert.equal(body[key], ''); assert.equal(body[clear], false) }
    for (const [key, clear] of keys[section]) {
      const form = formFor(state, section)
      form[key] = mockSecret
      form[clear] = true
      state.clearMarkedSecret(section, key, true)
      assert.equal(form[key], '')
      assert.equal(state.buildPayload(section)[clear], true)
      assert.equal(state.buildPayload(section)[key], '')
      for (const [, otherClear] of keys[section]) if (otherClear !== clear) assert.equal(state.buildPayload(section)[otherClear], false)
      form[clear] = false
    }
  }
  state.paymentForm.merchantPrivateKey = ' -----BEGIN PRIVATE KEY-----\r\nYWJj\r\n-----END PRIVATE KEY----- '
  assert.equal(state.buildPayload('PAYMENT').merchantPrivateKey, '-----BEGIN PRIVATE KEY-----\nYWJj\n-----END PRIVATE KEY-----')
})

test('06 exact field formats, configured retention, masks and optional disabled fields', async t => {
  const harness = createHarness(t)
  await harness.mount()
  const state = harness.state
  const check = async (rules, value, expected) => assert.equal(await accepts(rules, value), expected)
  await check(state.loginRules.appId, 'wx0123456789abcdef', true)
  await check(state.loginRules.appId, 'wx123', false)
  await check(state.loginRules.appSecret, '', true)
  await check(state.loginRules.appSecret, mockSecret, true)
  await check(state.loginRules.appSecret, 'g'.repeat(32), false)
  for (const mask of ['****', '••••', '＊＊＊＊']) await check(state.loginRules.appSecret, mask, false)
  state.paymentForm.enabled = true
  await check(state.paymentRules.mchId, '', false)
  await check(state.paymentRules.mchId, '12345678', true)
  await check(state.paymentRules.merchantSerialNo, 'a'.repeat(15), false)
  await check(state.paymentRules.merchantSerialNo, 'a'.repeat(16), true)
  await check(state.paymentRules.merchantSerialNo, 'a'.repeat(65), false)
  await check(state.paymentRules.apiV3Key, mockSecret, true)
  await check(state.paymentRules.apiV3Key, 'a'.repeat(31) + ' ', false)
  await check(state.paymentRules.apiV3Key, '', false)
  state.paymentSaved.value.apiV3KeyConfigured = true
  await check(state.paymentRules.apiV3Key, '', true)
  await check(state.paymentRules.platformPublicKeyId, 'PUB_KEY_ID_123', true)
  await check(state.paymentRules.platformPublicKeyId, 'PUB_KEY_ID_a b', false)
  for (const value of ['http://example.invalid/notify', 'https://user:password@example.invalid', 'https://example.invalid/#fragment']) await check(state.paymentRules.notifyUrl, value, false)
  await check(state.paymentRules.notifyUrl, 'https://example.invalid/notify', true)
  await check(state.paymentRules.refundNotifyUrl, '', true)
  state.paymentForm.enabled = false
  for (const rule of Object.values(state.paymentRules)) await check(rule, '', true)
  state.paymentForm.enabled = true
  state.paymentForm.clearMerchantPrivateKey = true
  await check(state.paymentRules.merchantPrivateKey, '', true)
  // Actual save uses the real validator mock, so a masked secret cannot reach PUT.
  state.loginForm.appSecret = '****'
  await state.saveSection('LOGIN')
  assert.equal(harness.confirmations.length, 0)
  assert.equal(countCalls(harness, 'saveWechatLogin'), 0)
})

test('07 local Web Crypto accepts RSA2048 PKCS8/SPKI and rejects weak/wrong PEM', async t => {
  const harness = createHarness(t)
  await harness.mount()
  const exportPem = async (format, key, label) => {
    const bytes = await webcrypto.subtle.exportKey(format, key)
    return `-----BEGIN ${label}-----\n${Buffer.from(bytes).toString('base64').match(/.{1,64}/g).join('\n')}\n-----END ${label}-----`
  }
  const generate = bits => webcrypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: bits, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify'])
  const pair = await generate(2048)
  assert.equal(await harness.state.isRsaPem(await exportPem('pkcs8', pair.privateKey, 'PRIVATE KEY'), true), true)
  assert.equal(await harness.state.isRsaPem(await exportPem('spki', pair.publicKey, 'PUBLIC KEY'), false), true)
  const weak = await generate(1024)
  assert.equal(await harness.state.isRsaPem(await exportPem('pkcs8', weak.privateKey, 'PRIVATE KEY'), true), false)
  assert.equal(await harness.state.isRsaPem(await exportPem('spki', weak.publicKey, 'PUBLIC KEY'), false), false)
  assert.equal(await harness.state.isRsaPem('-----BEGIN RSA PRIVATE KEY-----\nYWJj\n-----END RSA PRIVATE KEY-----', true), false)
  assert.equal(await harness.state.isRsaPem('-----BEGIN PUBLIC KEY-----\nYWJj\n-----END PUBLIC KEY-----', false), false)
})

test('08 successful PUT refreshes returned version and clears all section secrets', async t => {
  for (const section of sections) {
    const harness = createHarness(t)
    await harness.mount()
    harness.state.loginForm.appSecret = mockSecret
    harness.state.paymentForm.apiV3Key = mockSecret
    await harness.state.saveSection(section)
    assert.equal(countCalls(harness, saveMethod(section)), 1)
    const call = harness.calls.find(call => call.name === saveMethod(section))
    assert.equal(call.args[0].version, section === 'PAYMENT' ? null : section === 'LOGIN' ? 4 : 9)
    assert.equal(savedFor(harness.state, section).version, section === 'PAYMENT' ? 0 : section === 'LOGIN' ? 5 : 10)
    if (section !== 'PAYMENT') assert.equal(call.args[1], section === 'LOGIN' ? 'USER' : 'RIDER')
    assertCleanSecrets(harness.state)
    assert.equal(harness.messages.filter(message => message.type === 'success').length, 1)
    assert.equal(harness.state.busy.value, false)
    assert.equal(harness.confirmations[0].message.includes(mockSecret), false)
  }
})

test('09 confirmation cancellation preserves input and concurrent save is blocked', async t => {
  const harness = createHarness(t)
  await harness.mount()
  harness.state.loginForm.appSecret = mockSecret
  const pending = deferred()
  harness.box.response = () => pending.promise
  const saving = harness.state.saveSection('LOGIN')
  await tick()
  assert.equal(harness.state.canSave.value, false)
  assert.equal(harness.state.canLeaveTab(), false)
  await harness.state.saveSection('LOGIN')
  assert.equal(harness.confirmations.length, 1)
  assert.equal(countCalls(harness, 'saveWechatLogin'), 0)
  pending.resolve(Promise.reject(new Error('cancel')))
  await saving
  assert.equal(harness.state.loginForm.appSecret, mockSecret)
  assert.equal(harness.state.busy.value, false)
  assertNoSuccess(harness)
})

test('10 HTTP/business 409 retains drafts, blocks overwrite and requires reload', async t => {
  for (const section of sections) for (const error of [{ code: 409 }, { response: { status: 409, data: {} } }]) {
    const harness = createHarness(t, { [saveMethod(section)]: async () => { throw error } })
    await harness.mount()
    formFor(harness.state, section)[keys[section][0][0]] = mockSecret
    await harness.state.saveSection(section)
    assert.equal(harness.state.needsReload.value, true)
    assert.equal(harness.state.canSave.value, false)
    assert.equal(harness.state.canValidate.value, false)
    assert.equal(formFor(harness.state, section)[keys[section][0][0]], mockSecret)
    assert.match(harness.state.actionErrors[section], /409/)
    await harness.state.saveSection(section)
    assert.equal(countCalls(harness, saveMethod(section)), 1)
    assertNoSuccess(harness)
    await harness.state.reloadSettings()
    assert.equal(harness.state.needsReload.value, false)
    assert.equal(harness.state.canSave.value, true)
    assertCleanSecrets(harness.state)
  }
})

test('11 401/403/503 fail closed; network save errors preserve input without leaks', async t => {
  for (const code of [401, 403, 503, 500]) for (const http of [false, true]) {
    const error = http ? { response: { status: code, data: { msg: mockSecret } } } : { code, msg: mockSecret }
    const harness = createHarness(t, { saveWechatLogin: async () => { throw error } })
    await harness.mount()
    harness.state.loginForm.appSecret = mockSecret
    await harness.state.saveSection('LOGIN')
    assert.equal(harness.state.loginForm.appSecret, mockSecret)
    assert.equal(harness.state.canSave.value, code === 500)
    assertNoSuccess(harness)
    assert.equal(harness.messages.some(message => message.text.includes(mockSecret)), false)
    assert.equal(harness.state.fieldsDisabled.value, code === 401 || code === 403)
    if (code === 503) assert.equal(harness.state.encryptionReady.value, false)
    if (code === 401 || code === 403) assert.ok(harness.state.pageError.value)
  }
})

test('12 saved validation submits section only and preserves valid=false and drafts', async t => {
  for (const section of sections) {
    const harness = createHarness(t)
    await harness.mount()
    harness.state.loginForm.appSecret = mockSecret
    harness.state.paymentForm.apiV3Key = mockSecret
    await harness.state.validateSaved(section)
    assert.deepEqual(harness.calls.find(call => call.name === 'validateWechat').args, section === 'PAYMENT' ? ['PAYMENT'] : ['LOGIN', section === 'LOGIN' ? 'USER' : 'RIDER'])
    assert.equal(harness.state.validationResults[section].valid, false)
    assert.equal(harness.state.validationResults[section].runtime, section !== 'PAYMENT' ? 'WECHAT_CODE2SESSION' : 'MOCK')
    assert.equal(harness.state.loginForm.appSecret, mockSecret)
    assert.equal(harness.state.paymentForm.apiV3Key, mockSecret)
    assertNoSuccess(harness)
  }
})

test('13 switching tabs/unmount clears secrets, flags and PEM visibility; ignores late responses', async t => {
  const harness = createHarness(t)
  await harness.mount()
  for (const section of sections) for (const [key, clear] of keys[section]) { formFor(harness.state, section)[key] = mockSecret; formFor(harness.state, section)[clear] = true }
  harness.state.pemVisible.merchantPrivateKey = true
  harness.state.pemVisible.platformPublicKey = true
  harness.state.tab.value = 'PAYMENT'
  assertCleanSecrets(harness.state)
  const pending = deferred()
  harness.implementations.saveWechatLogin = () => pending.promise
  harness.state.loginForm.appSecret = mockSecret
  const saving = harness.state.saveSection('LOGIN')
  await tick()
  harness.unmount()
  assertCleanSecrets(harness.state)
  pending.resolve({ ...overview().login, version: 5 })
  await saving
  assert.equal(harness.state.loginSaved.value.version, 4)
  assertNoSuccess(harness)
  await harness.state.saveSection('LOGIN')
  assert.equal(countCalls(harness, 'saveWechatLogin'), 1)
  const confirming = createHarness(t)
  await confirming.mount()
  const confirmPending = deferred()
  confirming.box.response = () => confirmPending.promise
  const confirmSave = confirming.state.saveSection('LOGIN')
  await tick()
  confirming.unmount()
  assert.equal(confirming.box.closeCount, 1)
  confirmPending.resolve(true)
  await confirmSave
  assert.equal(countCalls(confirming, 'saveWechatLogin'), 0)
})

test('15 independent USER/RIDER drafts, versions, errors and validation do not overwrite each other', async t => {
  const harness = createHarness(t)
  await harness.mount()
  const state = harness.state
  state.loginForm.appId = 'wx1111111111111111'
  state.riderLoginForm.appId = 'wx2222222222222222'
  state.riderLoginForm.appSecret = mockSecret
  state.actionErrors.LOGIN = 'user-only-error'
  const userBefore = snapshot(state.loginSaved.value)
  await state.saveSection('LOGIN_RIDER')
  assert.deepEqual(snapshot(state.loginSaved.value), userBefore)
  assert.equal(state.loginForm.appId, 'wx1111111111111111')
  assert.equal(state.actionErrors.LOGIN, 'user-only-error')
  assert.equal(state.riderLoginSaved.value.version, 10)
  assert.equal(state.buildPayload('LOGIN').version, 4)
  assertCleanSecrets(state)
  await state.validateSaved('LOGIN_RIDER')
  assert.equal(state.validationResults.LOGIN, null)
  assert.equal(state.validationResults.LOGIN_RIDER.valid, false)
  assert.match(harness.confirmations[0].message, /骑手端微信登录/)
  state.loginForm.appSecret = mockSecret
  state.riderLoginForm.clearAppSecret = true
  state.tab.value = 'LOGIN_RIDER'
  assertCleanSecrets(state)
  assert.throws(() => state.formFor('UNKNOWN'), /INVALID_SECTION/)
  await assert.rejects(state.saveSection('UNKNOWN'), /INVALID_SECTION/)
  await assert.rejects(state.validateSaved('UNKNOWN'), /INVALID_SECTION/)
})

test('16 missing RIDER credentials stay absent, and masks cannot be submitted for RIDER', async t => {
  const harness = createHarness(t, { wechatSettings: async () => {
    const data = overview()
    data.riderLogin = { enabled: false, appId: '', appSecretConfigured: false, source: 'ENVIRONMENT', version: null, updatedTime: null }
    return data
  } })
  await harness.mount()
  const state = harness.state
  assert.equal(state.riderLoginForm.appId, '')
  assert.equal(state.riderLoginSaved.value.appSecretConfigured, false)
  state.riderLoginForm.enabled = true
  assert.equal(await accepts(state.riderLoginRules.appSecret, ''), false)
  for (const mask of ['****', '••••', '＊＊＊＊']) {
    assert.equal(await accepts(state.riderLoginRules.appSecret, mask), false)
    state.riderLoginForm.appId = 'wxfedcba9876543210'
    state.riderLoginForm.appSecret = mask
    await state.saveSection('LOGIN_RIDER')
  }
  assert.equal(countCalls(harness, 'saveWechatLogin'), 0)
  assert.equal(harness.confirmations.length, 0)
})

test('14 malformed data/unsafe versions never enable saves; no secret storage/logging', async t => {
  const mutations = [
    data => { data.login.version = Number.MAX_SAFE_INTEGER + 1 },
    data => { data.login.source = 'UNKNOWN' },
    data => { delete data.login.appSecretConfigured },
    data => { data.paymentRuntime = 'REAL' },
    data => { data.payment.mode = 'REAL' },
    data => { delete data.riderLogin },
    data => { data.riderLogin.source = 'USER_FALLBACK' },
    data => { data.riderLogin.version = -1 },
    data => { delete data.riderLogin.appSecretConfigured }
  ]
  for (const mutate of mutations) {
    const harness = createHarness(t, { wechatSettings: async () => { const data = overview(); mutate(data); return data } })
    await harness.mount()
    assert.equal(harness.state.canSave.value, false)
    assert.ok(harness.state.pageError.value)
    assertNoSuccess(harness)
  }
  const harness = createHarness(t, { saveWechatLogin: async () => ({ enabled: true }) })
  await harness.mount()
  harness.state.loginForm.appSecret = mockSecret
  await harness.state.saveSection('LOGIN')
  assert.equal(harness.state.canSave.value, false)
  assert.equal(harness.state.loginForm.appSecret, mockSecret)
  assertNoSuccess(harness)
  assert.equal(/localStorage|sessionStorage|console\.|URLSearchParams/.test(descriptor.scriptSetup.content), false)
  assert.match(descriptor.template.content, /type="password"/)
  assert.match(descriptor.template.content, /:disabled="!canSave"/)
})
