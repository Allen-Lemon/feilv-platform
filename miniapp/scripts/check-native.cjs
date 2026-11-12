'use strict'
// 无第三方依赖；不扫描 node_modules/dist 的历史依赖或编译产物。
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '..')
const native = path.join(root, 'native')
const errors = []
const check = (ok, message) => { if (!ok) errors.push(message) }
function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    if (['node_modules', 'dist', '.git'].includes(entry.name)) return []
    const file = path.join(dir, entry.name)
    return entry.isDirectory() ? walk(file) : [file]
  })
}
const files = walk(root)
let jsonCount = 0
let jsCount = 0
for (const file of files) {
  if (file.endsWith('.json')) {
    try { JSON.parse(fs.readFileSync(file, 'utf8')); jsonCount++ } catch (e) { errors.push(file + ': ' + e.message) }
  }
  if (/\.(?:js|cjs)$/.test(file)) {
    const result = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' })
    check(result.status === 0, file + ': ' + result.stderr)
    jsCount++
  }
}
const app = JSON.parse(fs.readFileSync(path.join(native, 'app.json'), 'utf8'))
const project = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8'))
check(project.miniprogramRoot === 'native/', 'project.config.json 必须指向 native/')
check(project.compileType === 'miniprogram', 'compileType 必须为 miniprogram')
check(Boolean(project.appid), '必须显式配置 touristappid 或真实 AppID')
check(new Set(app.pages).size === app.pages.length, 'app.json 包含重复页面')
check(fs.existsSync(path.join(native, app.sitemapLocation)), '缺少 sitemap')
check(app.requiredPrivateInfos.includes('getLocation'), '缺少定位隐私 API 声明')
check(Boolean(app.permission['scope.userLocation'].desc), '缺少定位用途说明')
for (const tab of app.tabBar.list) check(app.pages.includes(tab.pagePath), 'Tab 页未注册：' + tab.pagePath)
const pageDefinitions = new Map()
for (const page of app.pages) {
  for (const ext of ['js', 'json', 'wxml', 'wxss']) check(fs.existsSync(path.join(native, page + '.' + ext)), page + ' 缺少 ' + ext)
  const js = path.join(native, page + '.js')
  if (!fs.existsSync(js)) continue
  let definition
  try {
    vm.runInNewContext(fs.readFileSync(js, 'utf8'), {
      require(name) {
        if (name.endsWith('/page')) return { definePage(value) { definition = value } }
        if (name.endsWith('/format')) return require(path.join(native, 'utils/format.js'))
        return {}
      }
    }, { filename: js })
    check(Boolean(definition && definition.data), page + ' 必须声明原生 Page 数据')
    pageDefinitions.set(page, definition)
  } catch (e) { errors.push(js + ': 页面声明失败 ' + e.message) }
}
let wxmlCount = 0
for (const file of files.filter(f => f.startsWith(native + path.sep))) {
  const text = fs.readFileSync(file, 'utf8')
  if (/\.(?:js|json|wxml|wxss)$/.test(file)) {
    check(!/\buni\s*\.|@dcloudio|from\s*['"](?:vue|vite)['"]|require\(['"](?:vue|vite)['"]|__webpack|createSSRApp/.test(text), file + ': 原生源码存在框架/编译产物引用')
  }
  if (file.endsWith('.js')) {
    for (const match of text.matchAll(/require\(['"]([^'"]+)['"]\)/g)) {
      check(match[1].startsWith('.'), file + ': 不能依赖 npm 运行时模块 ' + match[1])
      const target = path.resolve(path.dirname(file), match[1])
      check(fs.existsSync(target + '.js') || fs.existsSync(target), file + ': require 路径不存在 ' + match[1])
    }
    for (const match of text.matchAll(/['"]\/pages\/([^'"?]+)(?:\?[^'"]*)?['"]/g)) check(app.pages.includes('pages/' + match[1]), file + ': 跳转页面未注册 ' + match[1])
  }
  if (file.endsWith('.wxml')) {
    wxmlCount++
    const page = path.relative(native, file).split(path.sep).join('/').replace(/\.wxml$/, '')
    const definition = pageDefinitions.get(page)
    for (const match of text.matchAll(/(?:bind|catch|capture-bind|capture-catch):?[\w-]+\s*=\s*["']([^"']+)["']/g)) {
      check(Boolean(definition && typeof definition[match[1]] === 'function'), file + ': 事件处理函数不存在 ' + match[1])
    }
    for (const match of text.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
      check(!/[(){};]|=>|\?\.|\.includes\b|\.toFixed\b|\.replace\b/.test(match[1]), file + ': WXML 不允许函数调用或复杂对象表达式 ' + match[1])
    }
    check(!/v-model|v-if|v-for|<script|<template\s|@click/.test(text), file + ': WXML 中存在 Vue 模板')
    // 原生 XML 风格标签配对；屏蔽插值中的比较符。
    const stack = []
    const source = text.replace(/<!--[\s\S]*?-->/g, '').replace(/\{\{[\s\S]*?\}\}/g, '')
    for (const token of source.matchAll(/<\/?([\w-]+)\b[^>]*>/g)) {
      if (token[0].startsWith('</')) check(stack.pop() === token[1], file + ': 标签不匹配 ' + token[0])
      else if (!token[0].endsWith('/>')) stack.push(token[1])
    }
    check(stack.length === 0, file + ': 标签未闭合 ' + stack.join(','))
  }
}
check(!/Math\.random/.test(fs.readFileSync(path.join(native, 'pages/rider/workbench.js'), 'utf8')), '骑手轨迹不得使用随机点')
check(!/wx\.(?:requestPayment|uploadFile)\s*\(/.test(files.filter(f => f.startsWith(native + path.sep) && f.endsWith('.js')).map(f => fs.readFileSync(f, 'utf8')).join('\n')), '尚未接入的支付/上传不得伪装实现')
check(!/statusCode/.test(fs.readFileSync(path.join(native, 'utils/format.js'), 'utf8')), '用户 OrderVO 必须读取 status')
const appidFiles = files.filter(f => f.endsWith('.json') && !f.includes(path.sep + 'node_modules' + path.sep)).filter(f => {
  const value = JSON.parse(fs.readFileSync(f, 'utf8'))
  return Boolean(value.appid && value.appid !== '__UNI__FEILV01') || Boolean(value['mp-weixin'] && value['mp-weixin'].appid)
})
check(appidFiles.length === 1 && appidFiles[0] === path.join(root, 'project.config.json'), '微信 AppID 应仅在 project.config.json 配置')
if (errors.length) {
  console.error(errors.join('\n'))
  process.exitCode = 1
} else console.log('PASS native static checks: ' + jsonCount + ' JSON, ' + jsCount + ' JS/CJS, ' + app.pages.length + ' complete pages, ' + wxmlCount + ' WXML; no framework runtime references.')
