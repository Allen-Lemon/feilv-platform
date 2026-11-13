# 飞毛腿 · 微信原生小程序

本目录是可直接导入微信开发者工具的**纯原生工程**：App/Page + JavaScript + WXML + WXSS + JSON。不使用 uni-app、Vue、Vite，也不需要 npm 安装依赖或生成 dist。

## 目录

```text
miniapp/
├── app.js / app.json / app.wxss   # 应用入口、页面注册、全局样式
├── config.js                     # 官方服务配置
├── sitemap.json
├── project.config.json           # 开发工具配置，miniprogramRoot: ./
├── pages/                        # 13个原生页面，每页四件套
├── utils/                        # 请求、会话、格式化及页面生命周期
├── scripts/                      # Node内置模块实现的检查/测试，不进入上传包
├── package.json / package-lock.json  # 仅测试命令，零第三方依赖
├── NATIVE_MIGRATION.md
└── WECHAT_DEPLOYMENT.md
```

## 开发与上传

1. 微信开发者工具直接导入本目录。根目录已有 `app.json`，无需选择旧 dist 或 native 子目录。
2. 在 `project.config.json` 填写真实小程序 AppID；`touristappid` 只能用于页面查看，不能正式上传或真实微信登录。
3. 默认 API 为 `https://xt.allms.cn`，接口模块拼接 `/api/v1/...`。微信后台配置 request 合法域名并保持域名校验开启。
4. AppSecret 只在后端环境变量中设置，绝不放在小程序中。
5. 编译、预览并完成真机验收后，使用有权限的微信账号上传体验版；正式发布须通过微信审核。

## 本地校验

Node 18+ 即可，无需 `npm install`：

```bash
npm run build --prefix miniapp
npm run check:wechat --prefix miniapp
npm run check:release --prefix miniapp
```

- `build`：原生架构、JS/JSON、页面注册/事件和 wx-mock 行为测试。
- `check:wechat`：已安装微信开发工具时，使用其官方 WXML/WXSS 编译器。
- `check:release`：拦截游客AppID、错误服务地址、关闭域名检查等问题。没有真实AppID时该检查应失败。
- scripts、文档、包清单及生成目录已由 packOptions 排除，不作为业务代码上传。

## 能力边界

已覆盖微信登录/短信备用、地址簿、送买办下单与报价、MOCK支付、订单列表/详情/评价/投诉/轨迹、骑手入驻与工作台。

真实微信授权需配套后端凭据；支付、短信及骑手准入仍存在演示逻辑。没有照片云上传、OCR、真实支付退款、手机号绑定或账号合并，不能把静态测试通过当作真实业务上线或微信审核通过。

详细发布步骤见 [WECHAT_DEPLOYMENT.md](WECHAT_DEPLOYMENT.md)，迁移与检查记录见 [NATIVE_MIGRATION.md](NATIVE_MIGRATION.md)。
