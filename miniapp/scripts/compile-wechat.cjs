'use strict'
// 可选的微信官方模板/样式编译校验，不上传代码、不发布小程序。
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const { spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '..')
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
const candidates = [process.env.WECHAT_DEVTOOLS_HOME,
  'C:/Program Files (x86)/Tencent/微信web开发者工具',
  'C:/Program Files/Tencent/微信web开发者工具'].filter(Boolean)
const directory = candidates.map(p => path.join(p, 'resources/app.asar.unpacked/node_modules/wcc-exec'))
  .find(p => fs.existsSync(path.join(p, 'wcc.exe')) && fs.existsSync(path.join(p, 'wcsc.exe')))
if (!directory) {
  console.error('未找到微信官方 wcc/wcsc 编译器。设置 WECHAT_DEVTOOLS_HOME 为微信开发者工具安装目录，或在工具内点击编译。')
  process.exit(1)
}
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'feilv-native-compile-'))
try {
  for (const [exe, files] of [
    ['wcc.exe', app.pages.map(p => p + '.wxml')],
    ['wcsc.exe', ['app.wxss', ...app.pages.map(p => p + '.wxss')]]
  ]) {
    const output = path.join(temp, exe + '.js')
    const result = spawnSync(path.join(directory, exe), ['-o', output, ...files], { cwd: root, encoding: 'utf8', timeout: 60000 })
    if (result.error || result.status !== 0 || !fs.existsSync(output) || fs.statSync(output).size === 0) {
      throw new Error(exe + ' 编译失败：' + (result.error ? result.error.message : result.stderr || result.stdout || result.status))
    }
    console.log('PASS official ' + exe + ': ' + files.length + ' files')
  }
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
} finally {
  // 仅清理此脚本创建的独立临时目录。
  fs.rmSync(temp, { recursive: true, force: true })
}
