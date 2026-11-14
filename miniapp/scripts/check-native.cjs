'use strict'
// 纯微信原生工程检查，Node 内置模块即可运行，无框架依赖。
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '..')
const errors = []
const check = (ok, message) => { if (!ok) errors.push(message) }
function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    if (['node_modules', 'dist', '.git'].includes(entry.name)) return []
    const file = path.join(dir, entry.name)
    return entry.isDirectory() ? walk(file) : [file]
  })
}
function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')) }
  catch (e) { errors.push(path.relative(root, file) + ': ' + e.message); return null }
}
const files = walk(root)
let jsonCount = 0
let jsCount = 0
for (const file of files) {
  if (file.endsWith('.json')) { if (readJson(file)) jsonCount++ }
  if (/\.(?:js|cjs)$/.test(file)) {
    const result = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' })
    check(result.status === 0, path.relative(root, file) + ': ' + (result.stderr || result.error || '语法检查失败'))
    jsCount++
  }
}
const app = readJson(path.join(root, 'app.json'))
const project = readJson(path.join(root, 'project.config.json'))
const pkg = readJson(path.join(root, 'package.json'))
const lock = readJson(path.join(root, 'package-lock.json'))
if (!app || !project || !pkg || !lock) { console.error(errors.join('\n')); process.exit(1) }
check(project.miniprogramRoot === './', 'project.config.json 必须直接指向根目录 ./')
check(project.compileType === 'miniprogram', 'compileType 必须为 miniprogram')
check(Boolean(project.appid), '必须显式配置游客或真实 AppID')
check(Array.isArray(app.pages) && app.pages.length > 0, 'app.json 必须注册页面')
check(new Set(app.pages).size === app.pages.length, 'app.json 包含重复页面')
check(fs.existsSync(path.join(root, app.sitemapLocation || '')), '缺少 sitemap')
check((app.requiredPrivateInfos || []).includes('getLocation'), '缺少定位隐私 API 声明')
check(Boolean(app.permission && app.permission['scope.userLocation'] && app.permission['scope.userLocation'].desc), '缺少定位用途说明')
for (const tab of (app.tabBar || {}).list || []) check(app.pages.includes(tab.pagePath), 'Tab 页未注册：' + tab.pagePath)
for (const section of ['dependencies', 'devDependencies', 'optionalDependencies']) check(Object.keys(pkg[section] || {}).length === 0, '原生工程不应包含第三方依赖：' + section)
check(Object.keys(lock.packages || {}).every(name => name === ''), '锁文件中残留第三方依赖')
check(!Object.keys(pkg.scripts || {}).some(name => name.startsWith('legacy:')), '应移除旧框架构建命令')
check(!fs.existsSync(path.join(root, 'node_modules')), '原生零依赖工程不应残留 node_modules')
if (fs.existsSync(path.join(root, 'dist'))) {
  function containsFiles(dir) { return fs.readdirSync(dir, { withFileTypes: true }).some(entry => !entry.isDirectory() || containsFiles(path.join(dir, entry.name))) }
  check(!containsFiles(path.join(root, 'dist')), 'dist 中残留旧编译文件；仅允许开发工具占用的空目录')
}
for (const name of ['src', 'native', 'vite.config.js', 'index.html']) check(!fs.existsSync(path.join(root, name)), '原生根目录残留旧架构：' + name)
check(!files.some(f => /\.(vue|uts|scss|sass)$/.test(f)), '存在非原生源码')
const ignores = project.packOptions && project.packOptions.ignore || []
for (const value of ['scripts', 'node_modules', 'dist', 'package.json', 'package-lock.json', 'README.md', 'NATIVE_MIGRATION.md', 'WECHAT_DEPLOYMENT.md']) {
  check(ignores.some(item => item.value === value), '上传包未排除开发文件/目录：' + value)
}
const pageDefinitions = new Map()
for (const page of app.pages) {
  check(/^pages\/[a-z0-9_/-]+$/.test(page) && !page.includes('..'), '非法页面路径：' + page)
  for (const ext of ['js', 'json', 'wxml', 'wxss']) check(fs.existsSync(path.join(root, page + '.' + ext)), page + ' 缺少 ' + ext)
  const js = path.join(root, page + '.js')
  if (!fs.existsSync(js)) continue
  let definition
  try {
    vm.runInNewContext(fs.readFileSync(js, 'utf8'), {
      require(name) {
        if (name.endsWith('/page')) return { definePage(value) { definition = value } }
        if (name.endsWith('/format')) return require(path.join(root, 'utils/format.js'))
        if (name.endsWith('/profile-page')) return require(path.join(root, 'utils/profile-page.js'))
        if (name.endsWith('/address')) return require(path.join(root, 'utils/address.js'))
        return {}
      }
    }, { filename: js })
    check(Boolean(definition && definition.data), page + ' 必须声明原生 Page 数据')
    pageDefinitions.set(page, definition)
  } catch (e) { errors.push(js + ': 页面声明失败 ' + e.message) }
}
// 原生 WXML template 可复用表单，事件必须存在于每个引用页面，不能跳过检查。
const templateConsumers = new Map()
for (const [page, definition] of pageDefinitions) {
  const file = path.join(root, page + '.wxml')
  if (!fs.existsSync(file)) continue
  const text = fs.readFileSync(file, 'utf8')
  const names = new Set()
  for (const match of text.matchAll(/<import\s+src=["']([^"']+)["']\s*\/>/g)) {
    const target = path.resolve(path.dirname(file), match[1])
    const valid = target.startsWith(root + path.sep) && target.endsWith('.wxml') && fs.existsSync(target)
    check(valid, file + ': 模板引用不存在或越界 ' + match[1])
    if (!valid) continue
    const consumers = templateConsumers.get(target) || []
    consumers.push({ page, definition }); templateConsumers.set(target, consumers)
    for (const item of fs.readFileSync(target, 'utf8').matchAll(/<template\s+name=["']([^"']+)["']/g)) names.add(item[1])
  }
  for (const match of text.matchAll(/<template\b[^>]*\bis=["']([^"']+)["']/g)) check(names.has(match[1]), file + ': 引用未导入的模板 ' + match[1])
}
let wxmlCount = 0
const runtimeFiles = files.filter(file => !path.relative(root, file).startsWith('scripts' + path.sep) && /\.(?:js|json|wxml|wxss)$/.test(file))
for (const file of runtimeFiles) {
  const text = fs.readFileSync(file, 'utf8')
  check(!/\buni\s*\.|@dcloudio|from\s*['"](?:vue|vite)['"]|require\(['"](?:vue|vite)['"]|__webpack|createSSRApp/.test(text), file + ': 存在框架/编译产物引用')
  if (file.endsWith('.js')) {
    for (const match of text.matchAll(/require\(['"]([^'"]+)['"]\)/g)) {
      check(match[1].startsWith('.'), file + ': 不能依赖 npm 运行时模块 ' + match[1])
      const target = path.resolve(path.dirname(file), match[1])
      check(target.startsWith(root + path.sep), file + ': require 越过小程序边界')
      check(fs.existsSync(target + '.js') || fs.existsSync(target), file + ': require 路径不存在 ' + match[1])
    }
    for (const match of text.matchAll(/['"]\/pages\/([^'"?]+)(?:\?[^'"]*)?['"]/g)) check(app.pages.includes('pages/' + match[1]), file + ': 跳转页面未注册 ' + match[1])
  }
  if (file.endsWith('.wxml')) {
    wxmlCount++
    const page = path.relative(root, file).split(path.sep).join('/').replace(/\.wxml$/, '')
    const definition = pageDefinitions.get(page)
    const consumers = definition ? [{ page, definition }] : templateConsumers.get(file) || []
    check(consumers.length > 0, file + ': WXML既未注册为页面，也未被页面导入')
    for (const match of text.matchAll(/(?:bind|catch|capture-bind|capture-catch):?[\w-]+\s*=\s*["']([^"']+)["']/g)) {
      for (const consumer of consumers) check(Boolean(consumer.definition && typeof consumer.definition[match[1]] === 'function'), file + ': 引用页面' + consumer.page + '缺少事件处理函数 ' + match[1])
    }
    for (const match of text.matchAll(/\{\{([\s\S]*?)\}\}/g)) check(!/[(){};]|=>|\?\.|\.includes\b|\.toFixed\b|\.replace\b/.test(match[1]), file + ': WXML 不允许函数调用或复杂对象表达式 ' + match[1])
    check(!/v-model|v-if|v-for|<script|@click/.test(text), file + ': WXML 中存在非原生模板')
    for (const match of text.matchAll(/<template\b[^>]*>/g)) check(/\b(?:name|is)=["'][^"']+["']/.test(match[0]), file + ': template 必须使用原生 name/is 属性')
    const stack = []
    const source = text.replace(/<!--[\s\S]*?-->/g, '').replace(/\{\{[\s\S]*?\}\}/g, '')
    for (const token of source.matchAll(/<\/?([\w-]+)\b[^>]*>/g)) {
      if (token[0].startsWith('</')) check(stack.pop() === token[1], file + ': 标签不匹配 ' + token[0])
      else if (!token[0].endsWith('/>')) stack.push(token[1])
    }
    check(stack.length === 0, file + ': 标签未闭合 ' + stack.join(','))
  }
}
check(!/Math\.random/.test(fs.readFileSync(path.join(root, 'pages/rider/workbench.js'), 'utf8')), '骑手轨迹不得使用随机点')
check(!/wx\.requestPayment\s*\(/.test(runtimeFiles.filter(f => f.endsWith('.js')).map(f => fs.readFileSync(f, 'utf8')).join('\n')), '尚未接入的真实支付不得伪装实现')
check(/wx\.uploadFile\s*\(/.test(fs.readFileSync(path.join(root, 'utils/request.js'), 'utf8')), '头像必须使用原生 multipart 上传')
check(fs.readFileSync(path.join(root, 'pages/profile/profile.wxml'), 'utf8').includes('open-type="chooseAvatar"'), '资料页必须提供主动选择头像')
check(!/statusCode/.test(fs.readFileSync(path.join(root, 'utils/format.js'), 'utf8')), '用户 OrderVO 必须读取 status')
const appidFiles = files.filter(f => f.endsWith('.json')).filter(f => { const value = readJson(f); return value && (value.appid || value['mp-weixin']) })
check(appidFiles.length === 1 && appidFiles[0] === path.join(root, 'project.config.json'), 'AppID 应仅在 project.config.json 配置')
if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1 }
else console.log('PASS native root architecture: ' + jsonCount + ' JSON, ' + jsCount + ' JS/CJS, ' + app.pages.length + ' complete pages, ' + wxmlCount + ' WXML; zero framework dependencies; upload exclusions verified.')
