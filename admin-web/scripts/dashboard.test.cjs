// Offline regression tests mount the real dashboard with an in-memory Vue renderer/router.
// These do not replace browser checks for keyboard focus, native links, or responsive layout.
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')
const vue = require('vue')
const { createMemoryHistory, createRouter } = require('vue-router')
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
const filename = path.resolve(__dirname, '../src/views/Dashboard.vue')
const { descriptor, errors } = parse(readFileSync(filename, 'utf8'), { filename })
assert.equal(errors.length, 0)
const compiled = compileScript(descriptor, { id: 'Dashboard', genDefaultAs: '__component' })
const template = compileTemplate({ source: descriptor.template.content, filename, id: 'Dashboard', compilerOptions: { bindingMetadata: compiled.bindings, hoistStatic: false } })
assert.equal(template.errors.length, 0)
const script = new vm.Script(replaceImports(compiled.content) + '\n' + replaceImports(template.code).replace('export function render', 'function render') + '\n__component.render = render; __component', { filename })
const snapshot = value => JSON.parse(JSON.stringify(value))
const tick = () => new Promise(resolve => setImmediate(resolve))
const allMenus = ['users', 'riders', 'orders', 'settlements']

function memoryRenderer() {
  const node = (type, text = '') => ({ type, text, props: {}, children: [], parent: null })
  const detach = child => {
    if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1)
    child.parent = null
  }
  return { node, renderer: vue.createRenderer({
    createElement: type => node(type), createText: text => node('#text', text), createComment: text => node('#comment', text),
    setText: (target, text) => { target.text = text },
    setElementText(target, text) { target.children.forEach(child => { child.parent = null }); target.children = []; target.text = text },
    patchProp: (target, key, previous, next) => { if (next == null) delete target.props[key]; else target.props[key] = next },
    insert(child, parent, anchor = null) { detach(child); const index = anchor ? parent.children.indexOf(anchor) : -1; parent.children.splice(index < 0 ? parent.children.length : index, 0, child); child.parent = parent },
    remove: detach,
    parentNode: target => target.parent,
    nextSibling: target => target.parent?.children[target.parent.children.indexOf(target) + 1] || null
  }) }
}
const find = (node, predicate) => [...(predicate(node) ? [node] : []), ...node.children.flatMap(child => find(child, predicate))]
const text = node => node.text + node.children.map(text).join('')
const cardNodes = root => find(root, node => /(?:^|\s)stat-card(?:\s|$)/.test(node.props.class || ''))
const click = (overrides = {}) => ({ button: 0, defaultPrevented: false, preventDefault() { this.defaultPrevented = true }, currentTarget: { getAttribute: () => null }, ...overrides })

async function harness(t, { menus = [], provideMenus = true, stats = async () => ({ userCount: 1234, riderCount: 8, orderCountToday: 2, orderCountTotal: 25, incomeTodayFen: 12345 }) } = {}) {
  const menuCodes = vue.ref(menus)
  const calls = []
  const implementation = { stats }
  const component = script.runInNewContext({ __imports: {
    vue,
    '../api': { adminApi: { stats: async () => { calls.push('stats'); return implementation.stats() } } },
    '../components/AppIcon.vue': { default: { render: () => vue.h('span') } }
  }, localStorage: { getItem: key => key === 'feilv_admin_nickname' ? '测试管理员' : JSON.stringify(allMenus) } })
  let state
  const dashboard = { ...component, setup(props, context) { state = component.setup(props, context); return state } }
  const router = createRouter({ history: createMemoryHistory(), routes: ['/dashboard', '/users', '/riders', '/orders', '/settlements', '/system'].map(route => ({ path: route, component: { render: () => null } })) })
  await router.push('/dashboard')
  const { renderer, node } = memoryRenderer()
  const app = renderer.createApp(dashboard)
  app.use(router)
  for (const name of ['el-button', 'el-alert']) app.component(name, { render() { return vue.h('div', this.$slots.default?.()) } })
  if (provideMenus) app.provide('adminMenuCodes', menuCodes)
  const root = node('root')
  app.mount(root)
  t.after(() => app.unmount())
  await tick()
  return { state, router, root, menuCodes, calls, implementation, cards: () => cardNodes(root) }
}

test('all five dashboard cards link to their corresponding modules without invented filters', async t => {
  const h = await harness(t, { menus: allMenus })
  assert.deepEqual(snapshot(h.state.cards.value.map(({ label, path, menuCode }) => ({ label, path, menuCode }))), [
    { label: '注册用户', path: '/users', menuCode: 'users' },
    { label: '注册骑手', path: '/riders', menuCode: 'riders' },
    { label: '今日订单', path: '/orders', menuCode: 'orders' },
    { label: '累计订单', path: '/orders', menuCode: 'orders' },
    { label: '今日平台收入', path: '/settlements', menuCode: 'settlements' }
  ])
  const cards = h.cards()
  assert.equal(cards.length, 5)
  for (const [index, card] of cards.entries()) {
    assert.equal(card.type, 'a')
    assert.equal(card.props.href, h.state.cards.value[index].path)
    assert.equal(card.props.title, `进入${h.state.cards.value[index].moduleName}`)
    const event = click()
    card.props.onClick(event)
    await tick()
    assert.equal(h.router.currentRoute.value.fullPath, card.props.href)
    assert.equal(event.defaultPrevented, true)
  }
})

test('empty or missing live menu grants keep every metric visible without a link, ignoring cache', async t => {
  for (const provideMenus of [true, false]) {
    const h = await harness(t, { provideMenus })
    assert.equal(h.cards().length, 5)
    for (const [index, card] of h.cards().entries()) {
      assert.equal(card.type, 'article')
      for (const prop of ['href', 'tabindex', 'role', 'title']) assert.equal(card.props[prop], undefined)
      assert.ok(text(card).includes(h.state.cards.value[index].value))
      const event = click()
      card.props.onClick(event)
      assert.equal(event.defaultPrevented, true)
    }
    await tick()
    assert.equal(h.router.currentRoute.value.path, '/dashboard')
  }
})

test('partial grants and revocation react immediately and stale activation cannot navigate', async t => {
  const h = await harness(t)
  h.menuCodes.value = ['orders']
  await vue.nextTick()
  assert.deepEqual(h.cards().map(card => card.type), ['article', 'article', 'a', 'a', 'article'])
  const staleClick = h.cards()[2].props.onClick
  h.menuCodes.value = []
  const event = click()
  staleClick(event)
  assert.equal(event.defaultPrevented, true)
  await tick()
  assert.equal(h.router.currentRoute.value.path, '/dashboard')
  assert.ok(h.cards().every(card => card.type === 'article'))
  h.menuCodes.value = ['users', 'settlements']
  await vue.nextTick()
  assert.deepEqual(h.cards().map(card => card.type), ['a', 'article', 'article', 'article', 'a'])
  assert.equal(h.cards().length, 5)
})

test('modified clicks retain native link behavior rather than forcing SPA navigation', async t => {
  const h = await harness(t, { menus: allMenus })
  for (const modifier of ['ctrlKey', 'metaKey', 'shiftKey', 'altKey']) {
    const event = click({ [modifier]: true })
    h.cards()[0].props.onClick(event)
    assert.equal(event.defaultPrevented, false)
  }
  const event = click({ button: 1 })
  h.cards()[0].props.onClick(event)
  await tick()
  assert.equal(event.defaultPrevented, false)
  assert.equal(h.router.currentRoute.value.path, '/dashboard')
})

test('navigation leaves metric formatting, zero values, missing values and refresh intact', async t => {
  const h = await harness(t, { menus: allMenus })
  assert.deepEqual(snapshot(h.state.cards.value.map(card => card.value)), ['1,234', '8', '2', '25', '123.45'])
  assert.equal(h.state.cards.value[4].unit, '元')
  h.implementation.stats = async () => ({ userCount: 0, riderCount: 0, orderCountToday: 0, orderCountTotal: 0, incomeTodayFen: 0 })
  await h.state.load()
  assert.deepEqual(snapshot(h.state.cards.value.map(card => card.value)), ['0', '0', '0', '0', '0.00'])
  h.implementation.stats = async () => ({})
  await h.state.load()
  assert.deepEqual(snapshot(h.state.cards.value.map(card => card.value)), ['—', '—', '—', '—', '—'])
  assert.equal(h.calls.length, 3)
  assert.ok(h.state.updatedAt.value)
  assert.equal(h.state.loading.value, false)
  assert.equal(h.cards().length, 5)
})

test('failed refresh remains retryable and duplicate refreshes are blocked', async t => {
  const h = await harness(t, { stats: async () => { throw new Error('offline') } })
  assert.match(h.state.error.value, /暂时未能获取经营数据/)
  assert.equal(h.state.loading.value, false)
  let resolve
  h.implementation.stats = () => new Promise(done => { resolve = done })
  const pending = h.state.load()
  await h.state.load()
  assert.equal(h.calls.length, 2)
  assert.equal(h.state.loading.value, true)
  resolve({ userCount: 10 })
  await pending
  assert.equal(h.state.error.value, '')
  assert.equal(h.state.cards.value[0].value, '10')
  assert.equal(h.state.loading.value, false)
})
