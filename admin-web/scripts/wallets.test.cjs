// Execute production Vue setup scripts with live Vue reactivity and API/router doubles.
// Compile every tested template as well; never call a server, payment provider or database.
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
const wallet = (overrides = {}) => ({ userId: 42, nickname: '用户', mobile: '138****0000', role: 'RIDER', status: 1, availableFen: 1234, frozenFen: 29, debtFen: 0, totalIncomeFen: 2200, totalWithdrawnFen: 937, channel: 'MOCK', withdrawAllowed: true, ...overrides })
const entry = (overrides = {}) => ({ id: 7, type: 'COMPLAINT_DEDUCTION', amountFen: -29, availableAfterFen: 1234, frozenAfterFen: 29, debtAfterFen: 0, orderId: 21, complaintId: 8, withdrawalId: null, remark: '投诉扣款', createdTime: '2026-11-15 12:00:00', ...overrides })
const withdrawal = (overrides = {}) => ({ id: 8, userId: 42, amountFen: 29, status: 'PENDING', requestId: 'request-001', reason: null, reviewedBy: null, reviewedTime: null, createdTime: '2026-11-15 12:00:00', channel: 'MOCK', ...overrides })
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
const components = Object.fromEntries(['Wallets', 'Withdrawal', 'UserList', 'Settlement', 'Layout'].map(name => {
  const filename = path.join(root, `src/${name === 'Layout' ? 'layout' : 'views'}/${name}.vue`)
  const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename })
  assert.equal(errors.length, 0)
  const compiled = compileScript(descriptor, { id: name, genDefaultAs: '__component' })
  const template = compileTemplate({ source: descriptor.template.content, filename, id: name, compilerOptions: { bindingMetadata: compiled.bindings } })
  assert.equal(template.errors.length, 0)
  return [name, { template: descriptor.template.content, script: new vm.Script(replaceImports(compiled.content) + '\n__component', { filename }) }]
}))
function harness(t, name, { menus = ['wallets', 'withdrawals'], query = {}, overrides = {} } = {}) {
  const route = vue.reactive({ path: name === 'Withdrawal' ? '/withdrawals' : `/${name.toLowerCase()}`, query })
  const menuCodes = vue.ref(menus)
  const calls = [], navigations = [], messages = [], confirmations = [], stops = [], provided = {}, storage = new Map()
  let mounted = () => {}, beforeUnmount = () => {}
  const implementations = {
    wallets: async params => pageOf([wallet()], params.page), walletEntries: async (_id, params) => pageOf([entry()], params.page),
    withdrawals: async params => pageOf([withdrawal()], params.page),
    reviewWithdrawal: async (id, body) => withdrawal({ id, ...body, status: body.decision, reviewedBy: 1, reviewedTime: '2026-11-15 13:00:00' }),
    customerUsers: async params => pageOf([wallet()], params.page), customerUser: async id => wallet({ userId: id }),
    settlements: async () => [], profile: async () => ({ nickname: '管理员', menus: [], roles: [] }), ...overrides
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
function startReview(h, draft = {}) { h.state.openDetail(h.state.rows.value[0]); Object.assign(h.state.reviewForm, { decision: 'APPROVED', reason: '  已核实  ', ...draft }) }

test('wallet API contracts expose only list, ledger and review endpoints; fen is never converted', () => {
  const calls = []
  const request = Object.fromEntries(['get', 'post', 'put', 'delete'].map(method => [method, (...args) => calls.push({ method, args: snapshot(args) })]))
  const source = replaceImports(readFileSync(path.join(root, 'src/api/index.js'), 'utf8')).replace('export const adminApi', 'const adminApi')
  const api = new vm.Script(source + '\nadminApi').runInNewContext({ __imports: { './request': { default: request } } })
  api.wallets({ userId: '42', page: 1, size: 15 }); api.walletEntries('42', { page: 0, size: 15 }); api.withdrawals({ status: 'PENDING', page: 2, size: 15 }); api.reviewWithdrawal(8, { decision: 'APPROVED', reason: '核实' })
  assert.deepEqual(calls, [
    { method: 'get', args: ['/v1/admin/wallets', { params: { userId: '42', page: 1, size: 15 } }] },
    { method: 'get', args: ['/v1/admin/wallets/42/entries', { params: { page: 0, size: 15 } }] },
    { method: 'get', args: ['/v1/admin/withdrawals', { params: { status: 'PENDING', page: 2, size: 15 } }] },
    { method: 'post', args: ['/v1/admin/withdrawals/8/review', { decision: 'APPROVED', reason: '核实' }] }
  ])
  assert.doesNotMatch(components.Wallets.template, /<el-button[^>]*>\s*(充值|修改余额)/)
})

test('wallet and withdrawal pages wait for live grants and fail closed when access is revoked', async t => {
  for (const name of ['Wallets', 'Withdrawal']) {
    const h = harness(t, name, { menus: [] }); await tick(); await h.state.load()
    if (name === 'Wallets') { await h.state.openEntries(42); h.state.viewUser(42) } else { h.state.openDetail(withdrawal()); await h.state.submitReview() }
    assert.equal(h.calls.length, 0)
    h.menuCodes.value = [name === 'Wallets' ? 'wallets' : 'withdrawals']; await tick(); assert.equal(h.state.rows.value.length, 1)
    if (name === 'Wallets') await h.state.openEntries(42); else startReview(h)
    h.menuCodes.value = []
    assert.equal(h.state.rows.value.length, 0); assert.equal(h.state.total.value, 0)
    assert.equal(name === 'Wallets' ? h.state.entriesOpen.value : h.state.detailOpen.value, false)
    assert.equal(h.state.loading.value, false)
  }
})

test('both pages apply precise route userId filters, page15, status filters and preserve unrelated query', async t => {
  for (const [name, method] of [['Wallets', 'wallets'], ['Withdrawal', 'withdrawals']]) {
    const h = harness(t, name, { query: { userId: '42', keep: 'yes' } }); await tick()
    assert.deepEqual(called(h, method)[0].args, [{ userId: '42', page: 0, size: 15 }])
    h.state.query.userId = ' 43 '
    if (name === 'Withdrawal') h.state.query.status = 'PENDING'
    await h.state.load(2)
    assert.deepEqual(called(h, method)[1].args, [{ ...(name === 'Withdrawal' ? { status: 'PENDING' } : {}), userId: '43', page: 1, size: 15 }])
    assert.equal(h.state.page.value, 2)
    h.route.query.userId = '44'; await tick(); assert.equal(called(h, method)[2].args[0].userId, '44'); assert.equal(h.state.page.value, 1)
    await h.state.resetFilters(); await tick()
    assert.deepEqual(snapshot(h.route.query), { keep: 'yes' }); assert.deepEqual(called(h, method)[3].args, [{ page: 0, size: 15 }])
    assert.match(components[name].template, /:page-size="15"/)
  }
  const h = harness(t, 'Withdrawal'); await tick()
  assert.deepEqual(snapshot(h.state.statuses.map(item => item.code)), ['PENDING', 'APPROVED', 'REJECTED'])
  for (const status of ['', 'PENDING', 'APPROVED', 'REJECTED']) { h.state.query.status = status; await h.state.load(1); assert.equal(called(h, 'withdrawals').at(-1).args[0].status, status || undefined) }
})

test('invalid route IDs, filters and pages never silently broaden a query', async t => {
  for (const name of ['Wallets', 'Withdrawal']) {
    for (const userId of ['', null, '0', '-1', '1.1', 'abc', ['42', '43']]) {
      const h = harness(t, name, { query: { userId } }); await tick(); assert.equal(h.calls.length, 0); assert.ok(h.state.listError.value); assert.equal(h.state.loading.value, false)
    }
    const h = harness(t, name); await tick()
    for (const p of [0, -1, 1.1, '2']) { await h.state.load(p); assert.ok(h.state.listError.value); assert.equal(h.state.rows.value.length, 0) }
    if (name === 'Withdrawal') { h.state.query.status = 'UNKNOWN'; await h.state.load(1); assert.ok(h.state.listError.value) }
    assert.equal(h.calls.length, 1)
  }
})

test('wallet ledger uses user ID, zero-based pagination, exact signed amounts and masked phone display', async t => {
  const h = harness(t, 'Wallets'); await tick(); await h.state.openEntries(42); await h.state.loadEntries(2)
  assert.deepEqual(called(h, 'walletEntries').map(call => call.args), [['42', { page: 0, size: 15 }], ['42', { page: 1, size: 15 }]])
  assert.equal(h.state.entriesPage.value, 2); assert.equal(h.state.entries.value[0].amountFen, -29)
  for (const [value, expected] of [[29, '+0.29'], [-29, '-0.29'], [0, '+0.00'], [Number.MAX_SAFE_INTEGER, '+90071992547409.91'], [null, '—'], [0.1, '—'], ['29', '—']]) assert.equal(h.state.signedFen(value), expected)
  assert.equal(h.state.maskedMobile('13800000000'), '138****0000'); assert.equal(h.state.maskedMobile('138****0000'), '138****0000')
  for (const value of [null, '138', '13800000000extra', 13800000000]) assert.equal(h.state.maskedMobile(value), '—')
  for (const field of ['userId', 'nickname', 'mobile', 'role', 'status', 'availableFen', 'frozenFen', 'debtFen', 'totalIncomeFen', 'totalWithdrawnFen', 'channel']) assert.ok(components.Wallets.template.includes(field), field)
  for (const field of ['id', 'type', 'amountFen', 'availableAfterFen', 'frozenAfterFen', 'debtAfterFen', 'orderId', 'complaintId', 'withdrawalId', 'remark', 'createdTime']) assert.ok(components.Wallets.template.includes(field), field)
  assert.match(components.Wallets.template, /70%/); assert.match(components.Wallets.template, /后续入账先抵欠款/)
  assert.equal(h.calls.some(call => !['wallets', 'walletEntries'].includes(call.method)), false)
})

test('empty pages differ from errors; malformed pages, network failures and forbidden responses clear old data', async t => {
  for (const [name, method] of [['Wallets', 'wallets'], ['Withdrawal', 'withdrawals']]) {
    const h = harness(t, name); await tick()
    h.implementations[method] = async () => pageOf([], 0, 0); await h.state.load(1)
    assert.equal(h.state.listError.value, ''); assert.equal(h.state.rows.value.length, 0); assert.equal(h.state.total.value, 0)
    for (const value of [null, {}, { content: [], number: 0 }, pageOf([], -1), pageOf([], 0, -1), pageOf(Array(16).fill(name === 'Wallets' ? wallet() : withdrawal())), pageOf([{}])]) {
      h.implementations[method] = async () => value; await h.state.load(); assert.ok(h.state.listError.value); assert.equal(h.state.rows.value.length, 0); assert.equal(h.state.loading.value, false)
    }
    h.implementations[method] = async () => { throw new Error('network') }; await h.state.load(); assert.ok(h.state.listError.value)
    h.implementations[method] = async () => { throw { response: { status: 403 } } }; await h.state.load()
    assert.equal(h.state.accessDenied.value, true); assert.equal(h.state.canManage.value, false)
    const count = h.calls.length; await h.state.load(); assert.equal(h.calls.length, count)
  }
})

test('wallet ledger errors clear rows and late/closed/revoked/unmounted responses cannot repopulate data', async t => {
  const h = harness(t, 'Wallets'); await tick(); await h.state.openEntries(42)
  h.implementations.walletEntries = async () => { throw new Error('network') }; await h.state.loadEntries(); assert.equal(h.state.entries.value.length, 0); assert.ok(h.state.entriesError.value)
  h.implementations.walletEntries = async () => pageOf([], 0, 0); await h.state.loadEntries(); assert.equal(h.state.entriesError.value, ''); assert.equal(h.state.entriesTotal.value, 0)
  for (const action of ['close', 'revoke', 'unmount']) {
    const h2 = harness(t, 'Wallets'); await tick(); const pending = deferred(); h2.implementations.walletEntries = () => pending.promise
    const opening = h2.state.openEntries(42)
    if (action === 'close') h2.state.entriesOpen.value = false
    else if (action === 'revoke') h2.menuCodes.value = []
    else h2.unmount()
    pending.resolve(pageOf([entry()])); await opening
    assert.equal(h2.state.entries.value.length, 0); assert.equal(h2.state.entriesOpen.value, false); assert.equal(h2.state.entriesLoading.value, false)
  }
  const pending = deferred(); h.implementations.walletEntries = () => pending.promise; const older = h.state.openEntries(42)
  h.implementations.walletEntries = async () => pageOf([entry({ id: 99 })]); await h.state.openEntries(43)
  pending.resolve(pageOf([entry()])); await older; assert.equal(h.state.entriesUserId.value, '43'); assert.equal(h.state.entries.value[0].id, 99)
})

test('list responses are latest-only, and no late response restores revoked or unmounted pages', async t => {
  for (const [name, method] of [['Wallets', 'wallets'], ['Withdrawal', 'withdrawals']]) {
    const h = harness(t, name); await tick(); const pending = deferred(); h.implementations[method] = () => pending.promise
    const older = h.state.load(1)
    h.implementations[method] = async () => pageOf([name === 'Wallets' ? wallet({ userId: 99 }) : withdrawal({ id: 99 })], 1)
    await h.state.load(2); pending.resolve(pageOf([])); await older
    assert.equal(h.state.page.value, 2); assert.equal(h.state.rows.value[0][name === 'Wallets' ? 'userId' : 'id'], 99)
    for (const action of ['revoke', 'unmount']) {
      const h2 = harness(t, name); await tick(); const gate = deferred(); h2.implementations[method] = () => gate.promise
      const loading = h2.state.load(); if (action === 'revoke') h2.menuCodes.value = []; else h2.unmount()
      gate.resolve(pageOf([name === 'Wallets' ? wallet() : withdrawal()])); await loading
      assert.equal(h2.state.rows.value.length, 0)
    }
  }
})

test('approved/rejected reviews confirm precise amounts, user/request/MOCK and never send balance edits', async t => {
  for (const decision of ['APPROVED', 'REJECTED']) {
    const h = harness(t, 'Withdrawal'); await tick(); startReview(h, { decision })
    await h.state.submitReview()
    assert.deepEqual(called(h, 'reviewWithdrawal')[0].args, [8, { decision, reason: '已核实' }])
    for (const text of ['0.29', '用户 ID 42', 'request-001', 'MOCK', '已核实']) assert.ok(h.confirmations[0][0].includes(text), text)
    assert.match(h.confirmations[0][0], decision === 'APPROVED' ? /不会实际出款/ : /先抵欠款.*返还可用余额/)
    assert.match(h.messages[0], decision === 'APPROVED' ? /未实际出款/ : /先抵欠款/)
    assert.equal(h.state.detailOpen.value, false)
    h.state.openDetail(h.state.rows.value[0]); assert.equal(h.state.isReviewable(h.state.detail.value), false)
    await h.state.submitReview(); assert.equal(called(h, 'reviewWithdrawal').length, 1)
    assert.equal(h.calls.some(call => /pay|recharge|balance/i.test(call.method)), false)
  }
  for (const field of ['amountFen', 'channel', 'status', 'requestId', 'createdTime', 'reviewedTime', 'reviewedBy', 'reason']) assert.ok(components.Withdrawal.template.includes(field), field)
})

test('reason bounds, invalid decision, terminal states, invalid money/accounts and non-MOCK channels block review', async t => {
  const h = harness(t, 'Withdrawal'); await tick()
  for (const draft of [{ reason: '' }, { reason: ' ' }, { reason: '字'.repeat(256) }, { decision: 'PENDING' }]) {
    startReview(h, draft); await h.state.submitReview(); assert.ok(h.state.reviewError.value)
  }
  assert.equal(h.confirmations.length, 0)
  for (const row of [withdrawal({ status: 'APPROVED' }), withdrawal({ status: 'REJECTED' }), withdrawal({ status: 'UNKNOWN' }), withdrawal({ amountFen: null }), withdrawal({ amountFen: -1 }), withdrawal({ amountFen: 0 }), withdrawal({ amountFen: 0.1 }), withdrawal({ amountFen: '29' }), withdrawal({ amountFen: Number.MAX_SAFE_INTEGER + 1 }), withdrawal({ userId: null }), withdrawal({ id: null }), withdrawal({ channel: 'WECHAT' }), withdrawal({ channel: null }), withdrawal({ requestId: '' })]) assert.equal(h.state.isReviewable(row), false, JSON.stringify(row))
  assert.equal(called(h, 'reviewWithdrawal').length, 0)
  startReview(h, { reason: '字'.repeat(255) }); await h.state.submitReview(); assert.equal(called(h, 'reviewWithdrawal').length, 1)
})

test('confirmation cancellation/close preserves reason draft and sends no review', async t => {
  const h = harness(t, 'Withdrawal'); await tick()
  for (const response of ['cancel', 'close']) {
    startReview(h); h.box.response = async () => { throw response }; await h.state.submitReview()
    assert.equal(h.state.reviewForm.reason, '  已核实  '); assert.equal(h.state.reviewError.value, ''); assert.equal(h.state.needsRefresh.value, false)
  }
  assert.equal(called(h, 'reviewWithdrawal').length, 0); assert.equal(h.messages.length, 0)
})

test('double clicks are locked throughout confirmation and review request', async t => {
  const h = harness(t, 'Withdrawal'); await tick(); startReview(h)
  const confirming = deferred(), posting = deferred(); h.box.response = () => confirming.promise
  h.implementations.reviewWithdrawal = () => posting.promise
  const reviewing = h.state.submitReview(); await h.state.submitReview(); assert.equal(h.confirmations.length, 1)
  confirming.resolve(true); await tick(); await h.state.submitReview(); assert.equal(called(h, 'reviewWithdrawal').length, 1)
  let closed = false; h.state.beforeCloseDetail(() => { closed = true }); assert.equal(closed, false)
  posting.resolve(withdrawal({ status: 'APPROVED' })); await reviewing
  assert.equal(h.state.reviewBusy.value, false); assert.equal(h.messages.length, 1)
})

test('changed drafts, route/filter refreshes, revoked grants, closed dialogs and unmount invalidate confirmation', async t => {
  for (const action of ['edit', 'refresh', 'route', 'revoke', 'close', 'unmount']) {
    const h = harness(t, 'Withdrawal'); await tick(); startReview(h)
    const pending = deferred(); h.box.response = () => pending.promise; const reviewing = h.state.submitReview()
    if (action === 'edit') h.state.reviewForm.reason = '变更原因'
    else if (action === 'refresh') await h.state.load()
    else if (action === 'route') h.route.query.userId = '43'
    else if (action === 'revoke') h.menuCodes.value = []
    else if (action === 'close') h.state.detailOpen.value = false
    else h.unmount()
    pending.resolve(true); await reviewing
    assert.equal(called(h, 'reviewWithdrawal').length, 0, action); assert.equal(h.messages.length, 0)
  }
})

test('uncertain network/409 reviews cannot retry by reopening; explicit refresh reconciles terminal status', async t => {
  for (const error of [new Error('timeout'), { code: 409 }, { response: { status: 409 } }]) {
    const h = harness(t, 'Withdrawal'); await tick(); startReview(h)
    h.implementations.reviewWithdrawal = async () => { throw error }; await h.state.submitReview()
    assert.equal(h.state.needsRefresh.value, true); assert.match(h.state.reviewError.value, /刷新核对/); assert.equal(h.messages.length, 0)
    await h.state.submitReview(); h.state.detailOpen.value = false; startReview(h); await h.state.submitReview()
    assert.equal(h.state.needsRefresh.value, true); assert.equal(called(h, 'reviewWithdrawal').length, 1)
    h.implementations.withdrawals = async () => pageOf([withdrawal({ status: 'APPROVED', reviewedBy: 2, reviewedTime: 'now', reason: '其他管理员处理' })])
    await h.state.refreshDetail(); assert.equal(h.state.detail.value.status, 'APPROVED'); assert.equal(h.state.needsRefresh.value, false)
    await h.state.submitReview(); assert.equal(called(h, 'reviewWithdrawal').length, 1)
  }
})

test('malformed/mismatched review results never claim success and block until refresh', async t => {
  for (const result of [undefined, {}, withdrawal(), withdrawal({ status: 'APPROVED', id: 9 }), withdrawal({ status: 'APPROVED', userId: 99 }), withdrawal({ status: 'APPROVED', amountFen: 30 }), withdrawal({ status: 'APPROVED', channel: 'WECHAT' }), withdrawal({ status: 'APPROVED', requestId: 'other' })]) {
    const h = harness(t, 'Withdrawal'); await tick(); startReview(h)
    h.implementations.reviewWithdrawal = async () => result; await h.state.submitReview()
    assert.equal(h.messages.length, 0); assert.equal(h.state.needsRefresh.value, true); assert.ok(h.state.reviewError.value)
  }
})

test('failed/missing-row refresh keeps review disabled; successful PENDING reconciliation requires fresh reason', async t => {
  const h = harness(t, 'Withdrawal'); await tick(); startReview(h)
  h.implementations.reviewWithdrawal = async () => { throw new Error('timeout') }; await h.state.submitReview()
  h.implementations.withdrawals = async () => { throw new Error('network') }; await h.state.refreshDetail()
  assert.equal(h.state.needsRefresh.value, true); assert.equal(h.state.rows.value.length, 0)
  h.implementations.withdrawals = async () => pageOf([], 0, 0); await h.state.refreshDetail(); assert.equal(h.state.needsRefresh.value, true)
  h.implementations.withdrawals = async () => pageOf([withdrawal()]); await h.state.refreshDetail()
  assert.equal(h.state.needsRefresh.value, false); assert.equal(h.state.reviewForm.reason, '')
  await h.state.submitReview(); assert.equal(called(h, 'reviewWithdrawal').length, 1)
})

test('revocation or unmount while posting clears data and suppresses late success', async t => {
  for (const action of ['revoke', 'unmount']) {
    const h = harness(t, 'Withdrawal'); await tick(); startReview(h)
    const pending = deferred(); h.implementations.reviewWithdrawal = () => pending.promise
    const reviewing = h.state.submitReview(); await tick()
    if (action === 'revoke') h.menuCodes.value = []; else h.unmount()
    pending.resolve(withdrawal({ status: 'APPROVED' })); await reviewing
    assert.equal(h.messages.length, 0); assert.equal(h.state.detailOpen.value, false); assert.equal(h.state.detail.value, null)
  }
  const h = harness(t, 'Withdrawal'); await tick(); startReview(h)
  h.implementations.reviewWithdrawal = async () => { throw { code: 403 } }; await h.state.submitReview()
  assert.equal(h.state.canManage.value, false); assert.equal(h.state.rows.value.length, 0); assert.equal(h.state.detail.value, null)
})

test('user, settlement, wallet and withdrawal cross-links require live destination grants', async t => {
  const user = harness(t, 'UserList', { menus: [] }), settlement = harness(t, 'Settlement', { menus: [] }), wallets = harness(t, 'Wallets', { menus: ['wallets'] }), withdrawals = harness(t, 'Withdrawal', { menus: ['withdrawals'] })
  await tick(); user.state.viewWallet(42); settlement.state.viewWallet(); wallets.state.viewUser(42); wallets.state.viewWithdrawals(42); withdrawals.state.viewWallet(42)
  assert.equal(user.navigations.length + settlement.navigations.length + wallets.navigations.length + withdrawals.navigations.length, 0)
  user.menuCodes.value = ['wallets']; settlement.menuCodes.value = ['wallets']; wallets.menuCodes.value = ['wallets', 'users', 'withdrawals']; withdrawals.menuCodes.value = ['withdrawals', 'wallets']
  user.state.viewWallet(42); settlement.state.viewWallet(); wallets.state.viewUser(42); wallets.state.viewWithdrawals(42); withdrawals.state.viewWallet(42)
  assert.deepEqual(user.navigations[0], { path: '/wallets', query: { userId: '42' } }); assert.equal(settlement.navigations[0], '/wallets')
  assert.deepEqual(wallets.navigations[0], { path: '/users', query: { userId: '42' } }); assert.deepEqual(wallets.navigations[1], { path: '/withdrawals', query: { userId: '42' } }); assert.deepEqual(withdrawals.navigations[0], { path: '/wallets', query: { userId: '42' } })
  wallets.menuCodes.value = ['wallets']; wallets.state.viewUser(42); assert.equal(wallets.navigations.length, 2)
  assert.match(components.UserList.template, /v-if="canViewWallets"[^>]*viewWallet\(row.userId\)[^>]*>钱包/)
  assert.match(components.Settlement.template, /v-if="canViewWallets"/)
})

test('server-observed terminal states remain terminal even if a later stale list says PENDING', async t => {
  const h = harness(t, 'Withdrawal', { overrides: { withdrawals: async () => pageOf([withdrawal({ status: 'REJECTED' })]) } }); await tick()
  h.implementations.withdrawals = async () => pageOf([withdrawal()]); await h.state.load(); startReview(h); await h.state.submitReview()
  assert.equal(h.state.isReviewable(h.state.detail.value), false); assert.equal(called(h, 'reviewWithdrawal').length, 0)
})

test('ledger malformed data and business/HTTP authorization errors close and clear sensitive views', async t => {
  const h = harness(t, 'Wallets'); await tick(); await h.state.openEntries(42)
  h.implementations.walletEntries = async () => ({ content: [entry()], number: 0 }); await h.state.loadEntries()
  assert.ok(h.state.entriesError.value); assert.equal(h.state.entries.value.length, 0)
  for (const error of [{ code: 401 }, { response: { status: 401 } }, { response: { data: { code: 403 }, status: 200 } }]) {
    const h2 = harness(t, 'Wallets'); await tick(); h2.implementations.walletEntries = async () => { throw error }; await h2.state.openEntries(42)
    assert.equal(h2.state.canManage.value, false); assert.equal(h2.state.entriesOpen.value, false); assert.equal(h2.state.rows.value.length, 0); assert.equal(h2.state.entries.value.length, 0)
  }
})

test('large fen amounts remain exact in confirmation and never become floating-point yuan payloads', async t => {
  const h = harness(t, 'Withdrawal', { overrides: {
    withdrawals: async () => pageOf([withdrawal({ amountFen: Number.MAX_SAFE_INTEGER })]),
    reviewWithdrawal: async (_id, body) => withdrawal({ amountFen: Number.MAX_SAFE_INTEGER, status: body.decision })
  } }); await tick(); startReview(h); await h.state.submitReview()
  assert.match(h.confirmations[0][0], /90071992547409\.91/); assert.deepEqual(called(h, 'reviewWithdrawal')[0].args, [8, { decision: 'APPROVED', reason: '已核实' }]); assert.equal(h.messages.length, 1)
})

test('Layout registers route menus/icons without granting ordinary roles or trusting cached grants', async t => {
  const ordinary = harness(t, 'Layout', { overrides: { profile: async () => ({ menus: [{ code: 'wallets', path: '/wallets', name: '用户钱包' }], roles: [] }) } }); await tick()
  assert.deepEqual(snapshot(ordinary.provided.adminMenuCodes.value), ['wallets']); assert.equal(ordinary.state.iconFor('wallets'), 'wallet'); assert.equal(ordinary.state.iconFor('withdrawals'), 'withdrawal')
  const superAdmin = harness(t, 'Layout', { overrides: { profile: async () => ({ menus: [], roles: ['SUPER_ADMIN'] }) } }); await tick()
  assert.ok(superAdmin.provided.adminMenuCodes.value.includes('wallets')); assert.ok(superAdmin.provided.adminMenuCodes.value.includes('withdrawals'))
  const failed = harness(t, 'Layout', { overrides: { profile: async () => { throw new Error('403') } } }); await tick(); assert.deepEqual(snapshot(failed.provided.adminMenuCodes.value), [])
  const source = readFileSync(path.join(root, 'src/router/index.js'), 'utf8'), routes = []
  const rewritten = replaceImports(source).replace('export default router', '')
  let guard
  new vm.Script(rewritten).runInNewContext({ __imports: { 'vue-router': { createWebHistory() {}, createRouter(config) { routes.push(...config.routes); return { beforeEach(fn) { guard = fn } } } } }, localStorage: { getItem: key => key === 'feilv_admin_token' ? 'token' : '["orders"]' }, document: {} })
  for (const [name, title] of [['wallets', '用户钱包'], ['withdrawals', '提现审核']]) {
    const route = routes.find(item => item.path === '/').children.find(item => item.path === name)
    assert.equal(route.name, name); assert.equal(route.meta.menu, name); assert.equal(route.meta.title, title)
    assert.equal(guard({ path: `/${name}`, meta: route.meta }), true) // fresh page grants decide, not stale router cache
  }
})
