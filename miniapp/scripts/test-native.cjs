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
  const calls = { requests: [], uploads: [], toast: [], navigation: [], locations: 0 }
  const timers = new Map()
  let timerId = 0
  let definition
  let pages = []
  const wx = {
    getStorageSync: k => storage.get(k), setStorageSync: (k, v) => storage.set(k, v), removeStorageSync: k => storage.delete(k),
    request(options) { calls.requests.push(options) },
    uploadFile(options) { calls.uploads.push(options) },
    getFileInfo(options) { options.success({ size: 1024 }) },
    getImageInfo(options) { options.success({ type: 'jpeg' }) },
    showToast(value) { calls.toast.push(value.title) },
    navigateTo(value) { calls.navigation.push(value); if (value.complete) value.complete() },
    redirectTo(value) { calls.navigation.push(value) }, navigateBack() { calls.navigation.push({ back: true }) }, switchTab(value) { calls.navigation.push(value) },
    showModal(options) { options.success({ confirm: true, cancel: false }) },
    getAccountInfoSync() { return { miniProgram: { envVersion: 'develop' } } },
    login(options) { options.success({ code: 'temporary-wx-code' }) },
    canIUse() { return true }, getAppBaseInfo() { return { SDKVersion: '3.4.0' } },
    getPrivacySetting(options) { options.success({ needAuthorization: false }) },
    openPrivacyContract(options) { options.success({}) },
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
    const context = { module, exports: module.exports, wx, console, getCurrentPages: () => pages, Page(value) { definition = value }, App(value) { definition = value },
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
  return { wx, storage, calls, timers, load, page, app, authenticated, setPages(value) { pages = value } }
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
  await flush()
  env.calls.requests[1].success({ statusCode: 200, data: { code: 0, data: profileValue({ userId: 9, nickname: '微信用户' }) } })
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
    await flush()
    if (role === 'RIDER') env.calls.requests[1].success({ statusCode: 200, data: { code: 0, data: profileValue({ userId: 9, role }) } })
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
  const create = env.page('pages/order/create', { async me() { return profileValue({ mobileBound: true }) } })
  create.onLoad({ type: 'SEND' }); create._visible = true
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
  page.input(event('detailAddress', '新地址'))
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
  const page = env.page('pages/order/confirm', { async me() { return profileValue({ mobileBound: true }) }, async orderDetail() { return { id: 88, status: 'WAIT_PAY' } }, async createOrder() { creates++; return { id: 88 } }, async payOrder(id) { assert.equal(id, 88); if (++pays === 1) throw new Error('network') } })
  page.onLoad({}); page.onShow(); await page.submit(); assert.equal(page.data.orderId, 88)
  await page.submit(); assert.equal(creates, 1); assert.equal(pays, 2)
  assert.equal(env.storage.has('native_draft'), false)
})
test('orders: uncertain create response blocks blind resubmit and persists across page reopen', async () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_draft', { owner: 1, savedAt: 123, payload: { orderType: 'SEND' }, quote: {} })
  let creates = 0
  const api = { async me() { return profileValue({ mobileBound: true }) }, async createOrder() { creates++; throw new Error('timeout') } }
  const page = env.page('pages/order/confirm', api); page.onLoad({}); page.onShow()
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
  const env = environment(); const page = env.page('pages/order/create', { async me() { return profileValue({ mobileBound: true }) } })
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
    const create = env.page('pages/order/create', { async me() { return profileValue({ mobileBound: true }) }, async quote() { return { totalFen: 500, items: [] } } })
    create.onLoad({ type: 'SEND' }); create._visible = true
    Object.assign(create.data.form, { fromContact: '甲', fromPhone: '13800000000', fromAddress: 'A', toContact: '乙', toPhone: '13900000000', toAddress: 'B', goodsDesc: '文件' })
    create.data.distanceKm = '2'; create.data.weightKg = '5'
    create._quotedPayload = create.buildPayload(); create.data.quote = { totalText: '5.00' }
    await create.next()
    const first = env.storage.get('native_draft')
    env.storage.set('native_pending_order', Object.assign({ owner: 1, savedAt: first.savedAt }, pendingFields))
    await create.next()
    assert.equal(env.storage.get('native_draft').savedAt, first.savedAt)
    const confirm = env.page('pages/order/confirm', { async me() { return profileValue({ mobileBound: true }) },})
    confirm.onLoad({})
    assert.equal(confirm.data.orderId, pendingFields.id || null)
    assert.equal(confirm.data.uncertain, !!pendingFields.uncertain)
  }
})
test('orders: old payment callback cannot delete replacement draft and pending marker', async () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_draft', { owner: 1, savedAt: 100, payload: { orderType: 'SEND' }, quote: {} })
  let paid
  const page = env.page('pages/order/confirm', { async me() { return profileValue({ mobileBound: true }) }, async createOrder() { return { id: 88 } }, payOrder: () => new Promise(resolve => { paid = resolve }) })
  page.onLoad({}); page.onShow(); const pending = page.submit(); await flush()
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
  const page = env.page('pages/order/confirm', { async me() { return profileValue({ mobileBound: true }) }, createOrder: () => new Promise(resolve => { created = resolve }), async payOrder() { pays++ } })
  page.onLoad({}); page.onShow(); const pending = page.submit(); await flush()
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
  const page = env.page('pages/order/confirm', { async me() { return profileValue({ mobileBound: true }) }, async orderDetail(id) { return { id, status: 'ACCEPTED' } }, async payOrder() { pays++ } })
  page.onLoad({}); page.onShow(); page._visible = true; await page.submit()
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
function profileValue(patch = {}) {
  return Object.assign({ userId: 1, role: 'USER', nickname: '平台昵称', mobile: '', mobileBound: false }, patch)
}
function phoneEvent(code, errMsg = 'getPhoneNumber:ok') { return { detail: { code, errMsg } } }
async function readyProfile(env, api, from = 'my') {
  env.authenticated()
  const page = env.page('pages/profile/profile', api)
  page.onLoad({ from }); await page.onShow()
  return page
}
test('login: WeChat keeps profile inline and GET uses the new authenticated session scope', async () => {
  const env = environment()
  env.setPages([{ route: 'pages/order/create' }, { route: 'pages/login/login' }])
  const login = env.page('pages/login/login')
  login.onLoad({}); login.onShow(); login.consent({ detail: { value: ['yes'] } })
  const session = env.load(path.join(native, 'utils/session.js')); const before = session.revision()
  const pending = login.wechat(); await flush()
  env.calls.requests[0].success({ statusCode: 200, data: { code: 0, data: { token: 'new-wechat-token', userId: 1, role: 'USER', nickname: '平台昵称' } } })
  await flush()
  assert.equal(session.revision(), before + 1)
  assert.equal(env.calls.requests.length, 2, 'me must use a new scope after session.save')
  assert.equal(env.calls.navigation.length, 0, 'profile must remain on the login page')
  const req = env.calls.requests[1]
  assert.ok(req.url.endsWith('/user/me')); assert.equal(req.header.Authorization, 'Bearer new-wechat-token')
  req.success({ statusCode: 200, data: { code: 0, data: profileValue() } }); await pending
  assert.equal(login.data.loaded, true); assert.equal(login.data.profileStage, true)
  login.browse(); assert.equal(env.calls.navigation.at(-1).back, true)
})
test('profile: complete returning WeChat profile continues; from=my always allows editing', async () => {
  for (const from of ['login', 'my']) {
    const env = environment(); env.setPages([{ route: 'pages/order/create' }, { route: 'pages/profile/profile' }])
    const page = await readyProfile(env, { async me() { return profileValue({ mobile: '138****8000', mobileBound: true }) } }, from)
    assert.equal(env.calls.navigation.length, from === 'login' ? 1 : 0)
    assert.equal(page.data.mobile, '138****8000')
    assert.equal(page.data.mobileBound, true)
  }
})
test('profile: nickname cache update preserves drafts, pending order and session revision', async () => {
  const env = environment()
  const page = await readyProfile(env, { async me() { return profileValue() }, async updateProfile(value) { return profileValue({ nickname: value }) } })
  const session = env.load(path.join(native, 'utils/session.js')); const revision = session.revision()
  const draft = { owner: 1, payload: { goodsDesc: '保留的草稿' } }; const pending = { owner: 1, id: 88 }
  env.storage.set('native_draft', draft); env.storage.set('native_pending_order', pending)
  page.input({ detail: { value: '  新昵称  ' } }); await page.save()
  assert.equal(env.storage.get('feilv_nickname'), '新昵称'); assert.equal(page.data.nickname, '新昵称')
  assert.equal(session.revision(), revision); assert.equal(env.storage.get('native_draft'), draft); assert.equal(env.storage.get('native_pending_order'), pending)
  assert.equal(env.storage.get('feilv_token'), 'token-1')
  assert.equal(session.updateNickname('越权', 2, revision), false)
  assert.equal(session.updateNickname('过期', 1, revision - 1), false)
  assert.equal(env.storage.get('feilv_nickname'), '新昵称')
})
test('profile: nickname validates Unicode codepoints, trims and rejects empty, long or control input', async () => {
  const env = environment(); const submitted = []
  const page = await readyProfile(env, { async me() { return profileValue() }, async updateProfile(value) { submitted.push(value); return profileValue({ nickname: value }) } })
  for (const value of ['  ', 'a'.repeat(33), '昵称\n', 'ab', 'ab', '😀'.repeat(33)]) {
    page.input({ detail: { value } }); await page.save()
  }
  assert.equal(submitted.length, 0)
  page.input({ detail: { value: '  ' + '😀'.repeat(32) + '  ' } }); await page.save()
  assert.equal(Array.from(submitted[0]).length, 32)
})
test('profile: denial, cancellation and old encrypted response without code send nothing and keep login', async () => {
  const env = environment(); let bindings = 0
  const page = await readyProfile(env, { async me() { return profileValue() }, async bindWechatPhone() { bindings++ } })
  for (const detail of [
    { errMsg: 'getPhoneNumber:fail user deny', code: 'ignored' },
    { errMsg: 'getPhoneNumber:fail user cancel' },
    { errMsg: 'getPhoneNumber:ok', encryptedData: 'legacy', iv: 'legacy', phoneNumber: '13800000000' },
    { errMsg: 'getPhoneNumber:ok', code: '   ' }
  ]) { page.preparePhone(); await page.bindPhone({ detail }) }
  assert.equal(bindings, 0); assert.equal(env.storage.get('feilv_token'), 'token-1'); assert.equal(env.calls.navigation.length, 0)
})
test('profile: binding request sends only getPhoneNumber code; draft nickname remains unsaved', async () => {
  const env = environment(); env.authenticated()
  const page = env.page('pages/profile/profile'); page.onLoad({ from: 'my' }); const loading = page.onShow()
  env.calls.requests[0].success({ statusCode: 200, data: { code: 0, data: profileValue() } }); await loading
  page.input({ detail: { value: '尚未保存的名字' } }); page.preparePhone()
  const pending = page.bindPhone({ detail: { errMsg: 'getPhoneNumber:ok', code: 'phone-authorization-code', phoneNumber: '13800000000', encryptedData: 'unused', iv: 'unused' } })
  const req = env.calls.requests[1]
  assert.ok(req.url.endsWith('/user/wechat-phone')); assert.equal(req.method, 'POST')
  assert.deepEqual(Object.keys(req.data), ['code']); assert.equal(req.data.code, 'phone-authorization-code')
  assert.notEqual(req.data.code, 'temporary-wx-code')
  req.success({ statusCode: 200, data: { code: 0, data: profileValue({ mobile: '138****8000', mobileBound: true }) } }); await pending
  assert.equal(page.data.nickname, '尚未保存的名字'); assert.equal(page.data.savedNickname, '平台昵称')
  assert.equal(env.storage.get('feilv_nickname'), '平台昵称')
  assert.equal(page.data.mobile, '138****8000'); assert.equal(env.calls.requests.length, 2)
  page.complete(); assert.equal(env.calls.navigation.length, 0, 'complete must not silently discard edits')
  for (const [key, value] of env.storage) { assert.equal(/mobile|phone/i.test(key), false); assert.notEqual(value, '13800000000') }
})
test('profile: nickname PUT contract is limited to nickname and masked server response is not cached', async () => {
  const env = environment(); env.authenticated()
  const page = env.page('pages/profile/profile'); page.onLoad({ from: 'my' }); const loading = page.onShow()
  env.calls.requests[0].success({ statusCode: 200, data: { code: 0, data: profileValue() } }); await loading
  page.input({ detail: { value: '  保存名  ' } }); const saving = page.save()
  const req = env.calls.requests[1]
  assert.ok(req.url.endsWith('/user/profile')); assert.equal(req.method, 'PUT'); assert.deepEqual(Object.keys(req.data), ['nickname']); assert.equal(req.data.nickname, '保存名')
  req.success({ statusCode: 200, data: { code: 0, data: profileValue({ nickname: '保存名', mobile: '已绑定', mobileBound: true }) } }); await saving
  assert.equal(page.data.mobileBound, true); assert.equal(page.data.mobile, '')
  page.preparePhone(); await page.bindPhone(phoneEvent('new-code')); assert.equal(env.calls.requests.length, 2)
  assert.equal(env.storage.get('feilv_nickname'), '保存名')
})
test('profile: skip returns prior business page or falls back to My without writes', async () => {
  for (const count of [1, 2]) {
    const env = environment(); env.setPages(Array.from({ length: count }, () => ({ route: 'pages/profile/profile' })))
    const page = await readyProfile(env, { async me() { return profileValue() } }, 'login')
    page.input({ detail: { value: '不保存' } }); page.leave(); page.leave()
    assert.equal(env.calls.navigation.length, 1)
    if (count === 2) assert.equal(env.calls.navigation[0].back, true)
    else assert.equal(env.calls.navigation[0].url, '/pages/my/my')
    assert.equal(env.storage.get('feilv_nickname'), '平台昵称')
  }
})
test('profile: repeat binding and consumed authorization code cannot send duplicate requests', async () => {
  const env = environment(); let complete; let calls = 0
  const page = await readyProfile(env, { async me() { return profileValue() }, bindWechatPhone() { calls++; return new Promise(resolve => { complete = resolve }) } })
  page.preparePhone(); const pending = page.bindPhone(phoneEvent('single-use-code'))
  page.preparePhone(); await page.bindPhone(phoneEvent('single-use-code')); assert.equal(calls, 1)
  complete(profileValue()); await pending
  page.preparePhone(); await page.bindPhone(phoneEvent('single-use-code')); assert.equal(calls, 1)
  page.preparePhone(); const again = page.bindPhone(phoneEvent('fresh-code')); assert.equal(calls, 2)
  complete(profileValue({ mobile: '138****8000', mobileBound: true })); await again
  page.preparePhone(); await page.bindPhone(phoneEvent('third-code')); assert.equal(calls, 2)
})
test('profile: binding conflict/network failure retains authenticated session and nickname draft', async () => {
  for (const message of ['手机号已绑定其他账号', '网络异常']) {
    const env = environment()
    const page = await readyProfile(env, { async me() { return profileValue() }, async bindWechatPhone() { throw new Error(message) } })
    page.input({ detail: { value: '保留编辑' } }); page.preparePhone(); await page.bindPhone(phoneEvent('code'))
    assert.equal(page.data.error, message); assert.equal(page.data.nickname, '保留编辑')
    assert.equal(page.data.mobileBound, false); assert.equal(env.storage.get('feilv_token'), 'token-1'); assert.equal(env.calls.navigation.length, 0)
  }
})
test('profile: stale account me/save/phone responses never update cache, UI or navigation', async () => {
  for (const operation of ['me', 'save', 'phone']) {
    const env = environment(); env.authenticated(); let respond
    const api = { async me() { return profileValue() }, updateProfile: () => new Promise(resolve => { respond = resolve }), bindWechatPhone: () => new Promise(resolve => { respond = resolve }) }
    if (operation === 'me') api.me = () => new Promise(resolve => { respond = resolve })
    const page = env.page('pages/profile/profile', api); page.onLoad({ from: 'my' })
    let pending = page.onShow()
    if (operation !== 'me') {
      await pending
      if (operation === 'save') { page.input({ detail: { value: 'A新昵称' } }); pending = page.save() }
      else { page.preparePhone(); pending = page.bindPhone(phoneEvent('A-code')) }
    }
    const session = env.load(path.join(native, 'utils/session.js'))
    session.save({ token: 'B-token', userId: 2, role: 'USER', nickname: 'B昵称' })
    respond(profileValue({ nickname: 'A秘密昵称', mobile: '138****8000', mobileBound: true })); await pending
    assert.notEqual(page.data.nickname, 'A秘密昵称'); assert.equal(env.storage.get('feilv_nickname'), 'B昵称'); assert.equal(env.calls.navigation.length, 0)
  }
})
test('profile: late phone event after account change, hide or unload is discarded', async () => {
  for (const state of ['switch', 'hide', 'unload']) {
    const env = environment(); let calls = 0
    const page = await readyProfile(env, { async me() { return profileValue() }, async bindWechatPhone() { calls++; return profileValue() } })
    page.preparePhone()
    if (state === 'switch') env.load(path.join(native, 'utils/session.js')).save({ token: 'B', userId: 2, role: 'USER', nickname: 'B' })
    else if (state === 'hide') page.onHide()
    else page.onUnload()
    await page.bindPhone(phoneEvent('old-code')); assert.equal(calls, 0)
  }
})
test('profile: privacy agreement is separate, verified, and required before native phone intent', async () => {
  let needed = true; let opens = 0; let binds = 0
  const env = environment({ getPrivacySetting(options) { options.success({ needAuthorization: needed, privacyContractName: '隐私保护指引' }) }, openPrivacyContract(options) { opens++; options.success({}) } })
  const page = await readyProfile(env, { async me() { return profileValue() }, async bindWechatPhone() { binds++; return profileValue() } })
  assert.equal(page.data.privacyNeeded, true); assert.equal(page.data.privacyReady, false)
  page.preparePhone(); await page.bindPhone(phoneEvent('no-privacy')); assert.equal(binds, 0)
  await page.openPrivacy(); assert.equal(opens, 1)
  await page.agreePrivacy(); assert.equal(page.data.privacyReady, false, 'unconfirmed/denied agreement must not unlock phone')
  needed = false; await page.agreePrivacy(); assert.equal(page.data.privacyReady, true)
  page.preparePhone(); await page.bindPhone(phoneEvent('privacy-approved')); assert.equal(binds, 1)
})
test('profile: privacy query failure, old version and missing capabilities fail safely without logout', async () => {
  for (const override of [
    { getPrivacySetting(options) { options.fail({ errMsg: 'failed' }) } },
    { canIUse() { return false } },
    { canIUse: undefined },
    { getAppBaseInfo() { return { SDKVersion: '2.21.1' } } },
    { getPrivacySetting(options) { options.success({ needAuthorization: true }) }, canIUse: value => value !== 'button.open-type.agreePrivacyAuthorization' }
  ]) {
    const env = environment(override); let calls = 0
    const page = await readyProfile(env, { async me() { return profileValue() }, async bindWechatPhone() { calls++ } })
    page.preparePhone(); await page.bindPhone(phoneEvent('blocked')); assert.equal(calls, 0)
    assert.equal(env.storage.get('feilv_token'), 'token-1'); page.leave(); assert.equal(env.calls.navigation.length, 1)
  }
  const env = environment({ getPrivacySetting: undefined })
  const page = await readyProfile(env, { async me() { return profileValue() } })
  assert.equal(page.data.phoneSupported, true); assert.equal(page.data.privacyReady, true)
})
test('profile: late privacy callbacks cannot mutate hidden, unloaded or replacement-account page', async () => {
  for (const change of ['hide', 'unload', 'switch']) {
    let respond
    const env = environment({ getPrivacySetting(options) { respond = options } }); env.authenticated()
    const page = env.page('pages/profile/profile', { async me() { return profileValue() } }); page.onLoad({ from: 'my' })
    const loading = page.onShow(); await flush()
    if (change === 'switch') env.load(path.join(native, 'utils/session.js')).save({ token: 'B', userId: 2, role: 'USER', nickname: 'B' })
    else if (change === 'hide') page.onHide()
    else page.onUnload()
    respond.success({ needAuthorization: false }); await loading
    assert.equal(page.data.privacyReady, false); assert.equal(env.calls.navigation.length, 0)
  }
})
test('profile: My entry and refresh preserve draft nickname without resetting revision', async () => {
  const env = environment(); env.authenticated(); const session = env.load(path.join(native, 'utils/session.js'))
  const page = env.page('pages/my/my', { async me() { return profileValue({ nickname: '服务端昵称', mobileBound: true, mobile: '138****8000' }) } })
  page.onLoad({}); env.storage.set('native_draft', { owner: 1 }); const revision = session.revision(); await page.onShow()
  assert.equal(page.data.nickname, '服务端昵称'); assert.equal(env.storage.get('feilv_nickname'), '服务端昵称')
  assert.equal(session.revision(), revision); assert.equal(env.storage.has('native_draft'), true)
  page.profile(); assert.equal(env.calls.navigation.at(-1).url, '/pages/profile/profile?from=my')
  const profile = await readyProfile(env, { async me() { return profileValue() } })
  profile.input({ detail: { value: '未保存编辑' } }); await profile.load(); assert.equal(profile.data.nickname, '未保存编辑')
})
test('login: SMS reads inline profile using the new authenticated session', async () => {
  for (const count of [1, 2]) {
    const env = environment(); env.setPages(Array.from({ length: count }, () => ({ route: 'pages/login/login' })))
    const page = env.page('pages/login/login'); page.onLoad({}); page.onShow()
    page.consent({ detail: { value: ['yes'] } }); page.input(event('mobile', '13800000000')); page.input(event('code', '123456'))
    const pending = page.smsLogin(); const req = env.calls.requests[0]
    assert.ok(req.url.endsWith('/auth/login')); assert.equal(req.data.mobile, '13800000000'); assert.equal(req.data.code, '123456')
    req.success({ statusCode: 200, data: { code: 0, data: { token: 'sms-token', userId: 1, role: 'USER', nickname: '短信用户' } } }); await flush()
    assert.equal(env.storage.get('feilv_token'), 'sms-token')
    assert.equal(env.calls.navigation.length, 0)
    assert.equal(env.calls.requests[1].header.Authorization, 'Bearer sms-token')
    env.calls.requests[1].success({ statusCode: 200, data: { code: 0, data: profileValue() } }); await pending
    assert.equal(page.data.profileStage, true); assert.equal(page.data.loaded, true)
  }
})
test('profile: native privacy APIs never use getUserProfile or declare getPhoneNumber as requiredPrivateInfos', () => {
  const app = JSON.parse(fs.readFileSync(path.join(native, 'app.json'), 'utf8'))
  assert.ok(app.pages.includes('pages/profile/profile')); assert.equal(app.requiredPrivateInfos.includes('getPhoneNumber'), false)
  const source = fs.readFileSync(path.join(native, 'utils/profile-page.js'), 'utf8')
  const template = fs.readFileSync(path.join(native, 'pages/profile/profile.wxml'), 'utf8')
  assert.equal(/wx\.(?:getUserProfile|login)\s*\(/.test(source), false)
  assert.ok(template.includes('type="nickname"')); assert.ok(template.includes('open-type="getPhoneNumber"')); assert.ok(template.includes('open-type="agreePrivacyAuthorization"'))
})
const AVATAR = '/api/v1/avatars/12345678-1234-1234-1234-123456789abc.png'
function selectAvatar(page, path = 'wxfile://tmp/avatar') { page.prepareAvatar(); page.chooseAvatar({ detail: { avatarUrl: path } }) }
function fillCreate(page) {
  Object.assign(page.data.form, { fromContact: '甲', fromPhone: '13800000000', fromAddress: 'A', toContact: '乙', toPhone: '13900000000', toAddress: 'B', goodsDesc: '文件' })
  page.data.distanceKm = '2'; page.data.weightKg = '5'
  page._quotedPayload = page.buildPayload(); page.data.quote = { totalText: '5.00' }
}
test('avatar: multipart bearer upload parses string Result; API /api suffix and avatar origin resolve correctly', async () => {
  const env = environment(); env.authenticated()
  env.load(path.join(native, 'config.js')).BASE_URL = 'https://service.example/api/'
  const transport = env.load(path.join(native, 'utils/request.js'))
  assert.equal(transport.apiURL('/api/v1/user/me'), 'https://service.example/api/v1/user/me')
  assert.equal(transport.avatarURL(AVATAR), 'https://service.example' + AVATAR)
  for (const bad of ['wxfile://tmp/avatar', 'https://other.example' + AVATAR, '//other.example/a.png', '/api/v1/avatars/../secret']) assert.equal(transport.avatarURL(bad), '')
  const pending = env.load(path.join(native, 'utils/api.js')).uploadAvatar('wxfile://tmp/avatar')
  await flush()
  const req = env.calls.uploads[0]
  assert.equal(req.url, 'https://service.example/api/v1/user/avatar'); assert.equal(req.name, 'file')
  assert.equal(req.filePath, 'wxfile://tmp/avatar'); assert.deepEqual(Object.keys(req.header), ['Authorization'])
  assert.equal(req.header.Authorization, 'Bearer token-1')
  req.success({ statusCode: 200, data: JSON.stringify({ code: 0, data: profileValue({ avatarUrl: AVATAR }) }) })
  assert.equal((await pending).avatarUrl, AVATAR)
})
test('avatar: file validation rejects oversized, empty, unsupported and unreadable files before upload', async () => {
  for (const override of [
    { getFileInfo(o) { o.success({ size: 5 * 1024 * 1024 + 1 }) } },
    { getFileInfo(o) { o.success({ size: 0 }) } },
    { getFileInfo(o) { o.fail({}) } },
    { getImageInfo(o) { o.success({ type: 'gif' }) } },
    { getImageInfo(o) { o.fail({}) } }
  ]) {
    const env = environment(override); env.authenticated()
    await assert.rejects(env.load(path.join(native, 'utils/api.js')).uploadAvatar('wxfile://tmp/file'))
    assert.equal(env.calls.uploads.length, 0); assert.equal(env.storage.get('feilv_token'), 'token-1')
  }
})
test('avatar: HTTP/business 401 clear session; other upload errors retain it', async () => {
  for (const response of [
    { statusCode: 401, data: '{}' }, { statusCode: 200, data: '{"code":401}' },
    { statusCode: 413, data: '{"code":413,"msg":"too large"}' },
    { statusCode: 200, data: '{"code":400,"msg":"invalid image"}' },
    { statusCode: 200, data: 'not json' }, null
  ]) {
    const env = environment(); env.authenticated()
    const pending = env.load(path.join(native, 'utils/api.js')).uploadAvatar('wxfile://tmp/file'); await flush()
    if (response) env.calls.uploads[0].success(response); else env.calls.uploads[0].fail({ errMsg: 'timeout' })
    await assert.rejects(pending)
    const unauthorized = response && (response.statusCode === 401 || response.data === '{"code":401}')
    assert.equal(env.storage.has('feilv_token'), !unauthorized)
    assert.equal(env.calls.navigation.length, unauthorized ? 1 : 0)
  }
})
test('avatar: late success, 401 and failure from switched account cannot clear or overwrite new session', async () => {
  for (const outcome of ['success', '401', 'fail']) {
    const env = environment(); env.authenticated()
    const pending = env.load(path.join(native, 'utils/api.js')).uploadAvatar('wxfile://tmp/file'); await flush()
    const session = env.load(path.join(native, 'utils/session.js'))
    session.save({ token: 'B-token', userId: 2, role: 'USER', nickname: 'B' })
    if (outcome === 'fail') env.calls.uploads[0].fail({})
    else env.calls.uploads[0].success({ statusCode: 200, data: JSON.stringify(outcome === '401' ? { code: 401 } : { code: 0, data: profileValue({ avatarUrl: AVATAR }) }) })
    await assert.rejects(pending, e => e.silent === true)
    assert.equal(env.storage.get('feilv_token'), 'B-token'); assert.equal(env.storage.has('feilv_avatar_url'), false); assert.equal(env.calls.navigation.length, 0)
  }
})
test('avatar: account switch during file inspection prevents upload with either account token', async () => {
  let inspect
  const env = environment({ getFileInfo(o) { inspect = o } }); env.authenticated()
  const api = env.load(path.join(native, 'utils/api.js')); const session = env.load(path.join(native, 'utils/session.js'))
  const scoped = api.forSession(session.revision()); const pending = scoped.uploadAvatar('wxfile://tmp/file')
  session.save({ token: 'B', userId: 2, role: 'USER', nickname: 'B' })
  inspect.success({ size: 100 }); await assert.rejects(pending, e => e.silent === true)
  await assert.rejects(scoped.uploadAvatar('wxfile://tmp/file'), e => e.silent === true)
  assert.equal(env.calls.uploads.length, 0)
})
test('profile: chooseAvatar is explicit; successful save persists only server path and preserves nickname draft', async () => {
  const env = environment(); let uploaded = 0
  const page = await readyProfile(env, { async me() { return profileValue() }, async uploadAvatar(p) { uploaded++; assert.equal(p, 'wxfile://tmp/avatar'); return profileValue({ avatarUrl: AVATAR }) } })
  page.chooseAvatar({ detail: { avatarUrl: 'ignored' } }); assert.equal(page.data.avatarDraft, '')
  page.input({ detail: { value: '尚未保存' } }); selectAvatar(page)
  assert.equal(uploaded, 0); assert.equal(env.storage.has('feilv_avatar_url') && !!env.storage.get('feilv_avatar_url'), false)
  const session = env.load(path.join(native, 'utils/session.js')); const revision = session.revision()
  env.storage.set('native_draft', { owner: 1 }); await page.saveAvatar()
  assert.equal(page.data.avatarDraft, ''); assert.equal(page.data.nickname, '尚未保存')
  assert.equal(page.data.avatarUrl, 'https://xt.allms.cn' + AVATAR); assert.equal(env.storage.get('feilv_avatar_url'), AVATAR)
  assert.equal(session.revision(), revision); assert.equal(env.storage.has('native_draft'), true)
  for (const value of env.storage.values()) assert.notEqual(value, 'wxfile://tmp/avatar')
  const my = env.page('pages/my/my', { async me() { return profileValue({ avatarUrl: AVATAR }) } }); my.onLoad({}); await my.onShow()
  assert.equal(my.data.avatarUrl, 'https://xt.allms.cn' + AVATAR)
  const reopened = await readyProfile(env, { async me() { return profileValue({ avatarUrl: AVATAR }) } })
  assert.equal(reopened.data.avatarUrl, 'https://xt.allms.cn' + AVATAR)
  session.clear(); assert.equal(env.storage.has('feilv_avatar_url'), false)
})
test('profile: failed avatar upload retains preview for retry without changing saved avatar', async () => {
  const env = environment(); let attempts = 0
  const page = await readyProfile(env, { async me() { return profileValue({ avatarUrl: AVATAR }) }, async uploadAvatar() { if (++attempts === 1) throw new Error('上传失败'); return profileValue({ avatarUrl: AVATAR }) } })
  selectAvatar(page); await page.saveAvatar()
  assert.equal(page.data.avatarDraft, 'wxfile://tmp/avatar'); assert.equal(page.data.error, '上传失败'); assert.equal(env.storage.get('feilv_avatar_url'), AVATAR)
  await page.saveAvatar(); assert.equal(page.data.avatarDraft, ''); assert.equal(attempts, 2)
})
test('profile: late avatar events and upload results cannot cross accounts or hidden pages', async () => {
  for (const state of ['switch', 'hide', 'unload']) {
    const env = environment(); let resolve
    const page = await readyProfile(env, { async me() { return profileValue() }, uploadAvatar() { return new Promise(r => { resolve = r }) } })
    selectAvatar(page); const pending = page.saveAvatar()
    if (state === 'switch') env.load(path.join(native, 'utils/session.js')).save({ token: 'B', userId: 2, role: 'USER', nickname: 'B' })
    else if (state === 'hide') page.onHide(); else page.onUnload()
    resolve(profileValue({ avatarUrl: AVATAR })); await pending
    assert.notEqual(env.storage.get('feilv_avatar_url'), AVATAR); assert.equal(page.data.avatarUrl, '')
    page.chooseAvatar({ detail: { avatarUrl: 'late-temp' } }); assert.notEqual(page.data.avatarDraft, 'late-temp')
  }
})
test('profile: complete requires loaded saved non-default nickname and bound phone but never requires avatar', async () => {
  const env = environment()
  const page = await readyProfile(env, { async me() { return profileValue({ nickname: '微信用户' }) }, async updateProfile(nickname) { return profileValue({ nickname }) }, async bindWechatPhone() { return profileValue({ nickname: '主动昵称', mobileBound: true }) } }, 'login')
  assert.equal(env.calls.navigation.length, 0); page.complete(); assert.equal(env.calls.navigation.length, 0)
  page.input({ detail: { value: '主动昵称' } }); page.complete(); assert.equal(env.calls.navigation.length, 0)
  await page.save(); page.complete(); assert.equal(env.calls.navigation.length, 0)
  page.preparePhone(); await page.bindPhone(phoneEvent('phone-code')); page.complete()
  assert.equal(env.calls.navigation.at(-1).url, '/pages/my/my'); assert.equal(page.data.avatarUrl, '')
})
test('orders: create and confirm query real me, block incomplete profiles, retain drafts and allow retry after binding', async () => {
  for (const route of ['create', 'confirm']) {
    for (const incomplete of [{ mobileBound: false }, { mobileBound: true, nickname: '' }, { mobileBound: true, nickname: '微信用户' }]) {
      const env = environment(); env.authenticated(); let bound = false; let reads = 0; let creates = 0
      const api = { async addressList() { return [] }, async quote() { return { totalFen: 500, items: [] } }, async me() { reads++; return profileValue(bound ? { mobileBound: true } : incomplete) }, async createOrder() { creates++; return { id: 88 } }, async payOrder() {} }
      env.storage.set('native_draft', { owner: 1, savedAt: 12, payload: { orderType: 'SEND' }, quote: {} })
      const page = env.page('pages/order/' + route, api); page.onLoad({}); await page.onShow()
      if (route === 'create') fillCreate(page)
      const submit = () => route === 'create' ? page.next() : page.submit()
      await submit()
      assert.equal(reads, 1); assert.equal(creates, 0); assert.equal(env.storage.has('native_draft'), true)
      assert.equal(env.calls.navigation.at(-1).url, '/pages/profile/profile?from=order'); assert.equal(env.storage.get('feilv_token'), 'token-1')
      const saved = env.storage.get('native_draft'); bound = true; await submit()
      assert.equal(reads, 2)
      if (route === 'create') { assert.equal(env.calls.navigation.at(-1).url, '/pages/order/confirm'); assert.equal(env.storage.get('native_draft').savedAt, saved.savedAt) }
      else { assert.equal(creates, 1); assert.equal(env.storage.has('native_draft'), false) }
    }
  }
})
test('orders: me network failure and switched-account response never create, navigate or discard another draft', async () => {
  for (const route of ['create', 'confirm']) {
    for (const mode of ['failure', 'switch']) {
      const env = environment(); env.authenticated(); let respond; let creates = 0
      env.storage.set('native_draft', { owner: 1, savedAt: 12, payload: { orderType: 'SEND' }, quote: {} })
      const page = env.page('pages/order/' + route, { async addressList() { return [] }, me() { if (mode === 'failure') throw new Error('me失败'); return new Promise(r => { respond = r }) }, async createOrder() { creates++ } })
      page.onLoad({}); await page.onShow(); if (route === 'create') fillCreate(page)
      const pending = route === 'create' ? page.next() : page.submit()
      if (mode === 'switch') {
        env.load(path.join(native, 'utils/session.js')).save({ token: 'B', userId: 2, role: 'USER', nickname: 'B' })
        env.storage.set('native_draft', { owner: 2, savedAt: 99 })
        respond(profileValue({ mobileBound: true }))
      }
      await pending
      assert.equal(creates, 0); assert.equal(env.calls.navigation.length, 0)
      assert.equal(env.storage.get('native_draft').owner, mode === 'switch' ? 2 : 1)
    }
  }
})
test('orders: backend 4091 redirects to profile with draft intact and no uncertain-create deadlock', async () => {
  const env = environment(); env.authenticated()
  env.storage.set('native_draft', { owner: 1, savedAt: 12, payload: { orderType: 'SEND' }, quote: {} })
  const page = env.page('pages/order/confirm', { async me() { return profileValue({ mobileBound: true }) }, async createOrder() { throw Object.assign(new Error('请绑定手机号'), { code: 4091 }) } })
  page.onLoad({}); page.onShow(); await page.submit()
  assert.equal(page.data.uncertain, false); assert.equal(env.storage.has('native_pending_order'), false)
  assert.equal(env.storage.get('native_draft').savedAt, 12); assert.equal(env.calls.navigation.at(-1).url, '/pages/profile/profile?from=order')
})
test('login: home order intent resumes after profile return without trapping browsing on cancellation', async () => {
  const env = environment(); const home = env.page('pages/index/index', {})
  home.onLoad({}); home.onShow(); home.create({ currentTarget: { dataset: { type: 'BUY' } } })
  assert.equal(env.calls.navigation.at(-1).url, '/pages/login/login')
  home.onHide(); home.onShow(); assert.equal(env.calls.navigation.length, 1)
  env.load(path.join(native, 'utils/session.js')).save({ token: 'new', userId: 1, role: 'USER', nickname: '微信用户' })
  home.onShow(); assert.equal(env.calls.navigation.at(-1).url, '/pages/order/create?type=BUY')
  home.onHide(); home.onShow(); assert.equal(env.calls.navigation.length, 2)
})
test('copy: avatar selection and required phone are explicit; no legacy no-avatar or optional-phone claims', () => {
  const source = ['pages/profile/profile.wxml', 'pages/login/login.wxml', 'pages/my/my.wxml', 'pages/legal/legal.js', 'README.md', 'WECHAT_DEPLOYMENT.md'].map(f => fs.readFileSync(path.join(native, f), 'utf8')).join('\n')
  assert.equal(/无需另设用户名或头像|不设置独立用户名或采集头像|绑定手机号为可选|尚未绑定手机号（可选）/.test(source), false)
  assert.ok(source.includes('open-type="chooseAvatar"')); assert.ok(source.includes('下单必填')); assert.ok(source.includes('生产'))
})
async function inlineLogin(env, api) {
  env.authenticated()
  const page = env.page('pages/login/login', api)
  page.onLoad({}); await page.onShow()
  return page
}
test('login inline: returning complete account leaves directly without another profile route', async () => {
  const env = environment(); env.setPages([{ route: 'pages/index/index' }, { route: 'pages/login/login' }])
  const page = await inlineLogin(env, { async me() { return profileValue({ mobileBound: true, avatarUrl: AVATAR }) } })
  assert.equal(page.data.profileStage, true)
  assert.equal(env.calls.navigation.length, 1); assert.equal(env.calls.navigation[0].back, true)
})
test('login inline: one save uploads chosen avatar and nickname then verifies me before leaving', async () => {
  const env = environment(); const calls = []; let value = profileValue({ nickname: '微信用户' })
  const page = await inlineLogin(env, {
    async me() { calls.push('me'); return value },
    async bindWechatPhone(code) { calls.push(code); value = Object.assign({}, value, { mobileBound: true, mobile: '138****8000' }); return value },
    async uploadAvatar(file) { calls.push(file); value = Object.assign({}, value, { avatarUrl: AVATAR }); return value },
    async updateProfile(nickname) { calls.push(nickname); value = Object.assign({}, value, { nickname }); return value }
  })
  page.nicknameInput({ detail: { value: '  新昵称  ' } }); selectAvatar(page)
  page.preparePhone(); await page.bindPhone(phoneEvent('phone-once'))
  assert.equal(page.data.nickname, '  新昵称  '); assert.equal(env.calls.navigation.length, 0)
  const saving = page.complete(); await page.complete(); await saving
  assert.deepEqual(calls, ['me', 'phone-once', 'wxfile://tmp/avatar', '新昵称', 'me'])
  assert.equal(env.storage.get('feilv_avatar_url'), AVATAR); assert.equal(env.storage.get('feilv_nickname'), '新昵称')
  assert.equal(env.calls.navigation.at(-1).url, '/pages/my/my')
  assert.equal(page.data.avatarDraft, ''); assert.equal(page.data.saving, false)
})
test('login inline: save validates phone and nickname; partial upload success is reused on retry', async () => {
  const env = environment(); let value = profileValue({ nickname: '微信用户' }); let uploads = 0; let saves = 0
  const page = await inlineLogin(env, {
    async me() { return value },
    async uploadAvatar() { uploads++; value = Object.assign({}, value, { avatarUrl: AVATAR }); return value },
    async updateProfile(nickname) { if (++saves === 1) throw new Error('昵称保存失败'); value = Object.assign({}, value, { nickname }); return value }
  })
  await page.complete(); assert.equal(saves, 0)
  page.nicknameInput({ detail: { value: '新昵称' } }); selectAvatar(page)
  await page.complete(); assert.equal(uploads, 0, 'no writes until phone is bound')
  value = Object.assign({}, value, { mobileBound: true }); page.applyProfile(value, page._profileRevision, page._profileUserId, true)
  await page.complete(); assert.equal(uploads, 1); assert.equal(page.data.avatarDraft, '')
  assert.equal(page.data.nickname, '新昵称'); assert.equal(env.calls.navigation.length, 0)
  await page.complete(); assert.equal(uploads, 1); assert.equal(saves, 2); assert.equal(env.calls.navigation.length, 1)
})
test('login inline: failed me can retry without repeating login or discarding account', async () => {
  const env = environment(); let attempts = 0
  const page = await inlineLogin(env, { async me() { if (++attempts === 1) throw new Error('读取失败'); return profileValue() } })
  assert.equal(page.data.profileStage, true); assert.equal(page.data.loaded, false); assert.equal(page.data.error, '读取失败')
  assert.equal(env.storage.get('feilv_token'), 'token-1'); await page.load()
  assert.equal(page.data.loaded, true); assert.equal(page.data.error, ''); assert.equal(env.calls.navigation.length, 0)
})
test('login inline: account switch during upload prevents nickname writes and late navigation', async () => {
  const env = environment(); let resolve; let writes = 0
  const page = await inlineLogin(env, { async me() { return profileValue({ nickname: '微信用户', mobileBound: true }) },
    uploadAvatar() { return new Promise(r => { resolve = r }) }, async updateProfile() { writes++; return profileValue() } })
  page.nicknameInput({ detail: { value: '新昵称' } }); selectAvatar(page)
  const pending = page.complete(); await flush()
  const session = env.load(path.join(native, 'utils/session.js'))
  session.save({ token: 'other-token', userId: 2, role: 'USER', nickname: '其他用户' })
  resolve(profileValue({ avatarUrl: AVATAR })); await pending
  assert.equal(writes, 0); assert.equal(env.storage.get('feilv_nickname'), '其他用户'); assert.equal(env.calls.navigation.length, 0)
})
test('login inline: expired inline session restores login entry instead of stranding profile form', async () => {
  const env = environment(); env.setPages([{ route: 'pages/login/login' }])
  const page = env.page('pages/login/login'); page.onLoad({}); page.onShow(); page.consent({ detail: { value: ['yes'] } })
  const pending = page.wechat(); await flush()
  env.calls.requests[0].success({ statusCode: 200, data: { code: 0, data: { token: 'new', userId: 1, role: 'USER', nickname: '微信用户' } } })
  await flush()
  env.calls.requests[1].success({ statusCode: 401, data: { code: 401, msg: '过期' } }); await pending
  assert.equal(page.data.profileStage, false); assert.equal(page.data.busy, false); assert.equal(env.storage.has('feilv_token'), false)
  assert.equal(env.calls.navigation.length, 0)
  page.consent({ detail: { value: ['yes'] } }); const retry = page.wechat(); await flush()
  assert.equal(env.calls.requests.length, 3)
  env.calls.requests[2].fail({}); await retry
})
test('login inline: native UI uses explicit avatar and nickname choices without obsolete profile APIs', () => {
  const template = fs.readFileSync(path.join(native, 'pages/login/login.wxml'), 'utf8')
  const source = fs.readFileSync(path.join(native, 'pages/login/login.js'), 'utf8')
  assert.ok(template.includes('open-type="chooseAvatar"')); assert.ok(template.includes('type="nickname"'))
  assert.ok(template.includes('open-type="getPhoneNumber"')); assert.ok(template.includes('保存并继续'))
  assert.equal(/getUserProfile|getUserInfo|redirectTo/.test(source), false)
})
test('login layout: signed-in form has compact welcome, grouped fields and separate save actions', () => {
  const template = fs.readFileSync(path.join(native, 'pages/login/login.wxml'), 'utf8')
  const styles = fs.readFileSync(path.join(native, 'pages/login/login.wxss'), 'utf8')
  assert.ok(template.includes('wx:if="{{!profileStage}}" class="login-intro"'))
  assert.ok(template.includes('wx:else class="profile-welcome"'))
  assert.ok(template.includes('class="profile-field nickname-field"'))
  assert.ok(template.includes('class="profile-field phone-field"'))
  assert.ok(template.includes('class="profile-save-actions"'))
  assert.ok(template.includes('class="profile-privacy-note"'))
  assert.ok(template.includes('class="bound-number"'))
  assert.ok(styles.includes('.field-label-row .profile-label'))
  assert.ok(styles.includes('.identity-preview .profile-avatar-button'))
  assert.ok(styles.includes('@media (max-width: 350px)'))
})
test('main pages: home and account quick links preserve native navigation and login gates', async () => {
  const env = environment()
  const home = env.page('pages/index/index', {}); home.onLoad({}); home.onShow()
  assert.equal(home.orders, undefined); assert.equal(home.address, undefined); assert.equal(home.settings, undefined)
  home.create({ currentTarget: { dataset: { type: 'SEND' } } }); assert.equal(env.calls.navigation.at(-1).url, '/pages/login/login')
  const my = env.page('pages/my/my', {}); my.onLoad({}); await my.onShow()
  my.orders(); assert.equal(env.calls.navigation.at(-1).url, '/pages/order/list')
  my.address(); assert.equal(env.calls.navigation.at(-1).url, '/pages/login/login')
  env.authenticated(); my.address(); assert.equal(env.calls.navigation.at(-1).url, '/pages/address/address')
  my.profile(); assert.equal(env.calls.navigation.at(-1).url, '/pages/profile/profile?from=my')
})
test('main pages: layouts group identity, services and order routes without exposing extra profile fields', () => {
  const home = fs.readFileSync(path.join(native, 'pages/index/index.wxml'), 'utf8')
  const my = fs.readFileSync(path.join(native, 'pages/my/my.wxml'), 'utf8')
  const orders = fs.readFileSync(path.join(native, 'pages/order/list.wxml'), 'utf8')
  assert.equal(home.includes('class="home-orders"'), false); assert.ok(home.includes('MOCK')); assert.ok(home.includes('绑定手机号'))
  assert.ok(my.includes('class="account-identity"')); assert.ok(my.includes('class="account-info"'))
  assert.ok(my.includes('class="card menu-card account-menu"')); assert.ok(my.includes('bindtap="orders"'))
  assert.ok(my.includes('wx:if="{{isRider}}"')); assert.ok(my.includes('wx:if="{{loggedIn}}"'))
  assert.ok(orders.includes('class="order-route"')); assert.ok(orders.includes('status-{{item.status}}'))
  assert.ok(orders.includes('data-id="{{item.id}}"')); assert.ok(orders.includes('class="amount-value"'))
  assert.equal(/me\.(?:openid|mobileRaw|sessionKey)|item\.userMobile/.test(my + orders), false)
})
const regionAddress = (patch = {}) => Object.assign({ id: 11, contact: '王先生', phone: '13800000000', province: '贵州省', city: '铜仁市', district: '德江县', detailAddress: '青龙街道一号楼', address: '客户端旧地址', poiName: '南门', lng: 108, lat: 28, isDefault: 1 }, patch)
function regionEvent(value) { return { detail: { value } } }
test('address region: new addresses require confirmed region and detail and compose full payload', async () => {
  const env = environment(); env.authenticated(); let saved
  const page = env.page('pages/address/address', { async addAddress(data) { saved = data }, async addressList() { return [] } })
  page.onLoad({}); page._visible = true
  page.input(event('contact', '张三')); page.input(event('phone', '13800000000'))
  page.input(event('detailAddress', '一号楼')); await page.save(); assert.equal(saved, undefined)
  assert.equal(page.data.regionText, '', 'default picker cursor must not count as user selection')
  page.regionChange(regionEvent(['贵州省', '铜仁市', '德江县']))
  assert.equal(page.data.form.detailAddress, '', 'changing region must clear stale street detail')
  page.input(event('detailAddress', ' 青龙街道一号楼 ')); await page.save()
  assert.equal(saved.province, '贵州省'); assert.equal(saved.city, '铜仁市'); assert.equal(saved.district, '德江县')
  assert.equal(saved.detailAddress, '青龙街道一号楼'); assert.equal(saved.address, '贵州省铜仁市德江县青龙街道一号楼')
  assert.equal(saved.lng, null); assert.equal(saved.lat, null); assert.equal(page.data.regionText, '')
})
test('address region: edit restores structured fields; legacy remains readable and needs deliberate completion', () => {
  const env = environment(); const page = env.page('pages/address/address', {}); page.onLoad({})
  page.data.list = [regionAddress(), { id: 12, contact: '旧用户', address: '原街道未知地区', lng: 109, lat: 29 }]
  page.edit({ currentTarget: { dataset: { id: 11 } } })
  assert.equal(page.data.regionText, '贵州省 / 铜仁市 / 德江县'); assert.equal(page.data.form.detailAddress, '青龙街道一号楼')
  page.regionChange(regionEvent(['贵州省', '铜仁市', '德江县'])); assert.equal(page.data.form.lng, 108)
  page.regionChange(regionEvent(['贵州省', '铜仁市', '思南县'])); assert.equal(page.data.form.lng, null); assert.equal(page.data.form.detailAddress, '')
  page.edit({ currentTarget: { dataset: { id: 12 } } })
  assert.equal(page.data.legacyAddress, '原街道未知地区'); assert.equal(page.data.regionText, ''); assert.equal(page.data.form.detailAddress, '')
  page.regionChange(regionEvent(['贵州省', '铜仁市'])); assert.equal(page.data.regionText, '')
})
test('address region: default updates preserve structured fields and selection exposes no owner metadata', async () => {
  const env = environment(); env.authenticated(); let submitted; let emitted
  const value = regionAddress({ userId: 999, extraSecret: 'not-for-client' })
  const page = env.page('pages/address/address', { async updateAddress(id, data) { submitted = data }, async addressList() { return [value] } })
  page.onLoad({ select: '1' }); page._visible = true; page.data.list = [value]
  await page.setDefault({ currentTarget: { dataset: { id: 11 } } })
  assert.equal(submitted.province, '贵州省'); assert.equal(submitted.detailAddress, '青龙街道一号楼')
  page.getOpenerEventChannel = () => ({ emit(name, value) { emitted = value } })
  page.select({ currentTarget: { dataset: { id: 11 } } })
  assert.equal(emitted.id, 11); assert.equal(emitted.province, '贵州省'); assert.equal(emitted.userId, undefined); assert.equal(emitted.extraSecret, undefined)
})
test('address dropdown: selector fills only chosen side, including full address and coordinates, invalidates quote', async () => {
  const env = environment(); env.authenticated()
  const page = env.page('pages/order/create', { async addressList() { return [regionAddress({ isDefault: 0 })] } })
  page.onLoad({}); await page.onShow()
  assert.ok(page.data.addressOptions[0].label.includes('王先生'))
  page.data.quote = {}; page._quotedPayload = {}
  page.addressChange({ currentTarget: { dataset: { side: 'to' } }, detail: { value: '0' } })
  assert.equal(page.data.form.toContact, '王先生'); assert.equal(page.data.form.toPhone, '13800000000')
  assert.equal(page.data.form.toAddress, '南门 贵州省铜仁市德江县青龙街道一号楼'); assert.equal(page.data.form.toLng, 108)
  assert.equal(page.data.form.fromAddress, ''); assert.equal(page.data.quote, null); assert.equal(page.data.toAddressId, 11)
  page.input(event('toAddress', '不能手工覆盖')); assert.ok(page.data.form.toAddress.includes('青龙街道'))
  page.addressChange({ currentTarget: { dataset: { side: 'from' } }, detail: { value: '99' } }); assert.equal(page.data.form.fromAddress, '')
})
test('address dropdown: reloading edited/deleted saved address invalidates stale quote without overwriting contact edits', async () => {
  const env = environment(); env.authenticated(); let list = [regionAddress()]
  const page = env.page('pages/order/create', { async addressList() { return list } }); page.onLoad({}); await page.onShow()
  page.addressChange({ currentTarget: { dataset: { side: 'from' } }, detail: { value: '0' } })
  page.input(event('fromContact', '本单联系人')); page.data.quote = {}
  list = [regionAddress({ detailAddress: '二号楼', lng: null, lat: null })]; await page.loadAddresses()
  assert.equal(page.data.form.fromContact, '本单联系人'); assert.ok(page.data.form.fromAddress.endsWith('二号楼')); assert.equal(page.data.form.fromLng, null); assert.equal(page.data.quote, null)
  page.data.quote = {}; list = []; await page.loadAddresses()
  assert.equal(page.data.form.fromAddress, ''); assert.equal(page.data.fromAddressId, null); assert.equal(page.data.quote, null)
})
test('address dropdown: failure is retryable; old address-list and selection callbacks cannot cross accounts', async () => {
  const env = environment(); env.authenticated(); let resolve; let fail = true
  const page = env.page('pages/order/create', { addressList() { if (fail) throw new Error('读取地址失败'); return new Promise(r => { resolve = r }) } })
  page.onLoad({}); await page.onShow(); assert.equal(page.data.addressError, '读取地址失败'); assert.equal(page.data.addressLoading, false)
  fail = false; page.address({ currentTarget: { dataset: { side: 'to' } } }); const eventChannel = env.calls.navigation.at(-1).events
  const pending = page.loadAddresses()
  env.load(path.join(native, 'utils/session.js')).save({ token: 'other', userId: 2, role: 'USER', nickname: '其他人' })
  eventChannel.addressSelected(regionAddress()); resolve([regionAddress()]); await pending
  assert.equal(page.data.form.toAddress, ''); assert.equal(page.data.addressOptions.length, 0)
})
test('address UI: region picker plus street detail and saved-address dropdown replace freeform order addresses', () => {
  const address = fs.readFileSync(path.join(native, 'pages/address/address.wxml'), 'utf8')
  const order = fs.readFileSync(path.join(native, 'pages/order/create.wxml'), 'utf8')
  assert.ok(address.includes('mode="region"')); assert.ok(address.includes('data-field="detailAddress"'))
  assert.ok(order.includes('range-key="label"')); assert.ok(order.includes('data-side="from" bindchange="addressChange"'))
  assert.ok(order.includes('data-side="to" bindchange="addressChange"'))
  assert.equal(/<input[^>]*data-field="(?:fromAddress|toAddress|address)"/.test(address + order), false)
})
test('phone binding: My exposes explicit entry and phone-only completion never changes nickname or avatar', async () => {
  const env = environment(); env.authenticated(); env.setPages([{ route: 'pages/my/my' }, { route: 'pages/profile/profile' }])
  const my = env.page('pages/my/my', { async me() { return profileValue() } }); my.onLoad({}); await my.onShow()
  assert.equal(my.data.me.mobileBound, false); my.phone(); assert.equal(env.calls.navigation.at(-1).url, '/pages/profile/profile?from=my&mode=phone')
  let binds = 0
  const page = env.page('pages/profile/profile', { async me() { return profileValue({ nickname: '微信用户' }) }, async bindWechatPhone() { binds++; return profileValue({ nickname: '微信用户', mobileBound: true, mobile: '138****8000' }) } })
  page.onLoad({ from: 'my', mode: 'phone' }); await page.onShow(); assert.equal(page.data.phoneOnly, true)
  const before = env.calls.navigation.length; page.complete(); assert.equal(env.calls.navigation.length, before)
  page.preparePhone(); await page.bindPhone(phoneEvent('verified-once'))
  assert.equal(binds, 1); assert.equal(page.data.mobileBound, true); assert.equal(page.data.phoneError, '')
  page.complete(); assert.equal(env.calls.navigation.at(-1).back, true); assert.equal(env.storage.get('feilv_nickname'), '微信用户')
})
test('phone binding: unconfirmed server response cannot show success; fresh authorization permits retry', async () => {
  const env = environment(); let attempts = 0
  const page = await readyProfile(env, { async me() { return profileValue() }, async bindWechatPhone() { attempts++; return profileValue({ mobileBound: attempts > 1, mobile: attempts > 1 ? '138****8000' : '' }) } })
  page.preparePhone(); await page.bindPhone(phoneEvent('first'))
  assert.equal(page.data.mobileBound, false); assert.ok(page.data.phoneError.includes('尚未确认'))
  assert.equal(env.calls.toast.includes('手机号已绑定'), false)
  page.preparePhone(); await page.bindPhone(phoneEvent('second'))
  assert.equal(page.data.mobileBound, true); assert.equal(page.data.phoneError, ''); assert.equal(attempts, 2)
})
test('phone binding: missing capability/code and denial have actionable errors without raw event leakage', async () => {
  const env = environment(); let binds = 0
  const page = await readyProfile(env, { async me() { return profileValue() }, async bindWechatPhone() { binds++ } })
  page.preparePhone(); await page.bindPhone({ detail: { errMsg: 'getPhoneNumber:fail internal-private-value', phoneNumber: '13800138000' } })
  assert.ok(page.data.phoneError.includes('开通手机号能力')); assert.equal(page.data.phoneError.includes('internal-private-value'), false)
  page.preparePhone(); await page.bindPhone(phoneEvent('', 'getPhoneNumber:fail user deny'))
  assert.ok(page.data.phoneError.includes('仍可返回浏览')); assert.equal(binds, 0); assert.equal(env.storage.get('feilv_token'), 'token-1')
})
test('phone binding: server conflict, capacity and expired authorization preserve account and display binding error', async () => {
  for (const code of [400, 409, 429, 503]) {
    const env = environment()
    const page = await readyProfile(env, { async me() { return profileValue() }, async bindWechatPhone() { throw Object.assign(new Error('授权未完成-' + code), { code }) } })
    page.preparePhone(); await page.bindPhone(phoneEvent('code-' + code))
    assert.equal(page.data.mobileBound, false); assert.equal(page.data.binding, false)
    assert.equal(page.data.phoneError, '授权未完成-' + code); assert.equal(env.storage.get('feilv_token'), 'token-1')
  }
})
test('phone binding: phone-only UI hides nickname/avatar forms and exposes native authorization and feedback', () => {
  const profile = fs.readFileSync(path.join(native, 'pages/profile/profile.wxml'), 'utf8')
  const my = fs.readFileSync(path.join(native, 'pages/my/my.wxml'), 'utf8')
  assert.ok(my.includes('bindtap="phone"')); assert.ok(my.includes('手机号绑定'))
  assert.ok(profile.includes('wx:if="{{loaded && !phoneOnly}}"'))
  assert.ok(profile.includes('open-type="getPhoneNumber"')); assert.ok(profile.includes('phoneError'))
  assert.ok(profile.includes('完成，返回我的'))
})
test('phone binding: expired inline binding session returns to login, not a false bound state', async () => {
  const env = environment(); env.authenticated(); env.setPages([{ route: 'pages/login/login' }])
  const page = env.page('pages/login/login'); page.onLoad({}); const showing = page.onShow()
  env.calls.requests[0].success({ statusCode: 200, data: { code: 0, data: profileValue() } }); await showing
  page.preparePhone(); const binding = page.bindPhone(phoneEvent('expired-session-code'))
  env.calls.requests[1].success({ statusCode: 401, data: { code: 401 } }); await binding
  assert.equal(page.data.profileStage, false); assert.equal(page.data.mobileBound, false); assert.equal(page.data.binding, false)
  assert.equal(env.storage.has('feilv_token'), false)
})
test('order UI: home service hierarchy and create form keep saved-address selectors ahead of contacts', () => {
  const home = fs.readFileSync(path.join(native, 'pages/index/index.wxml'), 'utf8')
  const styles = fs.readFileSync(path.join(native, 'pages/index/index.wxss'), 'utf8')
  const create = fs.readFileSync(path.join(native, 'pages/order/create.wxml'), 'utf8')
  assert.equal(home.includes('class="home-shortcuts"'), false); assert.equal(home.includes('bindtap="address"'), false)
  assert.ok(styles.includes('.home-page .service-SEND')); assert.ok(styles.includes('width: 48.5%'))
  assert.ok(create.indexOf('data-side="from" bindchange="addressChange"') < create.indexOf('id="from-contact"'))
  assert.ok(create.indexOf('data-side="to" bindchange="addressChange"') < create.indexOf('id="to-contact"'))
  assert.ok(create.includes('距离为手工预估')); assert.ok(create.includes('disabled="{{busy || addressLoading}}"'))
})
test('order UI: details prioritize next action and retain all state branches, privacy and MOCK notices', () => {
  const detail = fs.readFileSync(path.join(native, 'pages/order/detail.wxml'), 'utf8')
  for (const state of ['WAIT_PAY', 'WAIT_GRAB', 'ACCEPTED', 'PICKING', 'DELIVERING', 'DELIVERED', 'FINISHED', 'CANCELED']) {
    assert.ok(detail.includes("order.status === '" + state + "'"))
  }
  for (const flag of ['canPay', 'canConfirm', 'canCancel', 'canReview', 'canComplain']) assert.ok(detail.includes('wx:if="{{order.' + flag + '}}"'))
  assert.ok(detail.indexOf('bindtap="pay"') < detail.indexOf('配送路线'))
  assert.ok(detail.indexOf('bindtap="confirm"') < detail.indexOf('配送路线'))
  assert.ok(detail.includes('wx:if="{{order.pickupCode}}"')); assert.ok(detail.includes('不要泄露给无关人员'))
  assert.ok(detail.includes('不发生真实扣款')); assert.ok(detail.includes('退款仅为模拟'))
  assert.ok(detail.includes('disabled="{{busy || loading}}"')); assert.ok(detail.includes('service-state-{{order.status}}'))
})
test('order UI: confirmation keeps retry and uncertain-submit protections visible and native', () => {
  const confirm = fs.readFileSync(path.join(native, 'pages/order/confirm.wxml'), 'utf8')
  assert.ok(confirm.includes('wx:if="{{invalid}}"')); assert.ok(confirm.includes('wx:if="{{uncertain}}"'))
  assert.ok(confirm.includes('wx:elif="{{orderId}}"')); assert.ok(confirm.includes('disabled="{{busy || uncertain}}"'))
  assert.ok(confirm.includes('服务端未提供幂等键')); assert.ok(confirm.includes('不会重复创建'))
  assert.ok(confirm.includes('不发生真实扣款')); assert.ok(confirm.includes('MOCK 支付'))
  assert.ok(confirm.includes('{{draft.fromContact}}')); assert.ok(confirm.includes('{{draft.toPhone}}'))
  for (const page of ['create', 'list', 'confirm', 'detail']) {
    const css = fs.readFileSync(path.join(native, 'pages/order/' + page + '.wxss'), 'utf8')
    assert.ok(css.includes('max-width: 960rpx')); assert.ok(css.includes('@media (max-width: 350px)'))
  }
})
test('order details: MOCK pay, cancel and receipt actions retain correct APIs and refresh state', async () => {
  for (const action of ['pay', 'cancel', 'confirm']) {
    const env = environment(); env.authenticated(); const calls = []
    let value = { id: 25, orderNo: 'LONG-ORDER-25', orderType: 'SEND', status: action === 'confirm' ? 'DELIVERED' : 'WAIT_PAY', realFeeFen: 1200, fromAddress: '长地址'.repeat(40), toAddress: '送达地址' }
    const page = env.page('pages/order/detail', {
      async orderDetail(id) { assert.equal(id, 25); return value },
      async payOrder(id) { calls.push(['pay', id]); value = Object.assign({}, value, { status: 'WAIT_GRAB' }) },
      async cancelOrder(id, reason) { calls.push(['cancel', id, reason]); return Object.assign({}, value, { status: 'CANCELED' }) },
      async confirmOrder(id) { calls.push(['confirm', id]); return Object.assign({}, value, { status: 'FINISHED' }) }
    })
    page.onLoad({ id: '25' }); await page.onShow()
    page.input(event('cancelReason', '  计划改变  ')); await page[action]()
    assert.equal(calls.length, 1); assert.equal(calls[0][0], action); assert.equal(calls[0][1], 25)
    if (action === 'cancel') assert.equal(calls[0][2], '计划改变')
    assert.equal(page.data.order.status, { pay: 'WAIT_GRAB', cancel: 'CANCELED', confirm: 'FINISHED' }[action])
    assert.equal(page.data.order.feeText, '12.00'); assert.equal(page.data.busy, false)
    page.track(); assert.equal(env.calls.navigation.at(-1).url, '/pages/order/track?id=25')
  }
})
test('order details: cancelled confirmations do not mutate orders and failed actions recover loading state', async () => {
  for (const action of ['pay', 'cancel', 'confirm']) {
    const env = environment({ showModal(options) { options.success({ confirm: false, cancel: true }) } }); env.authenticated(); let calls = 0
    const api = { async orderDetail() { return { id: 9, status: 'DELIVERED' } }, async payOrder() { calls++; throw new Error('操作失败') }, async cancelOrder() { calls++; throw new Error('操作失败') }, async confirmOrder() { calls++; throw new Error('操作失败') } }
    const page = env.page('pages/order/detail', api); page.onLoad({ id: '9' }); await page.onShow()
    await page[action](); assert.equal(calls, 0); assert.equal(page.data.busy, false)
    env.wx.showModal = options => options.success({ confirm: true })
    await page[action](); assert.equal(calls, 1); assert.equal(page.data.order.status, 'DELIVERED'); assert.equal(page.data.busy, false)
    assert.ok(env.calls.toast.includes('操作失败'))
  }
})
test('order details: review and complaint form selections still submit only current order data', async () => {
  const env = environment(); env.authenticated(); let review; let complaint
  const page = env.page('pages/order/detail', {
    async orderDetail() { return { id: 33, status: 'FINISHED' } },
    async review(data) { review = data }, async complaint(data) { complaint = data; return { ticketId: 66, sla: '等待客服处理' } }
  })
  page.onLoad({ id: '33' }); await page.onShow()
  page.starsChange({ detail: { value: '3' } }); page.input(event('content', '  服务很好  ')); await page.review()
  assert.equal(review.orderId, 33); assert.equal(review.stars, 4); assert.equal(review.content, '服务很好')
  assert.equal(page.data.reviewed, true); assert.equal(env.storage.get('native_review_1_33'), true)
  await page.complaint(); assert.equal(complaint, undefined)
  page.complaintChange({ detail: { value: '1' } }); page.input(event('complaintDescription', '  包装破损  ')); await page.complaint()
  assert.equal(complaint.orderId, 33); assert.equal(complaint.type, 'DAMAGE'); assert.equal(complaint.description, '包装破损')
  assert.ok(page.data.ticket.includes('66')); assert.equal(page.data.complaintDescription, '')
})
test('simple publish: home keeps only distinct service entries and routes shared tools to existing navigation', () => {
  const home = fs.readFileSync(path.join(native, 'pages/index/index.wxml'), 'utf8')
  assert.equal(home.includes('home-shortcuts'), false)
  for (const handler of ['orders', 'address', 'settings']) assert.equal(home.includes('bindtap="' + handler + '"'), false)
  assert.equal((home.match(/bindtap="create"/g) || []).length, 1)
  assert.ok(home.includes('wx:for="{{types}}"')); assert.ok(home.includes('发布送件任务')); assert.ok(home.includes('MOCK'))
  const create = fs.readFileSync(path.join(native, 'pages/order/create.wxml'), 'utf8')
  assert.equal(create.includes('bindtap="calculate"'), false); assert.equal((create.match(/bindtap="next"/g) || []).length, 1)
  assert.ok(create.includes('wx:if="{{fromContactEditing}}"')); assert.ok(create.includes('wx:if="{{moreVisible}}"'))
  assert.ok(create.includes('重量 {{weightKg}} 公斤')); assert.ok(create.includes('默认按1公斤计费'))
})
test('simple publish: one continue calculates quote then opens confirmation without creating or paying', async () => {
  const env = environment(); env.authenticated(); const calls = []
  const page = env.page('pages/order/create', {
    async me() { calls.push('me'); return profileValue({ mobileBound: true }) },
    async quote(data) { calls.push('quote'); assert.equal(data.distanceM, 2000); return { totalFen: 650, items: [{ feeType: 'BASE', label: '起步费', amountFen: 650 }] } },
    async createOrder() { assert.fail('continue must not create order') }, async payOrder() { assert.fail('continue must not pay') }
  })
  page.onLoad({ type: 'SEND' }); page._visible = true; fillCreate(page); page.data.quote = null
  await page.next()
  assert.deepEqual(calls, ['me', 'quote']); assert.equal(env.calls.navigation.at(-1).url, '/pages/order/confirm')
  const saved = env.storage.get('native_draft'); assert.equal(saved.quote.totalText, '6.50'); assert.equal(saved.payload.goodsDesc, '文件')
})
test('simple publish: quote failure retains inputs and draft; repeated taps cannot double quote or navigate', async () => {
  const env = environment(); env.authenticated(); let respond; let attempts = 0
  const page = env.page('pages/order/create', { async me() { return profileValue({ mobileBound: true }) }, quote() { attempts++; if (attempts === 1) throw new Error('计价失败'); return new Promise(r => { respond = r }) } })
  page.onLoad({}); page._visible = true; fillCreate(page)
  await page.next(); assert.equal(env.calls.navigation.length, 0); assert.equal(page.data.form.goodsDesc, '文件'); assert.ok(env.storage.get('native_draft'))
  const pending = page.next(); await flush(); await page.next(); assert.equal(attempts, 2)
  respond({ totalFen: 500, items: [] }); await pending; assert.equal(env.calls.navigation.length, 1); assert.equal(page.data.busy, false)
})
test('simple publish: late quote cannot replace another draft or navigate after account changes or hiding', async () => {
  for (const change of ['draft', 'account', 'hide']) {
    const env = environment(); env.authenticated(); let respond
    const page = env.page('pages/order/create', { async me() { return profileValue({ mobileBound: true }) }, quote() { return new Promise(r => { respond = r }) } })
    page.onLoad({}); page._visible = true; fillCreate(page); const pending = page.next(); await flush()
    if (change === 'account') env.load(path.join(native, 'utils/session.js')).save({ token: 'B', userId: 2, role: 'USER', nickname: 'B' })
    if (change !== 'hide') env.storage.set('native_draft', { owner: change === 'account' ? 2 : 1, savedAt: 999, payload: { orderType: 'BUY' }, quote: { totalText: '99.00' } })
    else page.onHide()
    respond({ totalFen: 500, items: [] }); await pending; assert.equal(env.calls.navigation.length, 0)
    if (change !== 'hide') assert.equal(env.storage.get('native_draft').savedAt, 999)
  }
})
test('simple publish: default pickup fills once, leaves destination empty and does not overwrite restored addresses', async () => {
  const env = environment(); env.authenticated()
  const page = env.page('pages/order/create', { async addressList() { return [regionAddress()] } }); page.onLoad({}); await page.onShow()
  assert.ok(page.data.form.fromAddress.includes('青龙街道')); assert.equal(page.data.form.toAddress, '')
  assert.equal(page.data.fromContactEditing, false); assert.equal(page.data.moreVisible, false); assert.equal(page.data.weightKg, '1')
  page.toggleContact({ currentTarget: { dataset: { side: 'from' } } }); assert.equal(page.data.fromContactEditing, true)
  page.goodsChoice({ currentTarget: { dataset: { value: '文件' } } }); assert.equal(page.data.form.goodsDesc, '文件')
  page.toggleMore(); assert.equal(page.data.moreVisible, true)
  page.data.form.fromAddress = '明确选择的其他地址'; page.data.fromAddressId = null; await page.loadAddresses(); assert.equal(page.data.form.fromAddress, '明确选择的其他地址')
})
test('simple publish: draft restoration belongs to current account and matching service type only', () => {
  const env = environment(); env.authenticated()
  const original = { orderType: 'SEND', fromContact: '甲', fromPhone: '13800000000', fromAddress: '原取件地址', toContact: '乙', toPhone: '13900000000', toAddress: '原收件地址', goodsDesc: '文件', distanceM: 3500, weightKg: 8, remark: '轻拿轻放' }
  env.storage.set('native_draft', { owner: 1, savedAt: 123, payload: original, fromAddressId: 11, toAddressId: 12 })
  const page = env.page('pages/order/create', {}); page.onLoad({ type: 'SEND' })
  assert.equal(page.data.restored, true); assert.equal(page.data.distanceKm, '3.5'); assert.equal(page.data.weightKg, '8')
  assert.equal(page.data.form.remark, '轻拿轻放'); assert.equal(page.data.fromAddressId, 11)
  const buy = env.page('pages/order/create', {}); buy.onLoad({ type: 'BUY' }); assert.equal(buy.data.restored, false)
  env.storage.set('feilv_user_id', 2); const other = env.page('pages/order/create', {}); other.onLoad({ type: 'SEND' }); assert.equal(other.data.restored, false)
})
test('simple publish: edited task cannot overwrite known or uncertain earlier order protections', async () => {
  for (const pendingFields of [{ id: 88 }, { uncertain: true }]) {
    const env = environment(); env.authenticated(); const previous = { owner: 1, savedAt: 111, payload: { orderType: 'SEND' }, quote: { totalText: '5.00' } }
    env.storage.set('native_draft', previous); env.storage.set('native_pending_order', Object.assign({ owner: 1, savedAt: 111 }, pendingFields))
    const page = env.page('pages/order/create', { async me() { assert.fail('resolve pending first') } }); page.onLoad({}); page._visible = true; fillCreate(page)
    await page.next(); assert.equal(env.storage.get('native_draft'), previous); assert.equal(env.storage.get('native_pending_order').savedAt, 111)
    assert.equal(env.calls.navigation.at(-1).url, '/pages/order/confirm')
  }
})
test('simple publish: final confirmation directly submits once without redundant modal and preserves failure handling', async () => {
  const env = environment({ showModal() { assert.fail('no redundant confirmation dialog') } }); env.authenticated(); let creates = 0; let pays = 0
  env.storage.set('native_draft', { owner: 1, savedAt: 123, payload: { orderType: 'SEND' }, quote: { totalText: '5.00' } })
  const page = env.page('pages/order/confirm', { async me() { return profileValue({ mobileBound: true }) }, async createOrder() { creates++; return { id: 88 } }, async payOrder() { pays++ } })
  page.onLoad({}); page.onShow(); const pending = page.submit(); await page.submit(); await pending
  assert.equal(creates, 1); assert.equal(pays, 1); assert.equal(env.storage.has('native_draft'), false)
  assert.equal(env.calls.navigation.at(-1).url, '/pages/order/detail?id=88')
})
test('simple publish: missing distance and malformed quotes never create a fabricated fee or confirmation', async () => {
  for (const invalid of [null, {}, { totalFen: -1, items: [] }, { totalFen: 500 }, { totalFen: '500', items: [] }]) {
    const env = environment(); env.authenticated(); let quotes = 0
    const page = env.page('pages/order/create', { async me() { return profileValue({ mobileBound: true }) }, async quote() { quotes++; return invalid } })
    page.onLoad({}); page._visible = true; fillCreate(page); page.data.distanceKm = ''
    await page.next(); assert.equal(quotes, 0); assert.equal(env.calls.navigation.length, 0)
    page.data.distanceKm = '2'; await page.next()
    assert.equal(quotes, 1); assert.equal(env.calls.navigation.length, 0); assert.equal(env.storage.get('native_draft').quote, null)
  }
})
test('simple publish: default pickup is account-specific after switching users in an existing page', async () => {
  const env = environment(); env.authenticated(); let value = regionAddress()
  const page = env.page('pages/order/create', { async addressList() { return [value] } }); page.onLoad({}); await page.onShow()
  assert.equal(page.data.fromAddressId, 11)
  env.load(path.join(native, 'utils/session.js')).save({ token: 'B', userId: 2, role: 'USER', nickname: 'B' })
  value = regionAddress({ id: 22, detailAddress: 'B的新地址', contact: 'B' }); await page.onShow()
  assert.equal(page.data.fromAddressId, 22); assert.ok(page.data.form.fromAddress.includes('B的新地址'))
  assert.equal(page.data.form.fromContact, 'B'); assert.equal(page.data.form.toAddress, '')
})
test('orders layout: compact card keeps route first, footer actions and single secondary order number', () => {
  const template = fs.readFileSync(path.join(native, 'pages/order/list.wxml'), 'utf8')
  const css = fs.readFileSync(path.join(native, 'pages/order/list.wxss'), 'utf8')
  assert.ok(template.indexOf('class="order-time"') < template.indexOf('class="order-route"'))
  assert.ok(template.indexOf('class="order-route"') < template.indexOf('class="order-card-footer"'))
  assert.ok(template.indexOf('class="order-card-footer"') < template.indexOf('class="order-number"'))
  assert.equal((template.match(/class="order-number"/g) || []).length, 1)
  assert.ok(template.includes('order-status status-{{item.status}}')); assert.ok(template.includes('bindtap="detail"'))
  assert.ok(template.includes('去模拟支付')); assert.ok(template.includes('去确认收货'))
  assert.ok(template.includes('wx:if="{{error}}"')); assert.ok(template.includes('bindtap="retry"'))
  assert.ok(template.includes('wx:if="{{hasMore && list.length}}"')); assert.ok(css.includes('min-height: 44px'))
  for (const state of ['WAIT_PAY', 'WAIT_GRAB', 'ACCEPTED', 'PICKING', 'DELIVERING', 'DELIVERED', 'FINISHED', 'CANCELED']) assert.ok(css.includes('.order-status.status-' + state))
})
test('orders layout: card navigation opens details without mutating pending payment or receipt orders', async () => {
  for (const status of ['WAIT_PAY', 'DELIVERED', 'FINISHED', 'CANCELED']) {
    const env = environment(); env.authenticated()
    const page = env.page('pages/order/list', { async myOrders() { return { content: [{ id: 7, orderNo: '订单号'.repeat(30), status, fromAddress: '很长的取件地址'.repeat(30), toAddress: '送达地址', realFeeFen: 2500 }], last: true } } })
    page.onLoad({}); await page.onShow(); page.detail({ currentTarget: { dataset: { id: 7 } } })
    assert.equal(env.calls.navigation.at(-1).url, '/pages/order/detail?id=7')
    assert.equal(page.data.list[0].status, status); assert.equal(page.data.list[0].feeText, '25.00')
    assert.equal(page.data.list[0].canPay, status === 'WAIT_PAY'); assert.equal(page.data.list[0].canConfirm, status === 'DELIVERED')
  }
})
async function readyManualPhone(env, api, route = 'pages/profile/profile') {
  env.authenticated()
  const page = env.page(route, api)
  page.onLoad({ from: 'my', mode: 'phone' }); await page.onShow()
  page.togglePhoneMethod()
  return page
}
function manualFill(page, mobile = '13800138000', code = '') {
  page.manualPhoneInput(event('manualMobile', mobile)); page.manualPhoneInput(event('manualCode', code))
}
test('manual phone: authenticated API uses separate binding purpose and never the public login SMS route', async () => {
  const env = environment(); env.authenticated()
  const api = env.load(path.join(native, 'utils/api.js'))
  const sending = api.phoneSmsCode('13800138000'); const req = env.calls.requests[0]
  assert.ok(req.url.endsWith('/user/phone/sms-code')); assert.equal(req.header.Authorization, 'Bearer token-1')
  assert.deepEqual(Object.keys(req.data), ['mobile']); req.success({ statusCode: 200, data: { code: 0, data: { retryAfterSeconds: 60, mock: true, demoCode: '123456' } } }); await sending
  const binding = api.bindPhone('13800138000', '123456'); const bind = env.calls.requests[1]
  assert.ok(bind.url.endsWith('/user/phone')); assert.deepEqual(Object.keys(bind.data), ['mobile', 'code'])
  bind.success({ statusCode: 200, data: { code: 0, data: profileValue({ mobileBound: true, mobile: '138****8000' }) } }); await binding
})
test('manual phone: validates entered phone, explicit request and six-digit code before any bind', async () => {
  const env = environment(); let sends = 0; let binds = 0
  const page = await readyManualPhone(env, { async me() { return profileValue() }, async phoneSmsCode() { sends++; return { retryAfterSeconds: 60, mock: true, demoCode: '123456' } }, async bindPhone() { binds++; return profileValue({ mobileBound: true }) } })
  manualFill(page, 'bad', '123456'); await page.sendPhoneSms(); await page.bindManualPhone(); assert.equal(sends, 0); assert.equal(binds, 0)
  manualFill(page, '13800138000', '123456'); await page.bindManualPhone(); assert.equal(binds, 0)
  await page.sendPhoneSms(); assert.equal(page.data.phoneDemoCode, '123456'); assert.equal(page.data.phoneSmsMock, true)
  assert.equal(page.data.manualCode, '', 'demo code must not be automatically submitted')
  page.manualPhoneInput(event('manualCode', '123')); await page.bindManualPhone(); assert.equal(binds, 0)
  page.manualPhoneInput(event('manualCode', '123456')); await page.bindManualPhone(); assert.equal(binds, 1)
})
test('manual phone: binding preserves nickname draft, clears raw credentials and does not alter account or order draft', async () => {
  const env = environment(); let payload
  const page = await readyManualPhone(env, { async me() { return profileValue() }, async phoneSmsCode() { return { retryAfterSeconds: 60, mock: true, demoCode: '234567' } }, async bindPhone(mobile, code) { payload = { mobile, code }; return profileValue({ mobileBound: true, mobile: '138****8000' }) } })
  env.storage.set('native_draft', { owner: 1, savedAt: 99 }); const revision = env.load(path.join(native, 'utils/session.js')).revision()
  page.data.nickname = '未保存昵称'; manualFill(page); await page.sendPhoneSms(); page.manualPhoneInput(event('manualCode', '234567'))
  await page.bindManualPhone(); assert.deepEqual(payload, { mobile: '13800138000', code: '234567' })
  assert.equal(page.data.mobileBound, true); assert.equal(page.data.mobile, '138****8000'); assert.equal(page.data.nickname, '未保存昵称')
  assert.equal(page.data.manualMobile, ''); assert.equal(page.data.manualCode, ''); assert.equal(page.data.phoneDemoCode, '')
  assert.equal(env.storage.get('feilv_token'), 'token-1'); assert.equal(env.storage.get('native_draft').savedAt, 99)
  assert.equal(env.load(path.join(native, 'utils/session.js')).revision(), revision)
  for (const [key, value] of env.storage) { assert.notEqual(value, '13800138000'); assert.notEqual(value, '234567') }
})
test('manual phone: SMS not configured, code mismatch and phone conflicts never claim binding success', async () => {
  for (const code of [400, 409, 429, 503]) {
    const env = environment()
    const page = await readyManualPhone(env, { async me() { return profileValue() }, async phoneSmsCode() { throw Object.assign(new Error('短信未完成-' + code), { code }) } })
    manualFill(page); await page.sendPhoneSms()
    assert.equal(page.data.mobileBound, false); assert.equal(page.data.phoneError, '短信未完成-' + code)
    assert.equal(page.data.phoneDemoCode, ''); assert.equal(page.data.phoneCooling, 0); assert.equal(page.data.phoneSending, false)
    assert.equal(env.storage.get('feilv_token'), 'token-1'); assert.equal(env.calls.toast.some(message => message.includes('已发送')), false)
  }
})
test('manual phone: changing phone clears old code and mock display; cooldown blocks repeated sends', async () => {
  const env = environment(); let sends = 0; let binds = 0
  const page = await readyManualPhone(env, { async me() { return profileValue() }, async phoneSmsCode() { sends++; return { retryAfterSeconds: 60, mock: true, demoCode: '123456' } }, async bindPhone() { binds++ } })
  manualFill(page); await page.sendPhoneSms(); assert.equal(sends, 1); assert.equal(page.data.phoneCooling, 60)
  await page.sendPhoneSms(); assert.equal(sends, 1)
  manualFill(page, '13900139000', '123456'); assert.equal(page.data.phoneDemoCode, '')
  await page.bindManualPhone(); assert.equal(binds, 0)
  page.onHide(); assert.equal(env.timers.size, 0); page._phoneSmsUntil = Date.now() - 1; await page.onShow()
  assert.equal(page.data.phoneCooling, 0); assert.equal(page.data.phoneSmsLabel, '获取验证码')
})
test('manual phone: switching method revokes pending WeChat phone intent but keeps manual form in page memory', async () => {
  const env = environment(); let binds = 0
  const page = await readyProfile(env, { async me() { return profileValue() }, async bindWechatPhone() { binds++ } })
  page.preparePhone(); page.togglePhoneMethod(); await page.bindPhone(phoneEvent('late-code')); assert.equal(binds, 0)
  manualFill(page, '13800138000', '123456'); page.togglePhoneMethod(); assert.equal(page.data.manualPhone, false)
  page.togglePhoneMethod(); assert.equal(page.data.manualMobile, '13800138000'); assert.equal(page.data.manualCode, '123456')
})
test('manual phone: concurrent send and bind taps are suppressed and hidden/switched responses never mutate another page', async () => {
  for (const operation of ['send', 'bind']) {
    const env = environment(); let resolve; let calls = 0
    const api = { async me() { return profileValue() }, phoneSmsCode() { return Promise.resolve({ retryAfterSeconds: 60, mock: true, demoCode: '123456' }) }, bindPhone() { calls++; return new Promise(r => { resolve = r }) } }
    if (operation === 'send') api.phoneSmsCode = () => { calls++; return new Promise(r => { resolve = r }) }
    const page = await readyManualPhone(env, api); manualFill(page)
    if (operation === 'bind') { await page.sendPhoneSms(); page.manualPhoneInput(event('manualCode', '123456')) }
    const invoke = () => operation === 'send' ? page.sendPhoneSms() : page.bindManualPhone()
    const pending = invoke(); await invoke(); assert.equal(calls, 1)
    env.load(path.join(native, 'utils/session.js')).save({ token: 'B', userId: 2, role: 'USER', nickname: 'B昵称' })
    resolve(operation === 'send' ? { retryAfterSeconds: 60, mock: true, demoCode: '654321' } : profileValue({ mobileBound: true, mobile: '138****8000' })); await pending
    assert.equal(page.data.mobileBound, false); assert.notEqual(page.data.phoneDemoCode, '654321'); assert.equal(env.storage.get('feilv_nickname'), 'B昵称')
    assert.equal(env.calls.navigation.length, 0)
  }
})
test('manual phone: inline login supports manual binding even when WeChat phone capability is unavailable', async () => {
  const env = environment({ canIUse() { return false } })
  const page = await readyManualPhone(env, { async me() { return profileValue({ nickname: '微信用户' }) }, async phoneSmsCode() { return { retryAfterSeconds: 60, mock: true, demoCode: '123456' } }, async bindPhone() { return profileValue({ mobileBound: true, mobile: '138****8000', nickname: '微信用户' }) } }, 'pages/login/login')
  assert.equal(page.data.phoneSupported, false); assert.equal(page.data.manualPhone, true)
  manualFill(page); await page.sendPhoneSms(); page.manualPhoneInput(event('manualCode', '123456')); await page.bindManualPhone()
  assert.equal(page.data.profileStage, true); assert.equal(page.data.mobileBound, true); assert.equal(page.data.manualMobile, '')
})
test('manual phone: failed binding keeps current input for retry but never displays a false bound state', async () => {
  for (const result of ['wrong-code', 'unconfirmed']) {
    const env = environment()
    const page = await readyManualPhone(env, { async me() { return profileValue() }, async phoneSmsCode() { return { retryAfterSeconds: 60, mock: true, demoCode: '123456' } }, async bindPhone() { if (result === 'wrong-code') throw new Error('验证码错误'); return profileValue() } })
    manualFill(page); await page.sendPhoneSms(); page.manualPhoneInput(event('manualCode', '123456')); await page.bindManualPhone()
    assert.equal(page.data.mobileBound, false); assert.ok(page.data.phoneError); assert.equal(page.data.manualMobile, '13800138000')
    assert.equal(page.data.binding, false); assert.equal(env.calls.toast.some(message => message.includes('已验证并绑定')), false)
  }
})
test('manual phone: native reusable template exists in both binding entry points and requires code verification', () => {
  const form = fs.readFileSync(path.join(native, 'templates/manual-phone.wxml'), 'utf8')
  assert.ok(form.includes('bindtap="sendPhoneSms"')); assert.ok(form.includes('bindtap="bindManualPhone"')); assert.ok(form.includes('data-field="manualMobile"'))
  assert.ok(form.includes('未发送真实短信')); assert.ok(form.includes('不能仅填写号码直接绑定'))
  for (const page of ['profile/profile', 'login/login']) {
    const template = fs.readFileSync(path.join(native, 'pages/' + page + '.wxml'), 'utf8')
    assert.ok(template.includes('is="manualPhoneForm"')); assert.ok(template.includes('bindtap="togglePhoneMethod"'))
  }
})
test('manual phone: non-mock send never displays server demo payload and invalid send responses fail closed', async () => {
  for (const result of [
    { retryAfterSeconds: 60, mock: false, demoCode: 'must-not-display' },
    { retryAfterSeconds: 60, mock: true, demoCode: '' },
    { retryAfterSeconds: -1, mock: false },
    null
  ]) {
    const env = environment()
    const page = await readyManualPhone(env, { async me() { return profileValue() }, async phoneSmsCode() { return result } })
    manualFill(page); await page.sendPhoneSms(); assert.equal(page.data.phoneDemoCode, '')
    if (result && result.mock === false && result.retryAfterSeconds === 60) {
      assert.equal(page.data.phoneCooling, 60); assert.ok(env.calls.toast.includes('验证码已发送，请查看短信'))
    } else { assert.ok(page.data.phoneError); assert.equal(page.data.phoneCooling, 0) }
  }
})
test('manual phone: code expiry is not platform session expiry; switched mode retains WeChat authorization support', async () => {
  const env = environment()
  const page = await readyManualPhone(env, { async me() { return profileValue() }, async phoneSmsCode() { return { retryAfterSeconds: 60, mock: true, demoCode: '123456' } }, async bindPhone() { throw Object.assign(new Error('验证码已过期，请重新获取'), { code: 4010 }) }, async bindWechatPhone() { return profileValue({ mobileBound: true }) } })
  manualFill(page); await page.sendPhoneSms(); page.manualPhoneInput(event('manualCode', '123456')); await page.bindManualPhone()
  assert.equal(page.data.mobileBound, false); assert.equal(env.storage.get('feilv_token'), 'token-1')
  page.togglePhoneMethod(); page.preparePhone(); await page.bindPhone(phoneEvent('fresh-wechat-code'))
  assert.equal(page.data.mobileBound, true); assert.equal(page.data.manualMobile, '')
})
test('manual phone: HTTP 401 during inline send restores login instead of leaving stale phone credentials', async () => {
  const env = environment(); env.authenticated(); env.setPages([{ route: 'pages/login/login' }])
  const page = env.page('pages/login/login'); page.onLoad({}); const showing = page.onShow()
  env.calls.requests[0].success({ statusCode: 200, data: { code: 0, data: profileValue() } }); await showing
  page.togglePhoneMethod(); manualFill(page); const pending = page.sendPhoneSms()
  env.calls.requests[1].success({ statusCode: 401, data: { code: 401 } }); await pending
  assert.equal(page.data.profileStage, false); assert.equal(page.data.manualMobile, ''); assert.equal(page.data.phoneSending, false)
  assert.equal(env.storage.has('feilv_token'), false)
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
