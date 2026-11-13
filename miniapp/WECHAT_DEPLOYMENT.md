# 微信原生小程序部署到 xt.allms.cn

## 已固定的项目配置

- 微信开发者工具直接导入本目录 `miniapp`，`miniprogramRoot` 为 `./`，根目录直接包含 `app.json`。不再使用框架源码、native子目录或dist。
- 根目录 `config.js` 的 API 根地址为 **https://xt.allms.cn**。不要添加 `/login`、`/api` 或端口5173。
- API 模块自动拼接 `/api/v1/...`。网页 `/login` 可用并不能单独证明接口可用；匿名请求 `/api/v1/user/me` 应返回 JSON 401。
- 默认禁止服务地址覆盖，历史 `native_base_url` 不影响线上请求。首次升级会清理来源不明/不同服务的登录态与订单草稿，要求重新登录；同一官方服务的后续启动保留会话。
- 即使维护者临时开启 `ALLOW_DEV_SERVER_OVERRIDE`，也只对 `develop` 版本生效，`trial` 和 `release` 始终使用官方根地址。
- 项目启用 URL 合法域名校验及 JS/WXML/WXSS 压缩。packOptions明确排除scripts、文档、包清单、node_modules和dist；只上传原生运行文件。旧框架代码和依赖已清理。

## 仍需提供的真实 AppID

`project.config.json` 当前保留 `touristappid`。请将它替换为该小程序的真实 AppID（形如 wx 开头的18位标识），不要填写 AppSecret：

```json
"appid": "你的真实小程序AppID"
```

这是唯一小程序 AppID 配置位置；本目录不再包含框架manifest配置。游客 AppID 仅能查看界面，不能用于正式上传和真实微信换码。

## 微信公众平台设置

1. 使用对应小程序账号登录微信公众平台。
2. 开发管理 / 开发设置 / 服务器域名，添加 request 合法域名：`https://xt.allms.cn`（无路径、无端口）。
3. 确认域名备案、HTTPS证书链和平台域名规则符合微信要求。不要关闭域名或证书校验来冒充上线就绪。
4. 完善运营主体、服务类目、用户隐私保护指引、客服电话/联系方式及数据删除等说明。
5. 当前骑手上报使用 `getLocation`。按账号类目和微信要求申请定位能力并声明用途；`app.json` 中的声明不能代替微信后台授权。

## 后端匹配配置

服务器项目根 `.env` 设置：

```dotenv
WECHAT_APP_ID=与小程序项目一致的真实AppID
WECHAT_APP_SECRET=该AppID对应的AppSecret
```

AppSecret **仅放后端**，不要写到小程序代码、不要提交到仓库、不要发送到聊天中。

更新配置后，按实际部署文件重建后端容器，例如：

```bash
docker compose up -d --build --force-recreate backend
```

微信登录调用 `POST /api/v1/auth/wechat-login`，后端通过微信官方 `jscode2session` 换码并签发平台JWT。客户端不提供 openid、手机号或 session_key。未配置或不匹配凭据时不能完成真实微信登录。

## 本地校验与上传

在项目根运行（无需安装任何npm依赖）：

```bash
npm run build --prefix miniapp
npm run check:wechat --prefix miniapp
npm run check:release --prefix miniapp
```

- `build`：静态检查和 wx-mock 行为回归。
- `check:wechat`：可选，依赖本机微信开发者工具中的官方编译器；非默认目录可设置 `WECHAT_DEVTOOLS_HOME`。
- `check:release`：检查真实 AppID、官方HTTPS根地址、锁定服务、域名校验及压缩。游客 AppID 会故意使检查失败，补真实AppID后再执行。

随后在微信开发者工具：

1. 导入 `miniapp`，核对 AppID 与入口 `./`，根目录应直接找到 `app.json`。
2. 编译，确认合法域名与证书校验开启。
3. 微信登录 → 查看个人资料 → 地址簿 → 下单/订单查询 → 骑手流程，逐项真机验收。
4. 点击“预览”扫码；无误后点击“上传”，填写版本与说明。
5. 微信公众平台设置体验版、邀请体验成员；正式版需要提交审核并通过后发布。

代码上传/审核/发布不由本配置修改自动执行，均需拥有对应开发或发布权限的账号操作。

## 当前演示能力边界

- 支付仍为 **MOCK**，不发生真实扣款/退款；不要把体验版当作真实交易系统。
- 短信验证码与骑手入驻仍存在演示逻辑。
- 真实微信授权未因HTTPS配置而自动完成，必须用真实AppID/Secret实测。
- 服务/隐私页面是当前数据流说明，正式法律文本、业务资质及审核材料必须由运营方补全。
- 照片上传、OCR、真实支付和手机号绑定/微信短信账号合并等不在本次部署配置修改范围。
