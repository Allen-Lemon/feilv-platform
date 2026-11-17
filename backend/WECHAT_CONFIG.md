# 后台微信参数配置管理

## 页面和权限

管理后台 `/wechat-settings` 包含三个独立页签：用户端微信登录（USER）、骑手端微信登录（RIDER）、微信支付（MOCK）。既有用户端 AppID 和账号保持原位，不以复制 AppID 或密文实现第二个应用。初始化 SQL 创建 `t_wechat_config`，初始化“微信配置”菜单并授权 `SUPER_ADMIN`。

敏感配置接口不仅检查JWT，还检查数据库用户状态、管理员账号启用状态、启用角色及菜单授权。超级管理员可管理；其他管理员需启用角色关联 `wechat-settings` 菜单。普通用户/骑手无权访问。为避免普通ADMIN通过旧系统接口自行提升，账号、角色、菜单及授权维护接口仅超级管理员可调用。

## 必须先配置加密主密钥

服务器生成一次32字节随机密钥：

```bash
openssl rand -base64 32
```

将输出保存到服务器项目根 `.env`（不要放到前端或提交仓库）：

```dotenv
CONFIG_ENCRYPTION_KEY=生成的Base64主密钥
```

该变量由Spring配置读取。没有有效主密钥时可以查看脱敏参数，但不能保存，系统不会回退到明文存储。主密钥需要独立备份、持久保存；不要每次启动随机生成。丢失或改变主密钥后，原密文无法解密，需要恢复原密钥或重新录入配置。数据库备份不包含主密钥。

本轮仅离线实现与隔离验证，不启动默认/local 后端、不执行部署或真实数据库迁移。默认及 local 配置仍保留既有远程数据库 URL，**不能用于自动测试或本地启动验证**。

默认/local/docker 的显式 `schema-locations` 均追加 `db/migrations/20261117_dual_miniapp_identity.sql`，身份迁移由独立脚本创建窄用途凭证表；既有 `LOGIN` 行、密文、用户和账务不搬迁。上线前由 DBA 在隔离 MySQL 副本验证幂等性、权限和旧数据保持，再另行安排生产维护窗口。本轮不执行迁移。

## 微信登录参数

- 启用/关闭微信登录。
- 小程序AppID，与原生小程序 `project.config.json` 对应。
- 小程序AppSecret，32位十六进制；只允许录入，不回显。

两端固定映射如下，不移动历史用户身份、不重写历史密文：

| 端 | 数据库 section | AppSecret 加密 AAD | 环境变量 / Spring 属性 |
| --- | --- | --- | --- |
| USER 用户端（原应用） | `LOGIN` | 原样 `LOGIN.appSecret` | `WECHAT_APP_ID/WECHAT_APP_SECRET` / `feilv.wechat.app-id/app-secret` |
| RIDER 骑手端（新应用） | `LOGIN_RIDER` | `LOGIN_RIDER.appSecret` | `WECHAT_RIDER_APP_ID/WECHAT_RIDER_APP_SECRET` / `feilv.wechat.rider-app-id/rider-app-secret` |

每端优先级独立为 **该端数据库配置 > 该端环境变量**。数据库行一旦存在，即使显式关闭或清空，也不会自动回退环境变量。骑手凭据缺失直接不可用，绝不借用用户端、轮流试凭据或复制另一端密文。两个已配置的 AppID 必须不同，包括已关闭但保留 AppID 的配置；数据库保存和运行时取凭据都会检查，环境变量冲突也会阻止登录。

首次数据库保存时，仅 AppID 与**该端**环境配置相同且密钥留空时，才将该端环境 AppSecret 加密迁入数据库。数据库已有配置时留空保留该行密钥；更换 AppID 必须同时提供配套新密钥或显式清除（禁用状态亦然）。不能同时填写新值并勾选清除。

保存后下次换码读取最新配置，无需重启；进行中的换码使用完整快照，绑定/关联写入前会重新核对。原 `effectiveLogin()`、`saveLogin(req)` 均为 USER 兼容重载；新入口固定 `effectiveLogin("USER"|"RIDER")`、`saveLogin(clientType, req)`。未知或错误大小写客户端一律拒绝，不做拼写/空串回退。不同 AppID 的身份不会自动合并，只能通过明确确认的一次性关联流程追加身份。

## 登录后昵称与手机号授权

微信换码成功后可由用户主动完善昵称（平台展示用户名），并独立授权绑定手机号，不会从 `wx.login` 自动读取昵称或手机号。`PUT /api/v1/user/profile` 接收昵称；`POST /api/v1/user/wechat-phone` 仅接收手机号授权 code。两个接口均针对当前已登录且启用的 USER/RIDER，不接收客户端指定的用户或明文手机号。

手机号兑换按**服务端签名 token 来源**调用 `effectiveLogin(clientType)` 获取该端凭据，不能由请求头、role 或客户端自报 AppID 选择权限。access_token 按 AppID/密钥指纹隔离。微信返回 AppID 须与当前签名应用及微信关联一致；凭据轮换/禁用后不能依赖旧缓存继续绑定。查询及写入响应仅返回脱敏手机号与 `mobileBound`，不返回微信 token 或 code。

已属于当前用户的同号授权为幂等成功；已被其他账号占用、或当前用户已有其他号码均返回409，不覆盖、不换号、不合并账号或迁移微信身份。手机号授权 code 失败不等于平台 JWT 过期，不应登出用户。实际手机号能力资格、额度与隐私指引需在微信后台配置并真机验收，格式校验不能代替联网验证。

**短信边界：** `auth.demo-sms-enabled: ${AUTH_DEMO_SMS_ENABLED:false}` 默认关闭，仅显式 local/development 且不存在 prod/production/docker profile 时允许演示；即使设 true，也不能在生产/docker 开启。此功能不是生产短信服务，不能作为账号关联或历史账号资产恢复的身份实证。真实短信、真实手机号能力仍需另行接入/验收。微信登录401需核对真实 AppID、有效 code 与该端生效配置，资料接口不会绕过登录。

## JWT 密钥与上线切换

`security.jwt-secret: ${JWT_SECRET:}` 必须注入独立、足够强的随机签名密钥（至少32字节），不能使用源码默认值或与 `CONFIG_ENCRYPTION_KEY` 复用。JWT 密钥负责 token 签名；配置主密钥负责历史密文解密，二者生命周期不同。

上线时原硬编码签名密钥退场及缺少来源的旧 C 端 token 退场会要求**用户、骑手和管理员全部重新登录**。须先核实历史仅手机号骑手的受控恢复路径、未决订单/提现、双端真实 AppID/合法域名/隐私与手机号资格，再在维护窗口切换，并安排人工重登与未知结果核实。未明确授权且无可验证恢复路径的旧账号仍是发布阻塞，不能自动按手机号/unionid 合并。本轮不旋转任何线上 JWT 密钥，**不改变既有 CONFIG_ENCRYPTION_KEY，不复制 LOGIN 密文**。

## 微信支付参数

当前管理页采用微信支付API v3 **平台公钥模式**参数：

- 支付AppID、小程序关联商户号。
- 商户API证书序列号（16至64位十六进制）。
- API v3密钥（32位ASCII可见字符）。
- 商户RSA私钥（至少2048位，PKCS#8 `BEGIN PRIVATE KEY` PEM）。
- 微信支付平台公钥（至少2048位 `BEGIN PUBLIC KEY` PEM）及 `PUB_KEY_ID_...` 公钥ID。
- HTTPS支付通知URL、可选HTTPS退款通知URL。

启用表示“已按启用要求填写参数”，**不是启用真实支付**。运行模式始终为 `MOCK`，本次不实现预支付、`wx.requestPayment`、回调验签、原路退款或真实分账，不会向微信提交商户参数或发起资金操作。配置的回调地址也不会自动创建对应接口；真实接入前需另行实现并验收。

更换商户号、证书序列号、公钥ID时会校验对应密钥是否同步更换/清除，不静默复用错误密钥。

## 密钥、审计、并发与校验

- AES-256-GCM随机IV加密存储；每项密钥带不同认证上下文，避免密文跨字段替换。
- 查询只返回 `...Configured` 布尔状态，没有明文或可恢复占位符。
- 空输入保留已存值；勾选清除才删除；同时填写与清除会拒绝。
- 每端独立 CAS version；其他管理员先保存后旧版本返回409。登录保存采用 SERIALIZABLE 并固定顺序读取两端行（含缺失行的范围），防止跨端并发写入相同 AppID；数据库并发/串行化冲突同样返回409，不自动重试覆盖。生产仍须在隔离 MySQL 验证事务行为。后台收到409锁住保存与校验，必须主动重新加载核对。
- 审计只记录操作类型、分组、启用状态和版本，不记录提交内容或密钥。
- “校验已保存配置”只检查格式、完整性及解密，不联网验证AppSecret有效性、商户权限或回调可达性；不会试扣款。
- HTTPS会保护传输，但有权限的浏览器用户/插件仍能看到自己输入的密钥；在可信设备和受控账号中操作。生产建议按组织要求增加登录2FA与密钥轮换流程。

## API

```text
GET  /api/v1/admin/system/wechat
PUT  /api/v1/admin/system/wechat/login                # 原入口，固定 USER
PUT  /api/v1/admin/system/wechat/login/USER
PUT  /api/v1/admin/system/wechat/login/RIDER
PUT  /api/v1/admin/system/wechat/payment
POST /api/v1/admin/system/wechat/validate  {"section":"LOGIN","clientType":"USER" | "RIDER"}
POST /api/v1/admin/system/wechat/validate  {"section":"LOGIN"}   # 旧请求仅默认 USER
POST /api/v1/admin/system/wechat/validate  {"section":"PAYMENT"} # 不接受无关 clientType
```

所有接口需管理员认证及实时配置授权，保持 `Cache-Control: no-store`；响应是现有 Result 封装。overview 旧 `login` 始终为 USER，新增 `riderLogin`，`payment` 和 `paymentRuntime=MOCK` 不变。不要把表实体作为 API 响应，也不要记录请求 body。后台三个页签独立表单、version、错误与校验结果；严格白名单读取脱敏响应。切页、成功保存任意一端或卸载清空**全部三组**机密及清除标记，不写浏览器存储。

## 回归验证

后端 `WechatConfig*Test` 保留既有加密、授权、支付 MOCK 回归，并覆盖双端 ENV/数据库隔离、原 AAD 密文不搬迁、缺失骑手不回退、AppID 冲突、独立版本、并发同版本写入、显式禁用/清除以及新旧 HTTP 路由。集成测试必须显式 `jdbc:h2:mem:...`、SQL init=never，先确认 H2 URL；不能假定存在 h2 profile。Maven 由主协调统一在 JDK17/受限内存/单 fork 下运行，不启动默认/local 服务或连接业务数据库。

管理端 `npm --prefix admin-web run test:wechat-settings` 同时执行真实 SFC setup（mock Vue/Element Plus/API）与真实 API 包装器（离线 request spy）两组测试，检查 USER/RIDER 精确路径/clientType、响应白名单、所有密钥清理、409 重载、未知端拒绝和支付 MOCK。独立构建只写系统临时 outDir，不能覆盖现有 `admin-web/dist`。这些测试不代替两端真机微信登录/手机号授权和上线前隔离 MySQL 验证。
