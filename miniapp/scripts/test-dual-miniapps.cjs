'use strict'
// Split-root contracts supplement, rather than replace, the original 241-case inventory.
module.exports = function ({ assert, fs, path, native, client, expectedPages, expectedTabs, environment, commonTest, userTest, riderTest, flush, event, phoneEvent, profileValue, walletPage, withdrawalValue, okRequest }) {
  const TICKET = 'T'.repeat(43)
  const LINK_CODE = 'L'.repeat(43)
  const future = () => new Date(Date.now() + 180000).toISOString()
  const authenticated = (patch = {}) => Object.assign({ token: 'new-auth-token', userId: 1, role: 'USER', nickname: '平台昵称' }, patch)
  const business = () => ({
    native_draft: { owner: 1, savedAt: 123, payload: { orderType: 'SEND', fromAddress: '私密地址' }, quote: { totalText: '5.00' } },
    native_draft_groups: { owner: 1, drafts: { RUNNER_SEND: { owner: 1, savedAt: 123, payload: { orderType: 'SEND' } } } },
    native_pending_order: { owner: 1, savedAt: 123, id: 88 },
    native_pending_withdrawal: { owner: '1', amountFen: 1234, requestId: 'original-withdraw-key' }
  })
  function seed(env) { const value = business(); for (const [key, item] of Object.entries(value)) env.storage.set(key, item); return value }
  async function identity(env, overrides = {}) {
    const page = env.page('pages/login/login', Object.assign({ async loginOptions() { return { smsLoginEnabled: false } }, async wechatLogin() { return { state: 'IDENTITY_REQUIRED', loginTicket: TICKET, expiresAt: future() } }, async me() { return profileValue() } }, overrides))
    page.onLoad({}); await page.onShow(); page.consent({ detail: { value: ['yes'] } }); await page.wechat()
    return page
  }
  async function enrollment(env, overrides = {}) {
    env.authenticated()
    const page = env.page('pages/rider/register', Object.assign({ async riderEnrollment() { return authenticated({ role: 'RIDER' }) } }, overrides))
    page.onLoad({}); await page.onShow()
    page.input(event('name', ' 张三 ')); page.input(event('plateNo', ' 贵D12345 ')); page.consent({ detail: { value: ['yes'] } })
    return page
  }

  commonTest('split root: exact native page set, client configuration and tab registration', () => {
    const app = JSON.parse(fs.readFileSync(path.join(native, 'app.json'), 'utf8'))
    assert.deepEqual([...app.pages].sort(), [...expectedPages].sort())
    assert.deepEqual(app.tabBar.list.map(tab => tab.pagePath), expectedTabs)
    const env = environment(); assert.equal(env.load(path.join(native, 'config.js')).CLIENT, client)
    for (const route of app.pages) for (const ext of ['js', 'json', 'wxml', 'wxss']) assert.ok(fs.existsSync(path.join(native, route + '.' + ext)))
  })
  commonTest('strict mock: unknown pages and wrong tab navigation fail even if page catches errors', () => {
    const env = environment()
    for (const operation of [() => env.wx.navigateTo({ url: '/pages/not-registered/missing' }), () => env.wx.navigateTo({ url: '/' + expectedTabs[0] }), () => env.wx.redirectTo({ url: '/' + expectedTabs[0] }), () => env.wx.switchTab({ url: '/pages/profile/profile' })]) {
      assert.throws(operation, /unregistered|tab/); env.calls.navigationErrors.pop()
    }
    assert.throws(() => env.page(client === 'USER' ? 'pages/rider/workbench' : 'pages/order/create'), /unregistered/)
    env.wx.switchTab({ url: '/' + expectedTabs[0] }); assert.equal(env.calls.navigation.at(-1).method, 'switchTab')
  })
  commonTest('expiry quarantine: credentials and private UI disappear while all four owner-scoped protections survive', () => {
    const env = environment(); env.authenticated(); const saved = seed(env)
    const page = env.page('pages/wallet/wallet'); page.onLoad({}); page.route = 'pages/wallet/wallet'; page.data.amount = '12.34'; page.data.wallet = { availableText: '50.00' }; env.setPages([page])
    const session = env.load(path.join(native, 'utils/session.js')); const revision = session.revision(); session.expire()
    assert.equal(session.revision(), revision + 1); assert.equal(session.token(), '')
    for (const key of ['feilv_token', 'feilv_user_id', 'feilv_role', 'feilv_nickname', 'feilv_avatar_url', ...Object.keys(saved)]) assert.equal(env.storage.has(key), false, key)
    const quarantined = env.storage.get('native_expired_business'); assert.equal(quarantined.owner, '1'); assert.equal(quarantined.backend, 'https://xt.allms.cn')
    for (const key of Object.keys(saved)) assert.equal(quarantined.values[key], saved[key])
    assert.equal(page.data.amount, ''); assert.equal(page.data.wallet, null)
  })
  commonTest('expiry quarantine: same account and backend restore known and uncertain markers across role refresh', () => {
    for (const role of ['USER', 'RIDER']) for (const uncertain of [false, true]) {
      const env = environment(); env.authenticated(); const saved = seed(env)
      if (uncertain) { delete saved.native_pending_order.id; saved.native_pending_order.uncertain = true }
      const session = env.load(path.join(native, 'utils/session.js')); session.expire(); session.save(authenticated({ role }))
      assert.equal(env.storage.get('feilv_role'), role); assert.equal(env.storage.has('native_expired_business'), false)
      for (const key of Object.keys(saved)) assert.equal(env.storage.get(key), saved[key])
    }
  })
  commonTest('expiry quarantine: other account, service change and explicit logout cannot inherit original markers', () => {
    for (const change of ['account', 'backend', 'logout']) {
      const env = environment(); env.authenticated(); const saved = seed(env)
      const session = env.load(path.join(native, 'utils/session.js')); session.expire()
      if (change === 'backend') env.storage.set('native_effective_base_url', 'https://other.example')
      if (change === 'logout') session.clear()
      session.save(authenticated({ userId: change === 'account' ? 2 : 1 }))
      for (const key of Object.keys(saved)) assert.equal(env.storage.has(key), false, change + ':' + key)
      assert.equal(env.storage.has('native_expired_business'), false)
    }
  })
  commonTest('expiry quarantine: foreign-owner data is removed, never restored to authenticated account', () => {
    const env = environment(); env.authenticated(); const saved = seed(env)
    for (const value of Object.values(saved)) value.owner = 2
    const session = env.load(path.join(native, 'utils/session.js')); session.expire(); session.save(authenticated())
    for (const key of Object.keys(saved)) assert.equal(env.storage.has(key), false)
  })
  commonTest('request role refresh: 40101 quarantines pending while ordinary business 403 retains live session', async () => {
    for (const code of [40101, 403]) {
      const env = environment(); env.authenticated(); const saved = seed(env)
      const request = env.load(path.join(native, 'utils/request.js')).request; const pending = request.get('/protected')
      env.calls.requests[0].success({ statusCode: 403, data: { code, msg: '权限变化' } }); await assert.rejects(pending)
      if (code === 40101) { assert.equal(env.storage.has('feilv_token'), false); assert.equal(env.storage.get('native_expired_business').values.native_pending_order, saved.native_pending_order) }
      else { assert.equal(env.storage.get('feilv_token'), 'token-1'); assert.equal(env.storage.get('native_pending_order'), saved.native_pending_order); assert.equal(env.storage.has('native_expired_business'), false) }
    }
  })
  commonTest('request stale expiry: old 40101 cannot clear replacement token or quarantine replacement business', async () => {
    const env = environment(); env.authenticated()
    const request = env.load(path.join(native, 'utils/request.js')).request; const pending = request.get('/protected')
    const session = env.load(path.join(native, 'utils/session.js')); session.save(authenticated({ userId: 2 }))
    const replacement = { owner: 2, id: 99 }; env.storage.set('native_pending_order', replacement)
    env.calls.requests[0].success({ statusCode: 403, data: { code: 40101 } }); await assert.rejects(pending, error => error.silent === true)
    assert.equal(env.storage.get('feilv_token'), 'new-auth-token'); assert.equal(env.storage.get('native_pending_order'), replacement); assert.equal(env.storage.has('native_expired_business'), false)
  })
  commonTest('wallet reauth: same account uses original pending withdrawal key and amount after expiry', async () => {
    const env = environment(); const posted = []
    const page = await walletPage(env, { async requestWithdrawal(value) { posted.push(value); if (posted.length === 1) throw new Error('丢回包'); return withdrawalValue(value) } })
    page.input(event('amount', '12.34')); await page.submit(); const original = env.storage.get('native_pending_withdrawal'); page.onHide()
    const session = env.load(path.join(native, 'utils/session.js')); session.expire(); assert.equal(env.storage.has('native_pending_withdrawal'), false)
    session.save(authenticated()); await page.onShow(); assert.equal(page.data.amount, '12.34'); await page.submit()
    assert.equal(posted.length, 2); assert.equal(posted[1].requestId, original.requestId); assert.equal(posted[1].amountFen, 1234); assert.equal(env.storage.has('native_pending_withdrawal'), false)
  })
  commonTest('dual auth API: fixed client ticket endpoints and protected linking accept no account-selection identity', async () => {
    const env = environment(); const api = env.load(path.join(native, 'utils/api.js'))
    for (const [method, args, suffix, body] of [
      ['wechatLogin', ['wx-code'], '/login', { code: 'wx-code', agreed: true }],
      ['createAccount', [TICKET, 'phone-code'], '/accounts', { loginTicket: TICKET, phoneCode: 'phone-code', agreed: true }],
      ['linkIdentity', [TICKET, LINK_CODE], '/identity-link', { loginTicket: TICKET, linkCode: LINK_CODE, agreed: true }]
    ]) {
      const pending = api[method](...args); const req = env.calls.requests.at(-1)
      assert.equal(req.url, 'https://xt.allms.cn/api/v1/auth/miniapps/' + client + suffix); assert.equal(req.method, 'POST')
      assert.deepEqual(JSON.parse(JSON.stringify(req.data)), body); assert.equal(req.header.Authorization, undefined); okRequest(req, {}); await pending
    }
    env.authenticated(); const pending = api.miniappLinkCode('fresh-wx-code'); const req = env.calls.requests.at(-1)
    assert.equal(req.url, 'https://xt.allms.cn/api/v1/user/miniapp-link-codes'); assert.equal(req.header.Authorization, 'Bearer token-1')
    assert.deepEqual(JSON.parse(JSON.stringify(req.data)), { code: 'fresh-wx-code', agreed: true }); okRequest(req, {}); await pending
  })
  commonTest('ticket identity: unbound login requires explicit choice and never creates token or persistent ticket', async () => {
    const env = environment(); let writes = 0; const page = await identity(env, { async createAccount() { writes++ }, async linkIdentity() { writes++ } })
    assert.equal(page.data.identityRequired, true); assert.equal(page.data.identityChoice, ''); assert.equal(env.storage.has('feilv_token'), false)
    await page.createAccount(phoneEvent('unsolicited')); await page.linkAccount(); assert.equal(writes, 0)
    for (const value of env.storage.values()) assert.equal(JSON.stringify(value).includes(TICKET), false)
  })
  commonTest('ticket new account: native fresh phone code plus confirmation only, no mobile or userId claim', async () => {
    const env = environment(); let submitted; const page = await identity(env, { async createAccount(ticket, phone) { submitted = { ticket, phone }; return authenticated() } })
    await page.chooseIdentity({ currentTarget: { dataset: { choice: 'NEW' } } }); page.prepareAccountPhone(); await page.createAccount(phoneEvent('real-phone-proof'))
    assert.deepEqual(submitted, { ticket: TICKET, phone: 'real-phone-proof' }); assert.equal(env.storage.get('feilv_token'), 'new-auth-token')
    assert.equal(page.data.identityRequired, false); assert.equal(page.data.linkCode, ''); assert.equal(page._loginTicket, null); assert.equal(page.data.profileStage, true)
  })
  commonTest('ticket new account: canceled confirmation, denied phone and already-used proof never repeat account creation', async () => {
    const env = environment(); let posts = 0; const page = await identity(env, { async createAccount() { posts++; throw new Error('网络失败') } })
    await page.chooseIdentity({ currentTarget: { dataset: { choice: 'NEW' } } }); page.prepareAccountPhone()
    await page.createAccount(phoneEvent('', 'getPhoneNumber:fail user deny')); assert.match(env.calls.toast.at(-1), /真实微信手机号/); assert.equal(posts, 0)
    env.wx.showModal = value => value.success({ confirm: false }); page.prepareAccountPhone(); await page.createAccount(phoneEvent('cancel-proof')); assert.equal(posts, 0)
    env.wx.showModal = value => value.success({ confirm: true }); page.prepareAccountPhone(); await page.createAccount(phoneEvent('one-use')); assert.equal(posts, 1)
    page.prepareAccountPhone(); await page.createAccount(phoneEvent('one-use')); assert.equal(posts, 1); assert.equal(env.storage.has('feilv_token'), false)
  })
  commonTest('ticket link: exact 43-character code requires explicit confirmation and same page generation', async () => {
    const env = environment(); let submitted; const page = await identity(env, { async linkIdentity(ticket, code) { submitted = { ticket, code }; return authenticated() } })
    await page.chooseIdentity({ currentTarget: { dataset: { choice: 'LINK' } } }); page.linkCodeInput({ detail: { value: 'bad' } }); await page.linkAccount(); assert.equal(submitted, undefined)
    page.linkCodeInput({ detail: { value: LINK_CODE } }); env.wx.showModal = value => value.success({ confirm: false }); await page.linkAccount(); assert.equal(submitted, undefined)
    env.wx.showModal = value => value.success({ confirm: true }); await page.linkAccount(); assert.deepEqual(submitted, { ticket: TICKET, code: LINK_CODE }); assert.equal(env.storage.get('feilv_user_id'), 1)
  })
  commonTest('ticket lifetime: hidden, expired and switched identity cannot send or apply account-link result', async () => {
    for (const change of ['hide', 'expire', 'switch']) {
      const env = environment(); let posts = 0; const page = await identity(env, { async linkIdentity() { posts++; return authenticated() } })
      await page.chooseIdentity({ currentTarget: { dataset: { choice: 'LINK' } } }); page.linkCodeInput({ detail: { value: LINK_CODE } })
      if (change === 'hide') page.onHide()
      else if (change === 'expire') page._loginTicket.expires = Date.now() - 1
      else env.load(path.join(native, 'utils/session.js')).save(authenticated({ userId: 2 }))
      await page.linkAccount(); assert.equal(posts, 0)
      if (change === 'switch') assert.equal(env.storage.get('feilv_user_id'), 2)
    }
  })
  commonTest('ticket late response: account switch while link request is pending never overwrites replacement credentials', async () => {
    const env = environment(); let respond; const page = await identity(env, { linkIdentity() { return new Promise(resolve => { respond = resolve }) } })
    await page.chooseIdentity({ currentTarget: { dataset: { choice: 'LINK' } } }); page.linkCodeInput({ detail: { value: LINK_CODE } }); const pending = page.linkAccount(); await flush()
    env.load(path.join(native, 'utils/session.js')).save(authenticated({ userId: 2, token: 'replacement' })); respond(authenticated()); await pending
    assert.equal(env.storage.get('feilv_token'), 'replacement'); assert.equal(env.storage.get('feilv_user_id'), 2)
  })
  commonTest('ticket validation: bare legacy LoginVO, malformed ticket and expired envelope do not authenticate', async () => {
    for (const value of [authenticated(), { state: 'IDENTITY_REQUIRED', loginTicket: 'short', expiresAt: future() }, { state: 'IDENTITY_REQUIRED', loginTicket: TICKET, expiresAt: new Date(0).toISOString() }, { state: 'UNKNOWN', loginTicket: TICKET, expiresAt: future() }]) {
      const env = environment(); const page = await identity(env, { async wechatLogin() { return value } }); assert.equal(env.storage.has('feilv_token'), false); assert.equal(page.data.identityRequired, false)
    }
  })
  commonTest('SMS capability: unavailable, string-true and failed capability reads leave public SMS entry closed', async () => {
    for (const value of [null, {}, { smsLoginEnabled: false }, { smsLoginEnabled: 'true' }, 'failure']) {
      const env = environment(); let posts = 0; const page = env.page('pages/login/login', { async loginOptions() { if (value === 'failure') throw new Error('unavailable'); return value }, async login() { posts++ }, async smsCode() { posts++ } })
      page.onLoad({}); await page.onShow(); page.consent({ detail: { value: ['yes'] } }); page.input(event('mobile', '13800000000')); page.input(event('code', '123456'))
      page.toggleSms(); await page.sendCode(); await page.smsLogin(); assert.equal(page.data.sms, false); assert.equal(page.data.smsLoginEnabled, false); assert.equal(posts, 0)
    }
  })
  commonTest('link code generation: fresh wx proof, target endpoint, page-memory only, cleared on hide', async () => {
    const env = environment(); env.authenticated(); let proof
    const page = env.page('pages/my/my', { async me() { return profileValue() }, async miniappLinkCode(code) { proof = code; return { linkCode: LINK_CODE, expiresAt: future(), targetClient: client === 'USER' ? 'RIDER' : 'USER' } } })
    page.onLoad({}); await page.onShow(); await page.generateLinkCode(); assert.equal(proof, 'temporary-wx-code'); assert.equal(page.data.linkCode, LINK_CODE)
    for (const value of env.storage.values()) assert.equal(JSON.stringify(value).includes(LINK_CODE), false)
    assert.equal(env.calls.clipboard, undefined); assert.equal(env.calls.navigation.length, 0); page.onHide(); assert.equal(page.data.linkCode, '')
  })
  commonTest('link code response: wrong target or expired code never displays even with authenticated session', async () => {
    for (const patch of [{ targetClient: client }, { expiresAt: new Date(0).toISOString() }, { linkCode: 'bad' }]) {
      const env = environment(); env.authenticated(); const page = env.page('pages/my/my', { async me() { return profileValue() }, async miniappLinkCode() { return Object.assign({ linkCode: LINK_CODE, expiresAt: future(), targetClient: client === 'USER' ? 'RIDER' : 'USER' }, patch) } })
      page.onLoad({}); await page.onShow(); await page.generateLinkCode(); assert.equal(page.data.linkCode, '')
    }
  })

  userTest('order reauth: known ID resumes on same confirm instance with fresh revision, never create again', async () => {
    const env = environment(); env.authenticated(); seed(env); let creates = 0; let pays = 0; let reads = 0
    const page = env.page('pages/order/confirm', { async me() { return profileValue({ mobileBound: true }) }, async createOrder() { creates++ }, async orderDetail(id) { reads++; assert.equal(id, 88); return { id, status: 'WAIT_PAY' } }, async payOrder(id) { pays++; assert.equal(id, 88) } })
    page.onLoad({}); page.onShow(); page.route = 'pages/order/confirm'; env.setPages([page]); page.onHide()
    const session = env.load(path.join(native, 'utils/session.js')); session.expire(); assert.equal(page.data.draft, null); session.save(authenticated()); page.onShow(); await page.submit()
    assert.equal(creates, 0); assert.equal(reads, 1); assert.equal(pays, 1); assert.equal(env.storage.has('native_pending_order'), false); assert.equal(env.calls.navigation.at(-1).url, '/pages/order/detail?id=88')
  })
  userTest('order reauth: uncertain create remains locked after same-owner recovery; no private draft while anonymous', async () => {
    const env = environment(); env.authenticated(); seed(env); env.storage.set('native_pending_order', { owner: 1, savedAt: 123, uncertain: true }); let creates = 0
    const page = env.page('pages/order/confirm', { async createOrder() { creates++ } }); page.onLoad({}); page.onShow(); page.onHide()
    const session = env.load(path.join(native, 'utils/session.js')); session.expire(); page.onShow(); assert.equal(page.data.draft, null); assert.equal(page.data.orderId, null)
    page.onHide(); session.save(authenticated()); page.onShow(); assert.equal(page.data.uncertain, true); await page.submit(); assert.equal(creates, 0); assert.equal(env.storage.get('native_pending_order').uncertain, true)
  })
  userTest('order reauth: different account cannot see or submit expired original confirmation', async () => {
    const env = environment(); env.authenticated(); seed(env); let creates = 0
    const page = env.page('pages/order/confirm', { async createOrder() { creates++ } }); page.onLoad({}); page.onShow(); page.onHide()
    const session = env.load(path.join(native, 'utils/session.js')); session.expire(); session.save(authenticated({ userId: 2 })); page.onShow(); await page.submit()
    assert.equal(page.data.invalid, true); assert.equal(page.data.draft, null); assert.equal(page.data.orderId, null); assert.equal(creates, 0)
  })

  riderTest('enrollment API: authenticated current subject with phone proof, no mobile or userId selectors', async () => {
    const env = environment(); env.authenticated(); const pending = env.load(path.join(native, 'utils/api.js')).riderEnrollment({ name: '张三', plateNo: '贵D12345', phoneCode: 'proof', mobile: '13800000000', userId: 999, role: 'ADMIN' })
    const req = env.calls.requests[0]; assert.equal(req.url, 'https://xt.allms.cn/api/v1/rider/enrollment'); assert.equal(req.header.Authorization, 'Bearer token-1')
    assert.deepEqual(JSON.parse(JSON.stringify(req.data)), { name: '张三', plateNo: '贵D12345', phoneCode: 'proof', agreed: true }); okRequest(req, authenticated({ role: 'RIDER' })); await pending
  })
  riderTest('enrollment: real phone proof upgrades same subject, preserves pending and lands on workbench tab', async () => {
    const env = environment(); let submitted; const page = await enrollment(env, { async riderEnrollment(value) { submitted = value; return authenticated({ role: 'RIDER' }) } }); const saved = seed(env)
    page.preparePhone(); await page.register(phoneEvent('fresh-phone-proof'))
    assert.deepEqual(JSON.parse(JSON.stringify(submitted)), { name: '张三', plateNo: '贵D12345', phoneCode: 'fresh-phone-proof' }); assert.equal(env.storage.get('feilv_user_id'), 1); assert.equal(env.storage.get('feilv_role'), 'RIDER')
    assert.equal(env.storage.get('native_pending_withdrawal'), saved.native_pending_withdrawal); assert.equal(env.calls.navigation.at(-1).url, '/pages/rider/workbench'); assert.equal(env.calls.navigation.at(-1).method, 'switchTab')
  })
  riderTest('enrollment boundaries: plate 17 chars, no intent or denied proof never reach server', async () => {
    const env = environment(); let posts = 0; const page = await enrollment(env, { async riderEnrollment() { posts++ } })
    await page.register(phoneEvent('unsolicited')); assert.equal(posts, 0)
    page.data.form.plateNo = 'X'.repeat(17); page.preparePhone(); await page.register(phoneEvent('too-long-plate')); assert.equal(posts, 0)
    page.data.form.plateNo = 'X'.repeat(16); page.preparePhone(); await page.register(phoneEvent('', 'getPhoneNumber:fail user deny')); assert.match(env.calls.toast.at(-1), /真实微信手机号/); assert.equal(posts, 0)
  })
  riderTest('enrollment idempotence: existing rider reads verified profile without consuming new phone code', async () => {
    const env = environment(); env.authenticated('RIDER'); let posts = 0
    const page = env.page('pages/rider/register', { async riderMe() { return { riderId: 11, status: 'ACTIVE' } }, async riderEnrollment() { posts++ } }); page.onLoad({}); await page.onShow()
    assert.equal(posts, 0); assert.equal(env.calls.navigation.at(-1).method, 'switchTab'); assert.equal(env.calls.navigation.at(-1).url, '/pages/rider/workbench')
  })
  riderTest('enrollment unknown or foreign response: no token replacement or false success, retry needs fresh proof', async () => {
    for (const outcome of ['network', 'foreign']) {
      const env = environment(); let posts = 0; const page = await enrollment(env, { async riderEnrollment() { posts++; if (outcome === 'network') throw new Error('结果未知'); return authenticated({ userId: 2, role: 'RIDER' }) } })
      page.preparePhone(); await page.register(phoneEvent('one-proof')); assert.equal(posts, 1); assert.equal(env.storage.get('feilv_token'), 'token-1'); assert.equal(env.calls.navigation.length, 0); assert.match(page.data.error, /核实/)
      page.preparePhone(); await page.register(phoneEvent('one-proof')); assert.equal(posts, 1)
    }
  })
  riderTest('enrollment stale proof: hide or account switch before event cannot enroll another subject', async () => {
    for (const change of ['hide', 'switch']) {
      const env = environment(); let posts = 0; const page = await enrollment(env, { async riderEnrollment() { posts++ } }); page.preparePhone()
      if (change === 'hide') page.onHide(); else env.load(path.join(native, 'utils/session.js')).save(authenticated({ userId: 2 }))
      await page.register(phoneEvent('stale-proof')); assert.equal(posts, 0)
    }
  })
  riderTest('rider detail API: dedicated authorized read endpoint, no publisher mutation or track functions', async () => {
    const env = environment(); env.authenticated('RIDER'); const api = env.load(path.join(native, 'utils/api.js'))
    for (const [method, suffix] of [['orderDetail', ''], ['customTask', '/custom-task']]) {
      const pending = api[method](88); assert.equal(env.calls.requests.at(-1).url, 'https://xt.allms.cn/api/v1/rider/orders/88' + suffix); okRequest(env.calls.requests.at(-1), {}); await pending
    }
    for (const method of ['createOrder', 'quote', 'payOrder', 'cancelOrder', 'confirmOrder', 'review', 'complaint', 'orderComplaints', 'orderTrack']) assert.equal(api[method], undefined, method)
  })
  riderTest('rider readonly detail: malicious owner flags never enable publisher controls or leak internal metadata', async () => {
    const env = environment(); env.authenticated('RIDER'); const page = env.page('pages/order/detail', { async orderDetail() { return { id: 88, orderType: 'SEND', status: 'WAIT_PAY', owner: true, canPay: true, canCancel: true, canConfirm: true, userId: 99, riderId: 11, internal: 'secret', fromAddress: '必要地址' } } })
    page.onLoad({ id: '88' }); await page.onShow(); assert.equal(page.data.order.amountText, '待核实'); assert.equal(page.data.order.fromAddress, '必要地址')
    for (const field of ['owner', 'canPay', 'canCancel', 'canConfirm', 'canReview', 'userId', 'riderId', 'internal']) assert.equal(field in page.data.order, false, field)
    for (const handler of ['pay', 'cancel', 'confirm', 'review', 'complaint', 'track']) assert.equal(page[handler], undefined, handler)
    page.workbench(); assert.equal(env.calls.navigation.at(-1).method, 'switchTab')
  })
  riderTest('rider detail isolation: forbidden transferred order clears old data without expiring ordinary session', async () => {
    const env = environment(); env.authenticated('RIDER'); let forbidden = false
    const page = env.page('pages/order/detail', { async orderDetail() { if (forbidden) throw Object.assign(new Error('不再承运'), { code: 403 }); return { id: 88, orderType: 'SEND', status: 'ACCEPTED' } } }); page.onLoad({ id: '88' }); await page.onShow(); assert.ok(page.data.order)
    forbidden = true; await page.load(); assert.equal(page.data.order, null); assert.match(page.data.error, /不再承运/); assert.equal(env.storage.get('feilv_token'), 'token-1')
  })
  riderTest('rider detail lifecycle: hidden or replacement-account late reads cannot restore private details', async () => {
    for (const change of ['hide', 'switch']) {
      const env = environment(); env.authenticated('RIDER'); let respond
      const page = env.page('pages/order/detail', { orderDetail() { return new Promise(resolve => { respond = resolve }) } }); page.onLoad({ id: '88' }); const pending = page.onShow(); await flush()
      if (change === 'hide') page.onHide(); else env.load(path.join(native, 'utils/session.js')).save(authenticated({ userId: 2, role: 'RIDER' }))
      respond({ id: 88, orderType: 'SEND', status: 'ACCEPTED', fromAddress: '旧私密地址' }); await pending; assert.equal(page.data.order, null)
    }
  })
}
