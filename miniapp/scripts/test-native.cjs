'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const native = path.resolve(__dirname, '..')
const tests = []
const test = (name, fn) => tests.push({ name, fn })
const flush = () => new Promise(resolve => setImmediate(resolve))
function environment(overrides = {}) {
  const storage = new Map()
  const calls = { requests: [], toast: [], navigation: [], locations: 0 }
  const timers = new Map()
  let timerId = 0
  let definition
  const wx = {
    getStorageSync: k => storage.get(k), setStorageSync: (k, v) => storage.set(k, v), removeStorageSync: k => storage.delete(k),
    request(options) { calls.requests.push(options) },
    showToast(value) { calls.toast.push(value.title) },
    navigateTo(value) { calls.navigation.push(value); if (value.complete) value.complete() },
    redirectTo(value) { calls.navigation.push(value) }, navigateBack() { calls.navigation.push({ back: true }) }, switchTab(value) { calls.navigation.push(value) },
    showModal(options) { options.success({ confirm: true, cancel: false }) },
    getAccountInfoSync() { return { miniProgram: { envVersion: 'develop' } } },
    login(options) { options.success({ code: 'temporary-wx-code' }) },
    stopPullDownRefresh() {}, openSetting() {},
    getLocation(options) { calls.locations++; options.success({ longitude: 108.123456, latitude: 28.234567, speed: 4 }) }
  }
  Object.assign(wx, overrides)
  const cache = new Map()
  function load(file) {
    const absolute = path.resolve(file)
    if (cache.has(absolute)) return cache.get(absolute).exports
    const module = { exports: {} }
    cache.set(absolute, module)
    const context = { module, exports: module.exports, wx, console, getCurrentPages: () => [], Page(value) { definition = value }, App(value) { definition = value },
      setTimeout(fn) { const id = ++timerId; timers.set(id, fn); return id }, clearTimeout(id) { timers.delete(id) },
      require(name) {
        assert.ok(name.startsWith('.'), 'native modules must not load dependencies')
        const target = path.resolve(path.dirname(absolute), name)
        return load(path.extname(target) ? target : target + '.js')
      }
    }
    vm.runInNewContext(fs.readFileSync(absolute, 'utf8'), context, { filename: absolute })
    return module.exports
  }
  function page(relative, api) {
    if (api) {
      // 测试替身也实现会话作用域，防止用Mock绕开真实请求层的账号隔离。
      const forSession = revision => Object.fromEntries(Object.entries(api).map(([name, fn]) => [name, (...args) => {
        const session = load(path.join(native, 'utils/session.js'))
        if (revision !== session.revision()) return Promise.reject(Object.assign(new Error('账户状态已变化'), { silent: true }))
        return fn(...args)
      }]))
      cache.set(path.join(native, 'utils/api.js'), { exports: Object.assign({}, api, { forSession }) })
      // 公共Page需引用本次测试替身；已创建的页面继续持有自己的Page定义。
      cache.delete(path.join(native, 'utils/page.js'))
    }
    const file = path.join(native, relative + '.js')
    cache.delete(file)
    load(file)
    const instance = Object.assign({}, definition)
    instance.data = JSON.parse(JSON.stringify(definition.data))
    instance.setData = function(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.')
        let target = this.data
        while (parts.length > 1) { const first = parts.shift(); target[first] = target[first] || {}; target = target[first] }
        target[parts[0]] = value
      }
    }
    return instance
  }
  function authenticated(role = 'USER') { storage.set('feilv_token', 'token-1'); storage.set('feilv_user_id', 1); storage.set('feilv_role', role) }
  function app() { load(path.join(native, 'app.js')); return definition }
  return { wx, storage, calls, timers, load, page, app, authenticated }
}
function event(field, value) { return { currentTarget: { dataset: { field } }, detail: { value } } }

test('architecture: root App launches directly without framework or bundled runtime', () => {
  const env = environment()
  const app = env.app()
  assert.equal(typeof app.onLaunch, 'function')
  app.onLaunch()
  assert.equal(env.storage.get('native_effective_base_url'), 'https://xt.allms.cn')
  assert.equal(env.calls.requests.length, 0)
  assert.equal(env.calls.locations, 0)
})
test('request: bearer, method helpers, valid Result and base URL', async () => {
  const env = environment(); env.authenticated()
  const { request } = env.load(path.join(native, 'utils/request.js'))
  const promise = request.post('/api/v1/example', { agreed: true })
  const req = env.calls.requests[0]
  assert.equal(req.url, 'https://xt.allms.cn/api/v1/example')
  assert.equal(req.header.Authorization, 'Bearer token-1')
  assert.equal(req.method, 'POST')
  req.success({ statusCode: 200, data: { code: 0, data: { id: 8 } } })
  assert.equal((await promise).id, 8)
  for (const method of ['get', 'post', 'put', 'delete']) assert.equal(typeof request[method], 'function')
})
test('deployment: old localhost override cannot redirect requests from official HTTPS service', async () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_base_url', 'http://localhost:8080')
  const { request, baseURL, canOverrideBaseURL } = env.load(path.join(native, 'utils/request.js'))
  assert.equal(canOverrideBaseURL(), false)
  assert.equal(baseURL(), 'https://xt.allms.cn')
  const pending = request.get('/api/v1/user/me')
  assert.equal(env.calls.requests[0].url, 'https://xt.allms.cn/api/v1/user/me')
  env.calls.requests[0].success({ statusCode: 200, data: { code: 0, data: {} } })
  await pending
})
test('deployment: only explicitly enabled develop environment may override API origin', () => {
  for (const version of ['develop', 'trial', 'release']) {
    const env = environment({ getAccountInfoSync: () => ({ miniProgram: { envVersion: version } }) })
    const config = env.load(path.join(native, 'config.js'))
    config.ALLOW_DEV_SERVER_OVERRIDE = true
    env.storage.set('native_base_url', 'http://localhost:8080')
    const request = env.load(path.join(native, 'utils/request.js'))
    assert.equal(request.canOverrideBaseURL(), version === 'develop')
    assert.equal(request.baseURL(), version === 'develop' ? 'http://localhost:8080' : 'https://xt.allms.cn')
  }
})
test('deployment: startup clears old service token, draft and override before first request', () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_base_url', 'http://localhost:8080')
  env.storage.set('native_draft', { owner: 1 })
  env.storage.set('native_pending_order', { id: 10 })
  const { initializeServerConfig } = env.load(path.join(native, 'utils/request.js'))
  initializeServerConfig()
  for (const key of ['feilv_token', 'native_draft', 'native_pending_order', 'native_base_url']) assert.equal(env.storage.has(key), false)
  assert.equal(env.storage.get('native_effective_base_url'), 'https://xt.allms.cn')
})
test('deployment: current official service session survives subsequent startup', () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_effective_base_url', 'https://xt.allms.cn')
  const { initializeServerConfig } = env.load(path.join(native, 'utils/request.js'))
  initializeServerConfig()
  assert.equal(env.storage.get('feilv_token'), 'token-1')
})
test('deployment: unknown legacy session without service marker is cleared once', () => {
  const env = environment(); env.authenticated()
  const { initializeServerConfig } = env.load(path.join(native, 'utils/request.js'))
  initializeServerConfig()
  assert.equal(env.storage.has('feilv_token'), false)
})
test('deployment: settings cannot save a different backend when release configuration is locked', async () => {
  const env = environment(); const page = env.page('pages/settings/settings')
  page.onLoad({}); page.onShow()
  assert.equal(page.data.editable, false)
  page.input({ detail: { value: 'https://other.example' } })
  await page.save()
  assert.equal(env.storage.has('native_base_url'), false)
  assert.equal(env.calls.requests.length, 0)
})
test('request: HTTP failures, business failures, invalid body and network errors reject', async () => {
  for (const response of [
    { statusCode: 500, data: { code: 0, msg: 'HTTP error' } },
    { statusCode: 403, data: { code: 403, msg: 'forbidden' } },
    { statusCode: 200, data: { code: 4090, msg: 'wrong state' } },
    { statusCode: 200, data: '<html>bad</html>' }
  ]) {
    const env = environment(); const { request } = env.load(path.join(native, 'utils/request.js'))
    const p = request.get('/test'); env.calls.requests[0].success(response)
    await assert.rejects(p)
  }
  const env = environment(); const { request } = env.load(path.join(native, 'utils/request.js'))
  const p = request.get('/test'); env.calls.requests[0].fail({ errMsg: 'timeout' }); await assert.rejects(p, /网络异常/)
})
test('request: HTTP/business 401 clears credentials and sends protected user to login', async () => {
  for (const statusCode of [200, 401]) {
    const env = environment(); env.authenticated()
    const { request } = env.load(path.join(native, 'utils/request.js'))
    const p = request.get('/test'); env.calls.requests[0].success({ statusCode, data: { code: 401, msg: 'expired' } })
    await assert.rejects(p, /登录已过期/)
    assert.equal(env.storage.has('feilv_token'), false)
    assert.equal(env.calls.navigation.length, 1)
  }
})
test('request: stale responses after logout/account switch are discarded', async () => {
  const env = environment(); env.authenticated()
  const { request } = env.load(path.join(native, 'utils/request.js'))
  const session = env.load(path.join(native, 'utils/session.js'))
  const p = request.get('/test'); session.clear()
  env.calls.requests[0].success({ statusCode: 200, data: { code: 0, data: 'old account data' } })
  await assert.rejects(p, /账户状态已变化/)
})
test('login: agreement required; wx.login code contract, no mobile or client openid', async () => {
  const env = environment()
  const page = env.page('pages/login/login')
  page.onLoad({}); page._visible = true
  await page.wechat(); assert.equal(env.calls.requests.length, 0)
  page.consent({ detail: { value: ['yes'] } })
  const pending = page.wechat(); await flush()
  const req = env.calls.requests[0]
  assert.ok(req.url.endsWith('/api/v1/auth/wechat-login'))
  assert.equal(req.data.code, 'temporary-wx-code'); assert.equal(req.data.agreed, true)
  assert.equal('mobile' in req.data, false); assert.equal('openid' in req.data, false)
  req.success({ statusCode: 200, data: { code: 0, data: { token: 'wx-token', userId: 9, role: 'USER', nickname: '微信用户' } } })
  await pending
  assert.equal(env.storage.get('feilv_token'), 'wx-token'); assert.equal(env.storage.get('feilv_role'), 'USER')
})
test('login: returning WeChat rider keeps server role while admin response is rejected', async () => {
  for (const role of ['RIDER', 'ADMIN']) {
    const env = environment()
    const page = env.page('pages/login/login')
    page.onLoad({}); page._visible = true
    page.consent({ detail: { value: ['yes'] } })
    const pending = page.wechat(); await flush()
    env.calls.requests[0].success({ statusCode: 200, data: { code: 0, data: { token: 'wx-token', userId: 9, role, nickname: '用户' } } })
    await pending
    assert.equal(env.storage.get('feilv_role'), role === 'RIDER' ? 'RIDER' : undefined)
    if (role === 'ADMIN') assert.equal(env.storage.has('feilv_token'), false)
  }
})
test('format: user OrderVO uses status, rider-only statusCode never controls user actions', () => {
  const env = environment(); const fmt = env.load(path.join(native, 'utils/format.js'))
  assert.equal(fmt.order({ status: 'DELIVERED', statusCode: 'WAIT_PAY' }).canConfirm, true)
  assert.equal(fmt.order({ status: 'DELIVERED' }).canPay, false)
  assert.equal(fmt.order({ status: 'FINISHED' }).canReview, true)
  assert.equal(fmt.order({ status: 'WAIT_PAY' }).canPay, true)
  assert.equal(fmt.order({ status: 'DELIVERING' }).canCancel, false)
  assert.equal(fmt.money(1099), '10.99')
})
test('address: select event returns whitelisted data and native create populates selected side', () => {
  const env = environment()
  const address = env.page('pages/address/address', {})
  address.onLoad({ select: '1' })
  address.data.list = [{ id: 11, contact: '王先生', phone: '13800000000', poiName: '南门', address: '一号楼', lng: 108, lat: 28, isDefault: 1, userId: 999 }]
  let selected
  address.getOpenerEventChannel = () => ({ emit(name, data) { assert.equal(name, 'addressSelected'); selected = data } })
  address.select({ currentTarget: { dataset: { id: 11 } } })
  assert.equal(selected.contact, '王先生'); assert.equal('userId' in selected, false)
  const create = env.page('pages/order/create', {})
  create.onLoad({ type: 'SEND' })
  create.address({ currentTarget: { dataset: { side: 'to' } } })
  env.calls.navigation.at(-1).events.addressSelected(selected)
  assert.equal(create.data.form.toContact, '王先生'); assert.equal(create.data.form.toAddress, '南门 一号楼'); assert.equal(create.data.form.toLng, 108)
  assert.equal(create.data.form.fromContact, '')
})
test('address: default uses existing PUT contract with isDefault=1', async () => {
  const env = environment(); let submitted
  const page = env.page('pages/address/address', { async updateAddress(id, data) { submitted = { id, data } }, async addressList() { return [] } })
  page.onLoad({}); page.data.list = [{ id: 3, contact: '张', phone: '13800000000', address: '街道', isDefault: 0 }]
  await page.setDefault({ currentTarget: { dataset: { id: 3 } } })
  assert.equal(submitted.id, 3); assert.equal(submitted.data.isDefault, 1)
})
test('address: manual address edits clear old coordinates instead of reusing another location', () => {
  const env = environment(); const page = env.page('pages/address/address', {})
  page.onLoad({})
  Object.assign(page.data.form, { address: '旧地址', poiName: '旧地点', lng: 108, lat: 28 })
  page.input(event('contact', '新联系人'))
  assert.equal(page.data.form.lng, 108)
  page.input(event('address', '新地址'))
  assert.equal(page.data.form.lng, null); assert.equal(page.data.form.lat, null)
  Object.assign(page.data.form, { lng: 109, lat: 29 })
  page.input(event('poiName', '新地点'))
  assert.equal(page.data.form.lng, null); assert.equal(page.data.form.lat, null)
})
test('rider: refuses blank pickup code and submits exact user input, not fabricated code', async () => {
  const env = environment(); env.authenticated('RIDER'); const submitted = []
  const page = env.page('pages/rider/workbench', { async pickup(id, code) { submitted.push({ id, code }) } })
  page.onLoad({}); page.load = async () => {}
  await page.pickup({ currentTarget: { dataset: { id: 12 } } })
  assert.equal(submitted.length, 0)
  page.codeInput({ currentTarget: { dataset: { id: 12 } }, detail: { value: '012345' } })
  await page.pickup({ currentTarget: { dataset: { id: 12 } } })
  assert.equal(submitted.length, 1); assert.equal(submitted[0].code, '012345')
})
test('rider: real wx.getLocation coordinates and failures never produce random report', async () => {
  const env = environment(); env.authenticated('RIDER'); const reports = []
  const page = env.page('pages/rider/workbench', { async reportTrack(value) { reports.push(value) } })
  page.onLoad({}); page._visible = true
  await page.report({ currentTarget: { dataset: { id: 4 } } })
  assert.equal(env.calls.locations, 1); assert.equal(reports[0].lng, 108.123456); assert.equal(reports[0].lat, 28.234567)
  env.wx.getLocation = options => options.fail({ errMsg: 'auth deny' })
  await page.report({ currentTarget: { dataset: { id: 4 } } })
  assert.equal(reports.length, 1); assert.equal(page.data.locationDenied, true)
})
test('lifecycle: polling timers are cleared on hide/unload and late request cannot restart them', async () => {
  const env = environment(); env.authenticated(); let complete
  const page = env.page('pages/order/track', { orderTrack: () => new Promise(resolve => { complete = resolve }) })
  page.onLoad({ id: '5' }); const pending = page.onShow()
  page.onHide(); complete([]); await pending
  assert.equal(env.timers.size, 0)
  page.later(() => {}, 5000); assert.equal(env.timers.size, 1)
  page.onUnload(); assert.equal(env.timers.size, 0)
  const before = page.data.lastUpdated; page.safeSet({ lastUpdated: 'late' }); assert.equal(page.data.lastUpdated, before)
})
test('lifecycle: SMS cooldown timer reclaimed and run lock suppresses duplicate submissions', async () => {
  const env = environment(); const page = env.page('pages/login/login', {})
  page.onLoad({}); page._visible = true; page._smsUntil = Date.now() + 60000
  page.updateCooling(); assert.equal(env.timers.size, 1)
  page.onHide(); assert.equal(env.timers.size, 0)
  let finish; let calls = 0
  const first = page.run('busy', () => { calls++; return new Promise(resolve => { finish = resolve }) })
  await page.run('busy', async () => { calls++ }); assert.equal(calls, 1)
  finish(); await first; assert.equal(page.data.busy, false)
})
test('orders: known create success + pay failure retries same order without duplicate creation', async () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_draft', { owner: 1, savedAt: 123, payload: { orderType: 'SEND' }, quote: { totalText: '5.00' } })
  let creates = 0; let pays = 0
  const page = env.page('pages/order/confirm', { async orderDetail() { return { id: 88, status: 'WAIT_PAY' } }, async createOrder() { creates++; return { id: 88 } }, async payOrder(id) { assert.equal(id, 88); if (++pays === 1) throw new Error('network') } })
  page.onLoad({}); await page.submit(); assert.equal(page.data.orderId, 88)
  await page.submit(); assert.equal(creates, 1); assert.equal(pays, 2)
  assert.equal(env.storage.has('native_draft'), false)
})
test('orders: uncertain create response blocks blind resubmit and persists across page reopen', async () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_draft', { owner: 1, savedAt: 123, payload: { orderType: 'SEND' }, quote: {} })
  let creates = 0
  const api = { async createOrder() { creates++; throw new Error('timeout') } }
  const page = env.page('pages/order/confirm', api); page.onLoad({})
  await page.submit(); assert.equal(page.data.uncertain, true)
  await page.submit(); assert.equal(creates, 1)
  const reopened = env.page('pages/order/confirm', api); reopened.onLoad({})
  assert.equal(reopened.data.uncertain, true); await reopened.submit(); assert.equal(creates, 1)
})
test('orders: paginated list advances on success and stops on last page', async () => {
  const env = environment(); env.authenticated(); const pages = []
  const page = env.page('pages/order/list', { async myOrders(n) { pages.push(n); return { content: [{ id: n + 1, status: 'WAIT_PAY' }], last: n === 1 } } })
  page.onLoad({}); await page.onShow(); await page.more(); await page.more()
  assert.equal(pages.join(','), '0,1'); assert.equal(page.data.list.length, 2); assert.equal(page.data.hasMore, false)
})
test('quote: changes invalidate old quote and validate contacts/distance/weight', () => {
  const env = environment(); const page = env.page('pages/order/create', {})
  page.onLoad({ type: 'BUY' })
  assert.throws(() => page.buildPayload(), /联系人/)
  Object.assign(page.data.form, { fromContact: '甲', fromPhone: '13800000000', fromAddress: 'A', toContact: '乙', toPhone: '13900000000', toAddress: 'B', goodsDesc: '文件' })
  page.data.distanceKm = '2.5'; page.data.weightKg = '5'
  assert.equal(page.buildPayload().distanceM, 2500)
  page.data.quote = { totalText: '1.00' }; page._quotedPayload = {}
  page.number(event('distanceKm', '3')); assert.equal(page.data.quote, null); assert.equal(page._quotedPayload, null)
})
test('orders: returning to unchanged create form preserves known and uncertain pending order', async () => {
  for (const pendingFields of [{ id: 88 }, { uncertain: true }]) {
    const env = environment(); env.authenticated()
    const create = env.page('pages/order/create', {})
    create.onLoad({ type: 'SEND' })
    Object.assign(create.data.form, { fromContact: '甲', fromPhone: '13800000000', fromAddress: 'A', toContact: '乙', toPhone: '13900000000', toAddress: 'B', goodsDesc: '文件' })
    create.data.distanceKm = '2'; create.data.weightKg = '5'
    create._quotedPayload = create.buildPayload(); create.data.quote = { totalText: '5.00' }
    create.next()
    const first = env.storage.get('native_draft')
    env.storage.set('native_pending_order', Object.assign({ owner: 1, savedAt: first.savedAt }, pendingFields))
    create.next()
    assert.equal(env.storage.get('native_draft').savedAt, first.savedAt)
    const confirm = env.page('pages/order/confirm', {})
    confirm.onLoad({})
    assert.equal(confirm.data.orderId, pendingFields.id || null)
    assert.equal(confirm.data.uncertain, !!pendingFields.uncertain)
  }
})
test('orders: old payment callback cannot delete replacement draft and pending marker', async () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_draft', { owner: 1, savedAt: 100, payload: { orderType: 'SEND' }, quote: {} })
  let paid
  const page = env.page('pages/order/confirm', { async createOrder() { return { id: 88 } }, payOrder: () => new Promise(resolve => { paid = resolve }) })
  page.onLoad({}); const pending = page.submit(); await flush()
  assert.equal(typeof paid, 'function')
  page.onUnload()
  env.storage.set('native_draft', { owner: 1, savedAt: 200, payload: { orderType: 'BUY' }, quote: {} })
  env.storage.set('native_pending_order', { owner: 1, savedAt: 200, uncertain: true })
  paid(); await pending
  assert.equal(env.storage.get('native_draft').savedAt, 200)
  assert.equal(env.storage.get('native_pending_order').uncertain, true)
  assert.equal(env.calls.navigation.some(item => item.url && item.url.includes('detail')), false)
})
test('orders: old create success cannot overwrite a new draft pending marker', async () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_draft', { owner: 1, savedAt: 100, payload: { orderType: 'SEND' }, quote: {} })
  let created; let pays = 0
  const page = env.page('pages/order/confirm', { createOrder: () => new Promise(resolve => { created = resolve }), async payOrder() { pays++ } })
  page.onLoad({}); const pending = page.submit(); await flush()
  page.onUnload()
  env.storage.set('native_draft', { owner: 1, savedAt: 200, payload: { orderType: 'BUY' }, quote: {} })
  env.storage.set('native_pending_order', { owner: 1, savedAt: 200, uncertain: true })
  created({ id: 88 }); await pending
  assert.equal(env.storage.get('native_pending_order').savedAt, 200)
  assert.equal(env.storage.get('native_pending_order').id, undefined)
  assert.equal(pays, 0)
})
test('orders: payment retry detects accepted order instead of paying again forever', async () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_draft', { owner: 1, savedAt: 100, payload: { orderType: 'SEND' }, quote: {} })
  env.storage.set('native_pending_order', { owner: 1, savedAt: 100, id: 88 })
  let pays = 0
  const page = env.page('pages/order/confirm', { async orderDetail(id) { return { id, status: 'ACCEPTED' } }, async payOrder() { pays++ } })
  page.onLoad({}); page._visible = true; await page.submit()
  assert.equal(pays, 0)
  assert.equal(env.storage.has('native_pending_order'), false)
  assert.equal(env.storage.has('native_draft'), false)
  assert.ok(env.calls.navigation.some(item => item.url === '/pages/order/detail?id=88'))
})
test('session: order tab clears previous account addresses before failed replacement request', async () => {
  const env = environment(); env.authenticated()
  const page = env.page('pages/order/list', { async myOrders() { return { content: [{ id: 1, status: 'WAIT_PAY', fromAddress: 'A的地址' }], last: true } } })
  page.onLoad({}); await page.onShow(); assert.equal(page.data.list.length, 1)
  page.onHide()
  const session = env.load(path.join(native, 'utils/session.js'))
  session.save({ token: 'token-B', userId: 2, role: 'USER', nickname: 'B' })
  // 当前页面持有原Mock，替换其接口函数使刷新失败。
  const api = env.load(path.join(native, 'utils/api.js'))
  const forSession = api.forSession
  api.forSession = revision => Object.assign(forSession(revision), { async myOrders() { throw new Error('B network failure') } })
  const pending = page.onShow()
  assert.equal(page.data.list.length, 0)
  await pending
  assert.equal(page.data.list.length, 0)
  assert.equal(page.data.loggedIn, true)
})
test('session: rider switch clears old pickup codes and credit details even when refresh fails', async () => {
  const env = environment(); env.authenticated('RIDER')
  const page = env.page('pages/rider/workbench', { async riderMe() { throw new Error('retry later') } })
  page.onLoad({})
  Object.assign(page.data, { info: { name: 'A' }, orders: [{ id: 1 }], lastGrabCode: 'A的取件码', pickupCodes: { 1: '1234' }, logs: [{ id: 1 }], logsVisible: true, locationInfo: 'A的位置' })
  const session = env.load(path.join(native, 'utils/session.js'))
  session.save({ token: 'rider-B', userId: 2, role: 'RIDER', nickname: 'B' })
  await page.onShow()
  assert.equal(page.data.lastGrabCode, '')
  assert.equal(Object.keys(page.data.pickupCodes).length, 0)
  assert.equal(page.data.logs.length, 0); assert.equal(page.data.logsVisible, false)
  assert.equal(page.data.info, null); assert.equal(page.data.orders.length, 0); assert.equal(page.data.locationInfo, '')
})
test('session: late old-account response cannot repopulate page after onShow reset', async () => {
  const env = environment(); env.authenticated()
  let respond
  const page = env.page('pages/order/list', { myOrders: () => new Promise(resolve => { respond = resolve }) })
  page.onLoad({}); const pending = page.onShow()
  const session = env.load(path.join(native, 'utils/session.js'))
  session.save({ token: 'B-token', userId: 2, role: 'USER', nickname: 'B' })
  page.onHide(); page.onShow()
  assert.equal(page.data.list.length, 0)
  respond({ content: [{ id: 1, status: 'WAIT_PAY', fromAddress: 'A秘密地址' }], last: true })
  await pending
  assert.equal(page.data.list.length, 0)
  assert.equal(env.timers.size > 0, true)
})
test('session: multi-step rider load cannot send follow-up request with another user token', async () => {
  const env = environment(); env.authenticated('RIDER')
  const page = env.page('pages/rider/workbench')
  page.onLoad({}); const pending = page.onShow()
  assert.equal(env.calls.requests.length, 1)
  const response = env.calls.requests[0]
  response.success({ statusCode: 200, data: { code: 0, data: { name: 'A', onlineStatus: 1 } } })
  const session = env.load(path.join(native, 'utils/session.js'))
  session.save({ token: 'rider-B', userId: 2, role: 'RIDER', nickname: 'B' })
  await pending
  assert.equal(env.calls.requests.length, 1)
  assert.equal(page.data.info, null)
})
;(async () => {
  let failures = 0
  for (const { name, fn } of tests) {
    try { await fn(); console.log('PASS ' + name) }
    catch (e) { failures++; console.error('FAIL ' + name + '\n' + e.stack) }
  }
  console.log((tests.length - failures) + '/' + tests.length + ' native wx-mock behavior tests passed')
  if (failures) process.exitCode = 1
})()
