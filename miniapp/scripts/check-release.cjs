'use strict'
// 上传前本地检查：不上传、不发布，不访问微信凭据或远程业务数据。
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '..')
const project = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8'))
const config = require(path.join(root, 'config.js'))
const errors = []
function check(ok, message) { if (!ok) errors.push(message) }
check(/^wx[0-9a-fA-F]{16}$/.test(project.appid || ''), 'project.config.json 仍未填写真实小程序 AppID；touristappid 无法上传或完成真实微信登录。')
check(project.compileType === 'miniprogram' && project.miniprogramRoot === './', '原生入口必须为 miniprogram / ./。')
check(config.BASE_URL === 'https://xt.allms.cn', '正式 API 根地址必须为 https://xt.allms.cn（不带 /api 或 /login）。')
check(config.ALLOW_DEV_SERVER_OVERRIDE === false, '上传前必须关闭 ALLOW_DEV_SERVER_OVERRIDE。')
check(project.setting && project.setting.urlCheck === true, '上传前必须开启合法域名检查 urlCheck。')
check(project.setting && project.setting.minified === true, '上传前必须开启脚本压缩。')
check(project.setting && project.setting.minifyWXML === true && project.setting.minifyWXSS === true, '上传前必须开启 WXML/WXSS 压缩。')
const privatePath = path.join(root, 'project.private.config.json')
if (fs.existsSync(privatePath)) {
  const local = JSON.parse(fs.readFileSync(privatePath, 'utf8'))
  check(!local.setting || local.setting.urlCheck !== false, '开发工具私有配置关闭了域名校验，请恢复后再上传。')
  check(!local.appid || local.appid === project.appid, '私有配置的 AppID 与项目配置不一致。')
}
const exclusions = project.packOptions && project.packOptions.ignore || []
for (const value of ['scripts', 'dist', 'node_modules', 'package.json', 'package-lock.json', 'README.md', 'NATIVE_MIGRATION.md', 'WECHAT_DEPLOYMENT.md']) {
  check(exclusions.some(item => item.value === value), '上传包未排除开发文件/目录：' + value)
}
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
for (const page of app.pages || []) {
  for (const ext of ['js', 'json', 'wxml', 'wxss']) check(fs.existsSync(path.join(root, page + '.' + ext)), page + ' 缺少 .' + ext)
}
if (errors.length) {
  console.error('发布配置检查未通过：\n- ' + errors.join('\n- '))
  process.exitCode = 1
} else {
  console.log('PASS release config: native project, real AppID, official HTTPS origin, locked server, domain check and minification enabled.')
}
console.log('人工必核：微信后台 request/uploadFile 合法域名 https://xt.allms.cn、头像公开读取及持久化、后端匹配 AppID/AppSecret、头像昵称手机号隐私指引和定位权限、真机登录与下单绑定门禁验收。')
console.log('注意：当前支付、钱包赔付和提现均为 MOCK，无真实微信转账或到账；短信/骑手审核仍有演示逻辑，本检查通过不等于生产业务或微信审核通过。')
