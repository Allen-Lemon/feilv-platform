# 德江县"飞毛腿"城市同城跑腿平台 · 参考实现工程

基于《建设方案》《系统功能参数》《功能设计文档》《功能详细设计说明书》四份文档的**核心链路参考实现**。

技术栈：**后端 Spring Boot 3（Java 17）· 管理后台 Vue 3 + Element Plus · 微信原生小程序（WXML/WXSS/JavaScript）**

## 一、工程结构

```
feilv-platform/
├── backend/          # Spring Boot 后端（单工程按七服务域分包）
│   ├── src/main/resources/db/schema.sql   # 数据库DDL（15张表，对齐详细设计第九章）
│   └── src/main/java/com/feilv/
│       ├── common/          # 统一响应/异常/用户上下文
│       ├── config/ interceptor/ util/     # Web配置、JWT认证、工具
│       ├── enums/           # 订单状态机（转换矩阵）+ 业务枚举
│       ├── domain/          # 12个JPA实体（t_user/t_order/t_pay_record...）
│       ├── repository/      # 数据访问（抢单公平锁条件更新在 OrderRepository）
│       ├── service/         # 七服务域：User/Pricing/BanWord/Order/Dispatch/Pay/Track/ReviewRisk/Message
│       ├── controller/      # REST API（对齐详细设计第十章）
│       └── task/            # 超时引擎（15分钟关单/待接单提醒/默认完成）
├── admin-web/       # Vue3 管理后台（登录/看板/订单监控干预/骑手管理/结算/工单）
└── miniapp/         # 纯微信原生工程，根目录直接包含app.json、pages/、utils/，零框架依赖
```

## 二、快速启动

### 1）后端（需 JDK 17 + Maven，或用 IDEA 直接打开）

```bash
cd backend
# 方式A：本机有 MySQL 8 → 先建库：CREATE DATABASE feilv DEFAULT CHARSET utf8mb4;
#        修改 application.yml 的账号密码，然后：
mvn spring-boot:run

# 方式B：无 MySQL（H2 内存库演示，重启数据清空）：
mvn spring-boot:run -Dspring-boot.run.profiles=h2
```

> schema.sql 全部 `CREATE TABLE IF NOT EXISTS`，可重复执行；管理员账号 admin/123456、默认禁发品类清单自动初始化。

### 2）管理后台（需 Node 18+）

```bash
cd admin-web
npm install
npm run dev        # http://localhost:5173 ，账号 admin / 123456
```

### 3）微信原生小程序

- 微信开发者工具直接导入 `miniapp`，`project.config.json` 指向 `./`，根目录直接包含 `app.json`，无需框架构建。旧源码、依赖和编译产物文件已清理；不要再导入dist或native子目录。
- 配置真实小程序 AppID；后端登录参数可在管理后台“微信配置”页面维护，或在尚未保存数据库配置时使用 `WECHAT_APP_ID/WECHAT_APP_SECRET` 环境变量。必须与小程序一致，AppSecret只留在后端。
- 微信登录：`wx.login` 获取一次性 code → `POST /api/v1/auth/wechat-login` → 后端调用微信 `jscode2session` → 返回平台 JWT。openid、session_key 和 AppSecret 不下发客户端。
- 原生请求地址已配置为 `https://xt.allms.cn`，默认禁止历史开发地址覆盖。体验版/正式版固定使用官方HTTPS服务，微信后台需配置同名 request 合法域名。真实AppID、上传前校验与发布步骤见 `miniapp/WECHAT_DEPLOYMENT.md`。
- 默认及 local/docker profile 启动时执行 `db/migrations/20261112_wechat_login.sql`，将手机号改为可空并创建 `t_wechat_identity`；不会删除或重置现有用户。
- 微信登录不会自动得到手机号，也不会自动合并现有手机号账号；短信登录仍独立保留。未配置微信凭据时接口明确返回配置错误，不使用模拟 openid 冒充登录成功。
- 原生目录结构见 `miniapp/README.md`；校验命令 `npm run build --prefix miniapp`、`npm run check:wechat --prefix miniapp`，不需要安装第三方npm依赖。

后端启动前设置（不要将 AppSecret 写到前端）：

```bash
export WECHAT_APP_ID="你的小程序AppID"
export WECHAT_APP_SECRET="你的小程序AppSecret"
mvn -f backend/pom.xml spring-boot:run
```

IDEA 在 Run Configuration 的 Environment variables 中设置以上变量。Docker Compose 使用项目根 `.env`，模板已包含这两个变量。小程序 AppID 在开发者工具项目配置中填写。游客模式只能查看页面，不能验证真实微信登录。

测试命令：`mvn -f backend/pom.xml test`。微信登录回归测试使用 Mock 微信服务和 H2 内存库，不会修改远程业务数据；真实授权需配置凭据并在微信开发者工具/真机验证。

### 4）用户资料、手机号绑定及后台用户管理

- `wx.login` 仅用于身份登录，不会自动提供头像、昵称和手机号。资料页使用微信 `chooseAvatar`、`type="nickname"` 让用户主动选择/填写；手机号可通过既有 `POST /api/v1/user/wechat-phone` 兑换 `getPhoneNumber` 动态凭证绑定，或使用下述独立短信验证接口，禁止绕过验证直接提交号码绑定。小程序登录资料区和“我的 → 手机号绑定”均可切换到手动填写＋短信验证；不能只填写号码直接绑定。
- `GET /api/v1/user/me` 返回持久化 `avatarUrl` 和 `mobileBound`；昵称通过 `PUT /api/v1/user/profile` 保存，头像通过 `POST /api/v1/user/avatar` 的 `file` multipart 字段上传。仅支持不超过5MB的真实PNG/JPEG；后端校验像素、缩放并重新编码为PNG，不保存临时路径或原始文件名。
- 用户可以登录浏览；**下单前必须绑定手机号**，创建订单接口也会核验，未绑定返回业务码 `4091`。拒绝手机号授权不会清除现有登录态。已绑定其他账号的手机号不自动合并，请走客服处理。
- 后台新增 `/users` 用户管理，支持昵称模糊搜索、完整手机号精准查询、账户状态及绑定状态筛选、分页、资料详情、冻结/解冻。手机号仅脱敏展示，ADMIN账号不在C端管理范围；冻结会阻止已签发JWT继续调用业务接口。用户管理和订单查询分别进行实时菜单权限校验。
- 后台订单新增下单用户ID、头像、昵称及绑定手机号（脱敏），可按用户筛选；这些字段只在管理端订单接口返回，不增加小程序订单的资料暴露。
- 启动默认/local/docker配置会执行 `db/migrations/20261114_user_profile.sql`，幂等新增头像列及用户管理菜单，并只向SUPER_ADMIN授予新菜单。其他角色需要在系统管理中授权，已有菜单缓存需重新登录刷新。
- 头像保存在 `AVATAR_DIRECTORY`（默认相对启动目录的 `./data/avatars`）；两个Compose文件已配置持久卷 `avatar-data`，重建容器不会丢失文件。不要执行 `docker compose down -v` 删除头像数据。多实例部署需配置共享存储/对象存储；备份数据库时也需备份头像文件。
- 头像通过同域 `/api/v1/avatars/<UUID>.png` 公开展示，请勿上传敏感图片。微信后台除 request 合法域名外，还需配置同域 uploadFile/downloadFile 合法域名和隐私声明。项目Nginx上传上限已设为6MB；外层代理也应允许至少6MB。
- 真实头像选择、昵称填写与手机号授权仍需真实AppID、微信能力资格及真机验证。现有短信登录为演示实现，生产上线前必须接入真实短信并关闭验证码回传，或停用短信登录；否则会削弱手机号账户的认证安全。

#### 开发模式临时免验证码绑定

- 后端以 `local` profile 启动时，默认开启 `feilv.sms.binding-dev-bypass-enabled`，可用 `SMS_BINDING_DEV_BYPASS_ENABLED=false` 关闭。`development` profile 需显式配置该属性为true；普通环境默认false，同时包含 `prod`、`production` 或 `docker` 时一律拒绝，即使显式开启也不能绕过。
- 小程序切换手动绑定时读取认证接口 `GET /api/v1/user/phone/options`；仅返回 `developmentBypass:true` 时隐藏短信区域、显示开发测试警告，点击“开发模式直接绑定”提交 `POST /api/v1/user/phone/development {mobile}`。不发送短信、不填写验证码，仍校验当前账号状态、号码格式、用户行锁及唯一性，不支持换绑或账号合并。
- 仅设置 `SMS_BINDING_DEV_BYPASS_ENABLED=true` 不足以绕过profile检查；隔离开发后端还需 `SPRING_PROFILES_ACTIVE=local` 或 `development`，修改后重启。项目两个Compose文件固定启用 `docker`，仍会拒绝，不要将生产Compose切换到local来测试。小程序当前固定连接 `https://xt.allms.cn`，本机环境变量不影响该远程服务，需确认请求实际指向的开发后端。
- 选项接口新增 `reason`（`ENABLED`、`DISABLED`、`DEVELOPMENT_PROFILE_REQUIRED`、`PROTECTED_PROFILE`）及中文 `message`。小程序显示未生效原因、当前服务地址及“刷新绑定模式”；后端旧接口404或查询失败不再静默当作正常短信模式。
- 仅限使用隔离开发数据库和测试账号。直接绑定的号码归属**未验证**，不要把开发用户数据迁入生产；该功能不能因前端传入develop标志而开启。

#### 手动填写手机号 + 绑定短信验证码

两个接口均须 `Authorization: Bearer <平台JWT>`，仅允许正常状态的 USER/RIDER，始终绑定当前登录账户，不接受 `userId`、`role` 或其他额外字段。沿用统一响应 `{code,msg,data}`（业务异常通常 HTTP 200，以 `code` 判断）：

- `POST /api/v1/user/phone/sms-code`，JSON `{"mobile":"13800138000"}`。显式开启本地 mock 后成功 `data` 为 `{"retryAfterSeconds":60,"mock":true,"demoCode":"六位验证码"}`；`demoCode` 契约为可空字符串，只有 mock 才能返回明文。**当前没有真实短信网关，默认 `feilv.sms.binding-mock-enabled: ${SMS_BINDING_MOCK_ENABLED:false}` 关闭，此时返回业务码 `503`，明确提示短信未配置、可使用微信授权，不生成验证码、不发送短信、不返回成功 data。**
- `POST /api/v1/user/phone`，JSON `{"mobile":"13800138000","code":"六位验证码"}`。成功返回完整 `ProfileVO`：`userId`、`nickname`、`role`、脱敏 `mobile`（如 `138****8000`）、`mobileBound`、`avatarUrl`，立即持久化生效，可继续下单。手机号须为11位大陆手机号（1开头、第二位3至9），验证码须为6位数字。
- 绑定凭证与旧 `/api/v1/auth/sms-code` 登录凭证完全独立，绑定 userId + mobile + PHONE_BINDING 用途；有效期5分钟，用户和手机号两个维度各60秒冷却、每日最多10次（北京时间自然日），连续错误5次作废，验证成功一次消费。错误/过期返回 `4011`/`4010`，不是平台登录失效 `401`，前端勿因此清除平台会话。
- 已绑定不同号码、号码属于其他账户均返回业务 `409`；同号也须重新验证后才幂等成功，不自动合并账户或换号。数据库行锁与既有微信绑定共用，唯一索引竞争回滚后返回 `409`。
- 本地联调显式设置 `SMS_BINDING_MOCK_ENABLED=true` 后启动后端（例如 `SMS_BINDING_MOCK_ENABLED=true mvn -f backend/pom.xml spring-boot:run`）。此模式只调用现有 `MessageService` 日志模拟，并使用脱敏号码、不将验证码写入日志；验证码仅在 mock 响应 `demoCode` 中供本地填写。**生产必须保持关闭；开启 mock 不等于发送真实短信，接入真实网关前手动短信绑号不可用，请使用微信授权。**
- 两个绑号接口设置 `Cache-Control: no-store`。控制台与文件日志保留数据库异常及堆栈，仅将消息和堆栈中的11位大陆手机号替换为 `[PHONE_REDACTED]`，避免唯一约束冲突泄漏完整号码；不关闭 Hibernate 错误日志类别。
- 凭证与限流当前为单实例内存状态，重启失效；mock 仅用于本地联调。未来接真实短信及多实例部署时须迁移到共享存储、原子限流/消费并取消明文验证码回传。既有短信登录仍是历史 demo，本次不改变其行为，也不能把其登录验证码用于绑定。

### 5）后台微信登录/支付参数管理

后台新增 `/wechat-settings`（微信配置），分别管理登录AppID/AppSecret及微信支付API v3商户、公钥、通知URL等参数。机密以AES-256-GCM加密，查询不回显；留空保留、勾选清除、乐观版本冲突与操作审计已接入。

首次部署须生成一次并持久保存 `CONFIG_ENCRYPTION_KEY`（`openssl rand -base64 32`），写入服务器 `.env`，重建后端后才能保存机密。不要将主密钥或商户私钥放到前端/小程序。数据库配置优先于环境变量，登录参数保存后即时读取；**支付运行模式仍为MOCK，保存参数不会接通真实收单、退款或回调**。

默认/local/docker启动新增表 `t_wechat_config` 及“微信配置”菜单，默认授权SUPER_ADMIN；其他管理员需明确菜单授权。账号角色授权接口仅超级管理员可操作。详见 `backend/WECHAT_CONFIG.md`。

### 6）地址簿接口兼容

`GET/POST /api/v1/user/address`、`PUT/DELETE /api/v1/user/address/{id}` 保持原路径和联系人、电话、`poiName`、经纬度、默认地址契约；保存请求新增可选字符串 `province`、`city`、`district`、`detailAddress`，查询也返回这四项。四项全部为空（含空白）时按旧客户端处理，仍须提供非空完整 `address`（最多255字符），不拆分旧地址；任一项非空则四项必须齐全，去除首尾空白后省/市/区各最多64字符、街道门牌等详细地址最多160字符，服务端以 `province + city + district + detailAddress` 组合完整地址（最多255字符），忽略请求中冲突的 `address`，不强制匹配行政区数据库。旧客户端更新会清空原结构化字段，避免与更新后的完整地址不一致。地址仅允许本人修改/删除，最多50条，设置默认会取消本用户其他默认地址。默认/local/docker启动会在 schema 之后执行 MySQL 幂等迁移 `db/migrations/20261115_address_region.sql`，只追加可空列，不改写历史完整地址；升级后旧地址可继续选用于下单。

### 7）自定义任务与投诉审核扣款

- 管理后台新增“自定义任务”(`/tasks`)和“投诉审核”(`/complaints`)菜单，仅默认授权SUPER_ADMIN；其他角色需明确授权。任务、标签、投诉审核、结算接口均检查数据库中的实时菜单权限。升级后重新登录刷新菜单。
- 小程序首页新增独立“自定义任务”。用户填写标题（1–64字）、内容（1–1000字），可选最多5个当前启用标签，沿用地址选择、距离/重量计价及两步确认发布。后台可指定已绑定手机号的正常USER/RIDER代发布，创建后待支付，由发布者在小程序完成MOCK支付；后台不会代付或冒充真实支付成功。
- 标签支持新增、改名、排序和启停，更新必须带version，冲突返回409。历史任务保存发布时标签名称快照，不因停用/改名改变。用户发布接口 `/api/v1/custom-tasks`、报价 `/custom-tasks/quote`、标签 `/task-tags`；后台对应 `/api/v1/admin/custom-tasks`、`/admin/task-tags`。普通 `/orders` 不允许绕过任务元数据直接创建CUSTOM订单。
- 自定义任务复用原订单履约，任务和订单、费用明细在同一事务创建。列表/详情可查看标题、内容、标签；只有订单发布者、承运骑手或有权限管理员可查询对应资料。
- 已接单起（ACCEPTED/PICKING/DELIVERING/DELIVERED/FINISHED）订单发布者可提交投诉，描述必填1–255字；每单最多一张OPEN/PROCESSING活动工单。`GET /api/v1/orders/{id}/complaints` 查询服务端处理记录，发布者/承运骑手可看，其他用户不可看。APPROVED/REJECTED/DONE为终态，可针对新问题再次投诉，但累计金额仍受本单额度限制。
- 投诉审核接口 `/api/v1/admin/complaints/{id}/review` 接收 `{decision,reason,amountFen}`，金额单位分且必须为JSON非负整数。受理/驳回必须0元，成立可0元；正额成立要求订单已送达/完成、存在承运骑手与成功MOCK支付，**从骑手本单分账扣除并等额赔付发布者，累计不超过本单骑手原分账**。订单级行锁串行化并发审核，终态不可重复审核。
- `t_complaint_deduction` 另记扣款/赔付账本，不改写原支付流水、订单金额或原分账。审核状态、扣款、操作记录、审计同事务，任何失败全部回滚。旧投诉handle仅支持0元受理/办结，不能绕过新审核直接赔付。
- 结算中心展示原分账、投诉扣款、净分账及发布者赔付。**这些均为MOCK模拟账务，未真实扣银行卡/微信零钱，未退款/转账，不能当作真实赔付完成。** 真实资金渠道、已结算后的追偿/退款及支付对账仍需另行接入。
- 默认/local/docker启动追加 `db/migrations/20261115_custom_tasks.sql`，幂等新增任务标签、任务、标签快照、投诉扣款账本和菜单，并增加投诉订单状态索引，不重写历史订单或支付。请先备份、升级后端/数据库，再更新后台和小程序；MySQL迁移需在部署环境验证，本地测试使用隔离H2，不执行远程迁移。

### 8）用户钱包与模拟提现

- 后台新增 `/wallets` 用户钱包、`/withdrawals` 提现审核；仅默认授权SUPER_ADMIN，其他管理员需明确菜单授权。用户管理可查看对应钱包与流水，不能通过HTTP接口任意充值或改余额。
- 普通用户和骑手共用 `t_user.id` 钱包，金额统一整数分（BIGINT）：可用、提现冻结、欠款各自非负。任务从已送达转为已完成时，以原成功MOCK支付的骑手分账（默认70%）减本单既有投诉扣款入骑手的用户钱包；发布者原支付不退回其本人。用户确认、后台强制完成、超时默认完成共用结算入口，一单一结算凭据与独立不可变流水防重。
- 无承运骑手或成功MOCK支付的旧演示订单可完成但跳过入账并记录订单ID原因；账务矛盾（如累计扣款超过分账）会回滚完成操作。**历史已完成订单、已审批投诉不自动补发**，不能通过重复确认旧订单获得余额，若需历史迁移应另外核对。
- 新投诉成立等额赔付发布者钱包。历史FINISHED订单若没有钱包结算凭据，会拒绝钱包扣赔并要求人工核对，不凭空补发赔付；不会扫描旧投诉。如果骑手已经完成入账，再扣可用余额，不足转为待抵扣欠款，不动已有提现冻结；未入账则完成时仅入扣款后的净额，避免重复扣。后续任务收入或投诉赔付先清欠款。投诉审核、扣款凭据、双方钱包、流水同事务；失败全部回滚。
- 小程序“我的 → 我的钱包”查看余额、明细、提现记录并申请提现；仅正常USER/RIDER、已绑定手机号、无欠款且可用余额充足才可申请。金额0.01至1000000元、最多两位小数；后端只接受正整数分。提现申请使用用户+requestId唯一幂等键，重复请求不重复冻结，同键改金额返回409。
- 后台批准提现将冻结额转为累计模拟提现出账；驳回先解冻，解冻金额先抵欠款再回可用。既有提现可审核，但任何终态不可再次审核。申请、审批、解冻与流水均同事务，行锁防余额并发透支。不可用余额不能提现，有欠款不能新申请。
- **本轮钱包收入和提现仅MOCK账务，不代表有真实充值资金，不发送微信/银行卡转账，不扣真实余额，不声称真实提现到账。** 当前MOCK余额绝不可接真实出款；接入真实支付/提现前必须隔离资金来源、实名/合规审核、商户能力、回调验签、幂等及对账。
- API：`GET /api/v1/wallet`、`GET /wallet/entries`、`GET/POST /wallet/withdrawals`；后台 `GET /api/v1/admin/wallets`、`GET /wallets/{userId}/entries`、`GET /withdrawals`、`POST /withdrawals/{id}/review`。查询均15条分页、0-based，钱包响应no-store，后台分别校验wallets/withdrawals实时权限。
- 新增幂等迁移 `db/migrations/20261115_wallet.sql`（5张表+2菜单及超级管理员授权），默认/local/docker启动接入，不扫描历史数据。先备份并升级后端/数据库，再更新后台和小程序；没有自动执行远程钱包迁移。

## 三、Docker 部署

本项目 Docker 部署只启动后端和管理后台容器，数据库使用外部 MySQL，不会启动数据库容器。

### 1）准备环境变量

```bash
cp .env.example .env
# 编辑 .env，至少填写 DB_HOST、DB_NAME、DB_USERNAME、DB_PASSWORD
```

不要把 `.env` 提交到版本库或镜像中。

### 2）构建并启动

```bash
docker compose up -d --build
```

服务地址：

- 管理后台：`http://localhost:5173`
- 后端 API：`http://localhost:8080`

后端容器使用 `docker` profile，启动时会执行 `classpath:db/schema.sql`。数据库表使用幂等初始化语句；已有数据库字段变更请先执行对应迁移 SQL。

### 3）查看状态和日志

```bash
docker compose ps
docker compose logs -f backend
docker compose logs -f admin-web
```

### 健康检查与请求方法排查

后端提供无需登录、只读的 `GET /health`，检查数据库连接可用性：就绪返回 HTTP 200，未就绪返回 HTTP 503。不调用验证码、登录等业务接口。

```bash
# 默认 compose 发布后端端口时：
curl -i http://127.0.0.1:8080/health
# 容器内检查（两个 compose 均可用）：
docker compose exec backend curl -fsS --max-time 4 http://127.0.0.1:8080/health
```

`Request method 'GET' is not supported` 表示运行中的服务收到了错误方法的请求，不是 JVM 启动失败。验证码和登录只接受 POST；请勿用浏览器地址栏或 GET 健康探针访问。旧 compose 的验证码探针已替换，方法错误现在返回 HTTP 405 和 Allow 响应头，不再误记为 500 系统异常。

升级时必须同时更新应用镜像和 compose 配置，不能只重启旧容器：

```bash
docker compose up -d --build backend admin-web
docker compose ps
docker compose logs --tail=100 backend
```

使用 `docker-compose.prod.yml` 的镜像部署需先构建/发布包含 `/health`、curl 和 HEALTHCHECK 的新镜像，再拉取并重建容器；旧镜像不满足新的 `service_healthy` 启动条件。健康状态正常不等于支付、微信授权等第三方能力已经接入。

本地回归：`mvn -f backend/pom.xml package`，随后运行 `node backend/scripts/smoke-health.cjs`。冒烟脚本使用 H2 内存库和随机本机端口，检查健康接口、405 响应及鉴权，完成后关闭验证进程，不修改远程业务数据库。

### 4）停止服务

```bash
docker compose down
```

生产环境建议使用预构建镜像和 `docker-compose.prod.yml`，并通过 HTTPS 反向代理、密钥管理服务和非 root 数据库账号提供配置。

## 四、演示链路（一屏看懂核心闭环）

1. **用户端**（小程序）：手机号验证码登录 → 首页选"跑腿送/买/办" → 填单 → 计算费用 → 模拟支付 → 订单"待接单"；
2. **骑手**（小程序"我的"页入驻骑手 → 进入工作台）：开启抢单 → 大厅抢单（公平锁，仅一人抢成）→ 取件核销 → 出发配送 → 模拟轨迹上报 → 确认送达；
3. **用户端**：确认收货（或15分钟默认完成）→ 五星评价（联动骑手信用分+1）；
4. **管理后台**：看板看统计 → 订单监控/干预取消 → 骑手信用分变化 → 结算中心看骑手分账（70/30）→ 投诉工单。

下单输入物品含"香烟/处方药"等禁发词会被实时拦截（合规底线功能）。

## 四、与设计文档的对应关系（追溯）

| 实现要点 | 文档出处 |
|---|---|
| 订单状态机（8状态转换矩阵、唯一流转入口 changeStatus） | 详细设计 3.2 表3-1 |
| 抢单公平锁（DB条件更新影响行数判断，仅一人抢成） | 详细设计 5.2.2 |
| 计价引擎（起步5元含2km＋1.5元/km＋重量档＋夜间120%，yml全配置化） | 详细设计 3.3 表3-2 |
| 禁发品类拦截（词库表+下单强校验） | 详细设计 4.2.8 |
| 支付分账（MOCK渠道模拟、骑手70/平台30、平台不经手资金沉淀） | 功能设计 3.4 |
| 信用分事件驱动（5星+1 / 1-2星-2 / 等级联动预警） | 详细设计 表5-1 |
| 超时引擎（待支付15分钟关单、待接单10分钟提醒、送达15分钟默认完成） | 详细设计 表3-1 |
| 手机号脱敏、地址簿≥50、验证码60秒冷却 | 功能参数 表3-x |
| 后台九组菜单、经营看板指标 | 功能设计 表7-1 |
| 15张数据表结构 | 详细设计 表9-1 |

## 五、生产化待办（对照验收要求）

- [ ] 抢单锁升级：Redis SETNX + DB 条件更新双重保险（当前 DB 条件更新单机已可靠）
- [ ] 真实支付：对接微信/支付宝持牌渠道（回调验签、幂等、T+1对账文件核对），分账走持牌机构
- [ ] 地图：接入高德（主）/腾讯（备）SDK，替换手填地址与距离（选点、路径规划、轨迹渲染、ETA）
- [ ] 短信/推送：接真实网关与订阅消息（当前日志模拟）；虚拟号隐私通话
- [ ] 骑手端独立工程拆分 + 培训考试/OCR活体认证/保险对接
- [ ] 鉴权升级：JWT密钥入配置中心、RBAC细化、管理员双因素；操作审计落 t_op_log
- [ ] 等保二级加固：HTTPS全站、敏感字段AES加密存储、日志≥6个月、渗透测试
- [ ] 商家端完整功能（入驻审核、店铺配置、出货打印、对账报表）

## 六、版本说明

- 本工程为**参考实现**：覆盖下单→支付→抢单→履约→评价→结算核心闭环与合规底线功能，用于验证设计可行性并作为开发基线；
- 一期SaaS路径下，本工程对应接口与规则可直接作为选型比对与验收测试依据。

> AI生成
