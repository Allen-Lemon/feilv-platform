# 飞毛腿 · 微信原生小程序

本目录是可直接导入微信开发者工具的**纯原生工程**：App/Page + JavaScript + WXML + WXSS + JSON。不使用 uni-app、Vue、Vite，也不需要 npm 安装依赖或生成 dist。

## 目录

```text
miniapp/
├── app.js / app.json / app.wxss   # 应用入口、页面注册、全局样式
├── config.js                     # 官方服务配置
├── sitemap.json
├── project.config.json           # 开发工具配置，miniprogramRoot: ./
├── pages/                        # 原生页面，每页四件套
├── utils/                        # 请求、会话、格式化及页面生命周期
├── scripts/                      # Node内置模块实现的检查/测试，不进入上传包
├── package.json / package-lock.json  # 仅测试命令，零第三方依赖
├── NATIVE_MIGRATION.md
└── WECHAT_DEPLOYMENT.md
```

## 开发与上传

1. 微信开发者工具直接导入本目录。根目录已有 `app.json`，无需选择旧 dist 或 native 子目录。
2. 在 `project.config.json` 填写真实小程序 AppID；`touristappid` 只能用于页面查看，不能正式上传或真实微信登录。
3. 默认 API 为 `https://xt.allms.cn`，接口模块拼接 `/api/v1/...`。微信后台配置 request、uploadFile 合法域名并保持域名校验开启。默认填服务 origin，也兼容以 `/api` 结尾的 API_BASE_URL 配置值（本项目配置键为 `BASE_URL`）；公开头像 `/api/v1/avatars/<uuid>.jpg|png` 始终按服务 origin 解析，不重复拼接 `/api`。
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

## 地址选择

- 地址簿新增/编辑：使用微信原生 `picker mode="region"` 选择省、市、区县，再手填街道、楼栋及门牌；默认游标在贵州省铜仁市德江县，但未确认选择前不视为已填写。
- 订单取件/收件：使用已保存地址的下拉列表，自动带出联系人、电话及地址，可按本单需要调整联系人；没有地址时先新增，返回后刷新选项。
- 服务端同时保存 `province/city/district/detailAddress` 与完整 `address`。原有未结构化地址仍能显示、选择和设默认；编辑保存时需补选地区并填写详细地址，不猜测或丢弃旧地址。
- 修改地区/街道会清理旧坐标；地区选择不等于地理编码。订单距离仍手工预估，未接入地图选点或自动测距。

## 授权绑定手机号

- 入口：登录后的手机号授权按钮，或“我的 → 手机号绑定”；未绑定时用户卡片显示专门的绑定提示。
- 绑定页只处理手机号，无需先改头像昵称。点击原生 `getPhoneNumber`、同意微信授权后，将动态 `code` 发往 `/api/v1/user/wechat-phone`；只有服务端返回 `mobileBound: true` 才显示成功。绑定立即生效，返回“我的”会重新读取状态。
- 隐私授权、手机号授权是独立步骤，拒绝保留登录态。凭证过期需重新点击原生授权按钮，不能重放旧 code；号码冲突不覆盖或合并账号。已绑定号码仅脱敏展示，暂不支持换绑。
- 真机前提：使用微信登录的当前小程序身份，配置相同 AppID/AppSecret，并在微信后台开通手机号能力、检查可用额度及隐私指引。旧版微信或游客AppID不能作为实际绑定验收依据。

## 能力边界

已覆盖微信登录/短信备用、地址簿、送买办下单与报价、MOCK支付、订单列表/详情/评价/投诉/轨迹、骑手入驻与工作台。

真实微信授权需配套后端凭据。微信及短信登录后在**同一登录页**以新会话获取 `me`，手机号未绑定或昵称未完善时直接展示头像、昵称及手机号表单，已完整则自动返回，不再跳转独立资料页。头像通过 `chooseAvatar` 主动选择，昵称通过 `type="nickname"` 主动选择或填写，点击一次“保存并继续”即可依次上传头像、保存昵称并核验资料。微信不支持一次登录授权自动获取真实头像昵称，手机号也需单独授权；微信默认昵称不算已完善。“我的”保留独立资料编辑入口，可单独保存头像、昵称。头像上传5MB以内 JPEG/PNG；登录页与资料页复用 `utils/profile-page.js` 的授权、校验和账户隔离逻辑。头像可保留默认，不影响浏览或下单。拒绝手机号授权保留登录态、可返回浏览，但**下单必须绑定手机号并保存非空昵称**。创建页继续和确认页提交均实时查询服务端 `me`，门禁失败保留草稿；服务端业务码 `4091` 也回到完善资料。手机号只展示服务端脱敏值，不缓存；头像只缓存服务端公开路径，不缓存临时路径。昵称不是微信号或真实姓名，手机号授权 code 与登录 code 不可混用。

手机号已属于其他账号或当前账号已绑不同号码时不会覆盖或自动合并。支付、短信及骑手准入仍存在演示逻辑；由于手机号也是短信登录键，正式用户环境必须关闭演示验证码回传并接入真实短信服务，或禁用短信登录。手机号能力资格和隐私保护指引需在微信后台核实。仅有公开头像上传，没有送达照片凭证、OCR、真实支付退款、换号或账号合并，不能把静态测试通过当作真实业务上线或微信审核通过。

详细发布步骤见 [WECHAT_DEPLOYMENT.md](WECHAT_DEPLOYMENT.md)，迁移与检查记录见 [NATIVE_MIGRATION.md](NATIVE_MIGRATION.md)。
