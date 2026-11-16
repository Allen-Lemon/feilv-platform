// Run production router and Vue setup code with in-memory storage/API doubles only.
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')
const vue = require('vue')
const vueRouter = require('vue-router')
const { babelParse, compileScript, compileTemplate, parse } = require('@vue/compiler-sfc')
const root = path.resolve(__dirname, '..')
const snapshot = value => JSON.parse(JSON.stringify(value))
const tick = () => new Promise(resolve => setImmediate(resolve))
const retired = ['marketing', 'content', 'risk']
const menu = (code, route = `/${code}`) => ({ code, name: code, path: route })
const kept = ['dashboard', 'orders', 'users', 'tasks', 'complaints', 'system']
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
const filename = path.join(root, 'src/layout/Layout.vue')
const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename })
assert.equal(errors.length, 0)
const compiled = compileScript(descriptor, { id: 'retired-menus', genDefaultAs: '__component' })
const template = compileTemplate({ source: descriptor.template.content, filename, id: 'retired-menus', compilerOptions: { bindingMetadata: compiled.bindings } })
assert.equal(template.errors.length, 0)
const layoutScript = new vm.Script(replaceImports(compiled.content) + '\n__component', { filename })
function layout({ cache = '[]', profile = () => new Promise(() => {}) } = {}) {
  const storage = new Map([['feilv_admin_menus_data', cache]])
  const provided = {}
  const component = layoutScript.runInNewContext({ __imports: {
    vue: { ...vue, provide: (key, value) => { provided[key] = value } },
    'vue-router': { useRouter: () => ({ push() {} }) },
    '../api': { adminApi: { profile } }, '../components/AppIcon.vue': { default: {} }
  }, localStorage: {
    getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key)
  } })
  return { state: component.setup({}, { expose() {} }), storage, provided }
}
const codes = h => snapshot(h.state.visibleMenus.value.map(item => item.code))
function routerHarness({ token = 'token', menus = JSON.stringify(retired) } = {}) {
  let records
  const document = {}
  const source = replaceImports(readFileSync(path.join(root, 'src/router/index.js'), 'utf8')).replace('export default router', '\nrouter')
  const router = new vm.Script(source).runInNewContext({ __imports: { 'vue-router': {
    createWebHistory: vueRouter.createMemoryHistory,
    createRouter(config) {
      records = config.routes
      // Keep real route matching/redirects/guards, but do not load pages or start network requests.
      const stubPages = routes => routes.forEach(route => { if (route.component) route.component = { render: () => null }; if (route.children) stubPages(route.children) })
      stubPages(config.routes)
      return vueRouter.createRouter(config)
    }
  } }, localStorage: { getItem: key => key === 'feilv_admin_token' ? token : menus }, document })
  return { router, records, document }
}

test('old cached menus are filtered synchronously before profile can render them', () => {
  const cache = [...retired.map(code => menu(code)), ...kept.map(code => menu(code)), menu('legacy-risk', '/risk/reports'), menu('legacy-content', '/content?old=1'), menu('legacy-marketing', '/MARKETING/'), null, {}, { code: 'empty', path: '' }]
  const h = layout({ cache: JSON.stringify(cache) })
  assert.deepEqual(codes(h), kept)
  assert.deepEqual(snapshot(h.provided.adminMenuCodes.value), [])
})

test('invalid localStorage shapes do not crash or revive retired menus', () => {
  for (const cache of ['{broken', 'null', '{}', '1', '"marketing"', '[null,{},false,{"code":"risk","path":"/risk"}]']) {
    assert.deepEqual(codes(layout({ cache })), [], cache)
  }
})

test('profile menus and both persisted caches exclude retired code and path aliases', async () => {
  const menus = [...retired.map(code => menu(code, '/other')), menu('legacy-content', '/content/'), menu('legacy-risk', '/risk#old'), ...kept.map(code => menu(code))]
  const h = layout({ profile: async () => ({ nickname: '运营', roles: [], menus }) })
  await tick()
  assert.deepEqual(codes(h), kept)
  assert.deepEqual(snapshot(h.provided.adminMenuCodes.value), kept)
  assert.deepEqual(JSON.parse(h.storage.get('feilv_admin_menus')), kept)
  assert.deepEqual(JSON.parse(h.storage.get('feilv_admin_menus_data')).map(item => item.code), kept)
})

test('failed profile cannot flash cached retired menus or grant stale permissions', async () => {
  const h = layout({ cache: JSON.stringify([...retired.map(code => menu(code)), menu('complaints')]), profile: async () => { throw new Error('offline') } })
  assert.deepEqual(codes(h), ['complaints'])
  await tick()
  assert.deepEqual(codes(h), ['complaints'])
  assert.deepEqual(snapshot(h.provided.adminMenuCodes.value), [])
})

test('super admin fallback keeps real task/complaint tools but never adds retired menus', async () => {
  const h = layout({ profile: async () => ({ roles: ['SUPER_ADMIN'], menus: [...retired.map(code => menu(code)), menu('orders'), menu('users')] }) })
  await tick()
  for (const code of ['orders', 'users', 'tasks', 'complaints', 'wallets', 'withdrawals', 'wechat-settings']) assert.ok(codes(h).includes(code), code)
  for (const code of retired) assert.equal(codes(h).includes(code), false)
})

test('non-array profile menu data safely produces no ordinary-role grants', async () => {
  for (const menus of [null, {}, 'risk']) {
    const h = layout({ profile: async () => ({ roles: [], menus }) })
    await tick()
    assert.deepEqual(codes(h), [])
    assert.deepEqual(snapshot(h.provided.adminMenuCodes.value), [])
  }
})

test('retired routes no longer exist, while order/user/task/complaint routes are preserved', () => {
  const { records } = routerHarness()
  const children = records.find(route => route.path === '/').children
  for (const code of retired) assert.equal(children.some(route => route.path === code || route.name === code || route.meta?.menu === code), false)
  for (const code of kept) assert.equal(children.find(route => route.path === code).meta.menu, code)
})

test('legacy URLs and nested unknown paths reach dashboard even with retired-only cache', async () => {
  for (const url of ['/marketing', '/content?tab=old', '/risk#old', '/risk/reports', '/unknown-old-link']) {
    const { router, document } = routerHarness()
    await router.push(url)
    assert.equal(router.currentRoute.value.path, '/dashboard', url)
    assert.equal(router.currentRoute.value.name, 'dashboard')
    assert.equal(document.title, '经营看板 · 飞毛腿管理后台')
  }
})

test('legacy URLs still require login and dashboard fallback tolerates corrupt permission cache', async () => {
  const anonymous = routerHarness({ token: null })
  await anonymous.router.push('/marketing')
  assert.equal(anonymous.router.currentRoute.value.path, '/login')
  for (const menus of ['{broken', 'null', '{}', '"risk"', '["orders"]']) {
    const { router } = routerHarness({ menus })
    await router.push('/dashboard')
    assert.equal(router.currentRoute.value.path, '/dashboard')
  }
})

test('remaining business routes still navigate with their existing grants', async () => {
  const { router } = routerHarness({ menus: JSON.stringify(kept) })
  for (const code of ['orders', 'users', 'tasks', 'complaints']) {
    await router.push(`/${code}`)
    assert.equal(router.currentRoute.value.name, code)
  }
})
