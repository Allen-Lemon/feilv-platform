# 用户端 / 骑手端双微信小程序

本文描述双工程接入协议、保留的数据和上线前边界。开发交付不等于已发布；真实微信、MySQL迁移及历史账号恢复需要另行验证和授权。

## 1. 工程与功能归属

| 工程 | 微信项目 | 功能 |
| --- | --- | --- |
| `miniapp/` | 用户AppID，沿用原项目 | 首页、飞毛腿/代买/接送/代驾/自定义任务发布、地址、消费者订单与轨迹、我的、资料、钱包、设置、协议 |
| `rider-miniapp/` | 不同的骑手AppID | 工作台与我的两个tab、受保护入驻、接单/履约/转派/信用/主动位置上报、承接订单只读详情、资料、钱包、设置、协议 |
| `miniapp-shared/` | 不可作为小程序导入 | 稳定工具、资料/钱包的共享源，按显式清单复制到两个上传根 |

两个原生项目分别直接导入微信开发者工具，各自以本目录为上传根，不使用跨根require、软链接或框架dist。用户端保留“首页 / 订单 / 我的”三个tab，骑手端使用“工作台 / 我的”两个tab。原用户工程不再包含骑手工作台/注册/转派入口和骑手定位声明。骑手详情不提供消费者支付、取消、确认、评价、投诉申请或轨迹按钮。

骑手AppID默认 `UNCONFIGURED_RIDER_APPID`，是故意阻止发布的占位，不可用用户AppID替代。不要复制个人 `project.private.config.json`，也不要将后台AppSecret放进任何前端工程。

原有用户 `draft.js` 及创建/确认链保持原路径和存储键；保留飞毛腿/代买分槽、接送代驾、旧ERRAND/DELIVERY兼容，以及旧地址 `poiName` 展示/保存兼容。地址表单不会恢复“地点名称”输入。

## 2. 双AppID与密钥

后台“微信配置”独立管理用户登录、骑手登录、支付：

| 项目 | 用户登录 | 骑手登录 |
| --- | --- | --- |
| 数据库section | `LOGIN`（保留） | `LOGIN_RIDER`（新增） |
| 环境AppID | `WECHAT_APP_ID` | `WECHAT_RIDER_APP_ID` |
| 环境Secret | `WECHAT_APP_SECRET` | `WECHAT_RIDER_APP_SECRET` |
| 加密AAD | `LOGIN.appSecret`（不变） | `LOGIN_RIDER.appSecret` |

两端独立数据库优先、启停、版本和密钥保留/清除语义。骑手配置缺失或关闭时明确失败，不尝试用户凭据。两个有效AppID必须不同；登录兑换和手机号授权使用同一选定端的不可变配置快照，并在写入前复核。

后台overview保留 `login`（用户端），新增 `riderLogin`。旧 `PUT /api/v1/admin/system/wechat/login` 继续用户端别名；新路径为 `/login/{USER|RIDER}`。校验登录参数使用 `{section:"LOGIN",clientType:"USER"|"RIDER"}`；省略clientType仅兼容旧用户端。未知端拒绝。PAYMENT不接受无关clientType。校验表示格式、完整性、解密等本地检查，**不表示调用微信在线验证成功**。

- `CONFIG_ENCRYPTION_KEY` 是既有配置加密主密钥；不要为本次拆分重新生成或替换，否则旧密文无法解密。
- `JWT_SECRET` 对应 `security.jwt-secret`，至少32个UTF-8字节，没有源码默认值。开发使用独立密钥，缺失/弱密钥启动失败是预期的fail-closed行为。
- `.env.example` 是模板；真实 `.env`、Secret、主密钥和登录票据都不可提交、截图或写日志。
- 支付参数和钱包继续 **MOCK**，配置真实商户参数不会自动开通真实支付、赔付或提现。

## 3. 一个平台账号，两个独立微信身份

唯一微信身份仍为 `(app_id,openid)`。关联是给原 `t_user.id` 追加另一端身份，不复制订单/钱包，不移动已存在身份。手机号和unionid都不能作为自动合并依据。

### 3.1 登录

`POST /api/v1/auth/miniapps/{USER|RIDER}/login`

```json
{"code":"本端新wx.login凭证","agreed":true}
```

已映射返回统一响应data：

```json
{"state":"AUTHENTICATED","login":{"token":"...","userId":123,"role":"USER","nickname":"..."}}
```

未映射返回：

```json
{"state":"IDENTITY_REQUIRED","loginTicket":"...","expiresAt":"..."}
```

此时还没有创建User，也没有普通访问token。用户明确选择关联已有账号或创建新账号。短效ticket和关联码只在当前流程短暂保留，不进入URL、日志或永久存储。

`GET /api/v1/auth/miniapps/{USER|RIDER}/options` 返回 `{smsLoginEnabled:false}` 等能力。演示短信默认关闭，生产/docker强制关闭，不应把demo码用于正式账号认证。

### 3.2 双向关联

1. 在**已经绑定原平台账号的那一端**登录，“我的”点击生成关联码并明确确认。
2. 原端调用 `POST /api/v1/user/miniapp-link-codes`，提交新的 `{code,agreed:true}`。服务端根据签名来源确定源端，兑换新的微信code并检查该身份属于当前userId。仅有旧bearer不够。
3. 返回 `{linkCode,expiresAt,targetClient}`，目标固定为另一端，不接受任意userId/target选择。
4. 新端取得自己的loginTicket，提交 `POST /api/v1/auth/miniapps/{USER|RIDER}/identity-link`：

```json
{"loginTicket":"...","linkCode":"...","agreed":true}
```

成功直接返回原平台主体的LoginVO。**USER→RIDER、RIDER→USER均适用**。不得互传bearer、手机号、订单草稿或提现pending。

服务端事务校验用途、有效期、客户端、配置指纹、消费状态和既有归属，按ticket→关联码→用户→必要骑手档案的顺序锁定。拒绝ADMIN、冻结主体或冻结骑手绕过；身份新增、两份凭证消费及审计同事务成功。并发/已占用时失败而不是搬移身份。关联不修改电话、角色、余额或能力。

### 3.3 创建新账号

`POST /api/v1/auth/miniapps/{USER|RIDER}/accounts`

```json
{"loginTicket":"...","phoneCode":"本端原生getPhoneNumber动态code","agreed":true}
```

正式环境使用当前端真实微信手机号授权核验。号码未占用才原子创建USER、身份并消费ticket；号码已占用返回409，提示从原账号关联，不能按同手机号自动继承钱包。手工号码、演示短信或开发免验证都不是正式的号码归属证明。仅隔离开发后端的骑手端可使用下述严格门禁的手填建号分支。

关联/创建成功但客户端丢回包时，重新wx.login会找到已映射的同一userId，不要另建第二个账户。

### 3.4 骑手隔离开发手填模式

复用现有 `SMS_BINDING_DEV_BYPASS_ENABLED` 门禁：开关开启且 active profile 包含 `local` 或 `development` 才允许；任何 `docker`、`prod`、`production` 优先禁止，单独 `dev` 不放行。不要用切换线上 profile 的方式开启；`local` 配置本身也不证明数据库隔离。

登录 options 增加严格布尔 `developmentManualPhoneEnabled`，仅 RIDER 在有效开发门禁下为 true。骑手新账号请求可将 `phoneCode` 替换为 `mobile`，两者必须且只能出现一个：

```json
{"loginTicket":"...","mobile":"13800138000","agreed":true}
```

仍须真实 `wx.login` 取得本端有效 ticket、明确创建新账号、号码未占用；新 USER、身份追加和 ticket 消费同事务提交，仍签 RIDER 来源而非直接赋予骑手角色。开发号码不触发微信手机号/短信调用，不会按号码查出旧账号后登录。占用号码仍409，关联码协议不变。

入驻页面通过已有 `/api/v1/user/phone/options` 的严格布尔 `developmentBypass` 识别同一门禁，开发请求为 `{name,plateNo,mobile,agreed:true}`，与原 `phoneCode` 形态互斥。必须是当前已认证 RIDER 来源主体、本端真实微信身份且输入等于该主体已绑定号码；需绑定或改号时先走原资料页显式绑定/换绑。冻结、缺档、当前主体、事务锁顺序和能力默认值不因开发模式放宽。

前端在有效能力下使用普通输入框/按钮，不依赖微信手机号能力和验证码；能力未知默认关闭，原生验证失败也不自动降级。真实微信身份仍必需。`mobileBound` 只是号码非空，不表示真实验证；开发数据不得迁入生产。现有 SMS 为 MOCK，本次未提供生产短信网关。

小程序当前默认远程 HTTPS 后端且地址覆盖关闭；微信开发者工具“开发版”不等于后端开发模式，本机开关不会影响远程服务器。本次不修改真实环境、服务地址、Compose profile 或密钥。操作示例见 `rider-miniapp/README.md`。

### 3.5 历史接口

- `/api/v1/auth/wechat-login` 只保留用户端旧请求/直接LoginVO响应兼容，新版本使用ticket协议。
- 旧公开 `/api/v1/auth/rider-register` 已退休，不再根据手机号创建/切换骑手主体；旧客户端需升级。
- 旧演示短信只能在明确允许的隔离开发环境启用，不能恢复历史资金账号。

## 4. 骑手入驻与应用会话边界

骑手端登录后调用受保护 `POST /api/v1/rider/enrollment`：

```json
{"name":"姓名","plateNo":"车牌","phoneCode":"新的真实微信手机号code","agreed":true}
```

主体仅为当前登录userId，不接受mobile/userId选账号。正式环境新授权手机号须与当前主体号码一致，历史 `mobileBound` 不足以证明真实归属。隔离开发环境允许手填当前已绑定号码，但不能据此切换主体或静默换号。新入驻保持即时ACTIVE语义，接送/代驾能力默认关闭，由后台分别授权；不新增虚假的资质审核结论。

已ACTIVE入驻重试返回当前档案/新角色LoginVO，不重建、不重置能力、不再次消费手机号凭据；FROZEN不重新激活。角色变化后另一端旧角色会话需重登。丢失入驻响应可重新wx.login/读取骑手档案恢复。

| 签名来源 / 当前角色 | 可用能力 |
| --- | --- |
| USER / USER或RIDER | 消费者发布、地址、支付/取消/确认等 |
| RIDER / USER | 入驻和允许的共享账户能力，不能接单 |
| RIDER / RIDER | 满足实时ACTIVE及独立能力检查后的大厅、接单、履约、转派、信用、位置上报 |
| 两端C用户 | 当前主体的资料、头像、手机号、钱包、一次性关联码 |
| ADMIN | 管理后台及管理资料，不是C端业务token |

JWT中的来源/appId由服务端签名，不信任客户端请求头或role。不存在null来源HTTP兼容后门。`GET /api/v1/rider/orders/{id}` 与 `/rider/orders/{id}/custom-task` 只允许当前承接者读取；不借用消费者通用详情绕过来源边界。旧转派参与者仍只能读取必要转派历史，不能继续看订单私密详情。

鉴权覆盖MVC处理器路径，在公开入口和OPTIONS放行前拒绝非规范原始路径（分号参数、百分号编码、反斜杠、双斜杠及独立`.`/`..`段），返回HTTP400/code400/no-store，而不是分别按不同规则解码。配置Servlet contextPath时精确去除前缀后再执行同一权限矩阵；客户端和代理应发送规范接口路径，查询参数中的正常URL编码不受此路径规则影响。

### 会话过期与幂等保护

HTTP401/明确会话需重登业务码40101会过期凭据；普通403（冻结/权限/业务限制）不一概清登录。

- 凭据过期清token、公开身份展示和页面私有缓存，推进revision，使旧请求回调失效。
- 草稿、待核实订单、提现原requestId按原owner及后端地址隔离保存。匿名状态不可展示隔离的地址/金额。
- 同一userId、同一后端重登恢复原待核实业务；不同账号登录前清除旧账号隔离数据，不沿用他人的幂等编号。
- 显式退出和服务地址切换仍全清；旧401不能使后来登录的新会话失效。
- 客户端结果未知时保留原order/pending或withdrawal requestId，先核实/重试原请求，不重新创建。

## 5. 本地验证（不启动业务后端）

```bash
# 两端分别跑原生完整检查/测试
npm run build --prefix miniapp
npm run build --prefix rider-miniapp

# 聚合执行双根原生检查、行为测试与共享副本一致性检查；--check不写文件
node scripts/check-miniapps.cjs --check

# 也可仅检查共享副本
node scripts/sync-miniapp-shared.cjs --check

# 有可用微信官方编译器时逐端独立编译到临时目录，不上传
npm run check:wechat --prefix miniapp
npm run check:wechat --prefix rider-miniapp
```

后台可运行 `node --test admin-web/scripts/*.test.cjs`；生产构建请指定独立临时outDir，不覆盖已有 `admin-web/dist`。双端发布检查必须拒绝未配置/重复AppID和个人配置关闭域名校验等情况，预期阻塞不能当作测试通过。

后端仅在核实所有Spring集成测试显式H2、SQL init=never之后运行，不启动default/local后端，不依赖不存在的h2 profile。Windows Git Bash可用以下低内存命令：

```bash
JAVA_HOME='C:\Users\yt\.jdks\ms-17.0.18' \
PATH="/c/Users/yt/.jdks/ms-17.0.18/bin:$PATH" \
MAVEN_OPTS='-Xms32m -Xmx160m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC' \
mvn -f 'D:\project\feilv-platform\backend\pom.xml' \
  '-DargLine=-Xms32m -Xmx192m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Dfile.encoding=UTF-8' \
  -DreuseForks=false -DforkCount=1 -Dspring.test.context.cache.maxSize=1 test
```

上述JDK路径为当前开发机示例，可替换为自己的JDK17。本机首次运行曾出现Windows1455分页文件/内存提交不足，以及Mockito动态附加代理失败；减小JVM和使用SerialGC后可正常执行。若测试环境仍不能动态附加，可在同一个`argLine`末尾追加 `-javaagent:C:\Users\yt\.m2\repository\net\bytebuddy\byte-buddy-agent\1.14.13\byte-buddy-agent-1.14.13.jar`，路径应指向本机与Mockito依赖匹配的ByteBuddy测试代理；这只影响测试进程，不修改生产启动参数。

测试JWT密钥必须显式且独立，不读取线上密钥。微信登录、手机号兑换使用替身，不调用真实微信。H2通过不证明MySQL锁、范围读和幂等DDL已验证。

### 已执行的本地验证（2026-11-17）

| 项目 | 实际结果 |
| --- | --- |
| 后端最终全量回归 | 525/525通过，0失败、0错误、0跳过；JDK17、小内存单fork及显式ByteBuddy测试代理，BUILD SUCCESS |
| 后端路径/缓存/钱包/健康定向回归 | 38/38通过；真实MVC修复前6项中5项失败（实际绕过返回200），修复后路径用例全部通过 |
| 管理后台Node测试 | 129/129通过，含21项微信配置SFC/API包装器测试 |
| 管理后台生产构建 | 通过，1699模块；输出到独立临时目录，未覆盖已有dist |
| 用户端行为测试 | 239/239通过 |
| 骑手端行为测试 | 164/164通过 |
| 两端独立用例 | 公共126 + 用户113 + 骑手38 = 277项，合计403次执行；原241项测试名称全部保留 |
| 原生结构与聚合检查 | 通过；用户13页/14 WXML，骑手10页/11 WXML；共享24文件在两端完全一致 |
| 聚合`--check`只读性 | 177文件执行前后的SHA-256、大小和修改时间全部相同 |
| 官方微信编译 | 两端实际退出码1：本机缺少官方wcc/wcsc，未伪造产物，不算编译通过 |
| 发布检查 | 两端实际退出码1：骑手AppID占位；用户端还存在既有private配置urlCheck=false，未擅改 |

表中后端525项是权限复核修正后的最终完整运行结果，取代此前存在失败或主动停止的运行；不能把仅编译成功算作测试通过。Windows内存/代理故障已通过小内存测试JVM及显式测试代理解决，未放宽生产授权。真实JAR健康冒烟未在本轮执行，仅对冒烟脚本做语法检查。上述测试不接触真实微信或业务数据库，也不等同于官方编译、真机或发布完成。

## 6. 历史账号盘点与受控恢复

只读脚本：`backend/scripts/dual-miniapp-account-inventory.sql`。**本次开发不会执行该脚本。** 仅由获授权人员在隔离副本/已批准只读连接运行；先检查表存在、执行计划、快照时长和结果访问权限。

脚本只SELECT并使用只读事务，报告：AppID映射分布、无微信身份骑手、冻结/角色异常、关联的原钱包及未决业务数量、孤立映射和同AppID多映射。它不输出手机号、openid或票据，也不自动推断哪个不同userId属于同一个人。

旧公开手机号演示注册可能产生 **USER A（微信身份）/RIDER B（手机号但无微信身份）**。B不能凭A的微信、同号码、unionid、旧演示短信或旧bearer直接恢复。必须保留A/B双方历史、订单、能力和钱包，另行完成真实人工身份核验、归属证明、明确授权、受控操作和审计。本轮不提供公开任意userId恢复接口、不自动合并、不转移身份或余额。

没有可靠恢复路径的老骑手，以及角色为RIDER却缺少Rider档案的历史异常主体，仍是上线阻塞；不能凭重新入驻修复归属或宣称“所有老账号无缝迁移”。盘点结果不包含客户端丢响应状态，仍须核对用户待核实订单/提现与服务端幂等记录。

## 7. 上线前顺序与阻塞

以下是另行授权后的操作清单，**不是本轮已执行结果**：

1. 在隔离MySQL副本验证完整schema/显式迁移清单及新增 `db/migrations/20261117_dual_miniapp_identity.sql`；重复执行不改用户/身份归属、余额、骑手能力或旧LOGIN密文。验证真实MySQL事务、唯一约束、跨配置并发/关联重放冲突及失败回滚。
2. 备份数据库、头像目录、配置加密主密钥，验证恢复。完成只读历史账号盘点和授权恢复；核对待支付/待确认/结果未知订单、提现、转派及幂等键。不要清客户端存储“解决问题”。
3. 准备两个不同真实AppID及各自Secret，后台逐端配置和版本核对。完成合法域名、隐私声明、手机号能力及接送/代驾运营/驾驶资质、安全、保险等合规。不能借用演示配置绕过。
4. 两个真实小程序分别验证登录、双向关联、新账户同号409、拒绝授权、配置轮换、入驻重试/丢回包、冻结、来源越权、能力撤销、转派和钱包pending恢复；不要用游客模式代替真机授权。
5. 规划维护窗口、全部账户（包括管理员）重新登录公告及管理员恢复方案。新JWT密钥/旧无来源C token退场是受控切换，不在开发时旋转。原 `CONFIG_ENCRYPTION_KEY` 保持不变。
6. 后端/数据库就绪后配套更新后台和两个小程序，保留旧用户AppID；旧骑手入口升级提示不能替代老账号恢复。先通过各端发布检查，再由授权人员上传/审核/发布。
7. 失败回滚要兼顾已追加身份、已消费短效凭证、账务幂等和token版本，不能通过删除身份/合并用户/还原旧弱认证来回滚。先停止新增流量、核对状态，再执行预先演练的方案。

已知需人工处理的条件：骑手AppID仍是占位时不能发布；用户端个人配置若 `urlCheck:false` 则不能用于上线；真实微信和隔离MySQL未验证；历史phone-only骑手未核实恢复；JWT维护窗口及管理员恢复未安排。支付、提现、赔付仍MOCK，不能接真实出款。

本轮不连接真实数据库、不做线上迁移或账号恢复、不轮换线上密钥、不上传发布、不提交或推送代码，也不清理仓库已有dist/target/IDE改动。
