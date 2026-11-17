// 微信原生小程序正式服务地址，推荐填写 origin；请求层也兼容 /api 后缀，勿追加 /api/v1。
// AppID 配置在 rider-miniapp/project.config.json；AppSecret 只能在后端配置。
module.exports = {
  CLIENT: 'RIDER',
  BASE_URL: 'https://xt.allms.cn',
  REQUEST_TIMEOUT: 15000,
  // 默认锁定线上服务；维护者如需本地调试，可临时开启。
  // 即使开启，也仅在 develop 环境生效，体验版/正式版始终使用 BASE_URL。
  ALLOW_DEV_SERVER_OVERRIDE: false
}
