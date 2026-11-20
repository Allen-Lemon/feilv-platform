# 微信原生小程序部署到 xt.allms.cn

## 双原生根拆分更新（2026-11-17）

本目录只发布 USER 十三页，保留原AppID与首页/订单/我的三tabs，无设备定位申请；完整骑手工作流迁入独立 `rider-miniapp` 十页、两tabs。骑手工程未配置真实AppID，发布检查必须阻止；不得借用用户AppID。用户个人private config域名校验关闭仍为已知发布阻塞，不在本次擅改。

新版使用 `/api/v1/auth/miniapps/USER/login` 及明确的ticket关联/创建协议，手机号归属只能本次真实微信授权验证；受保护关联码通过“我的”确认、新wx.login签发，另一端明确确认后关联，不传跨端token。短信能力不可用默认隐藏。已入驻骑手读取档案，新入驻在骑手端为当前主体提交fresh phoneCode，不再使用公开手机号注册。

401或40101隔离同owner同后端业务标记，同userId重新登录恢复原orderID与提现requestId，不另建；显式退出/异账号/换服务完整清除。普通403不退出。下文历史部署记录中原统一工程、旧登录接口、骑手页面及401删除pending的描述已由本节替代。双端完整人工检查、AppID/隐私/旧账号恢复/JWT维护窗口/隔离MySQL发布阻塞见 `../rider-miniapp/WECHAT_DEPLOYMENT.md`。本轮只离线验证，不执行下文历史部署示例命令，不启动默认/local后端、不上线。

## 已固定的项目配置

- 微信开发者工具直接导入本目录 `miniapp`，`miniprogramRoot` 为 `./`，根目录直接包含 `app.json`。不再使用框架源码、native子目录或dist。
- 根目录 `config.js` 的 API 根地址为 **https://xt.allms.cn**。默认填 origin，也兼容以 `/api` 结尾的 API_BASE_URL（本项目配置键为 `BASE_URL`）；不要添加 `/login` 或开发端口5173。
- API 模块自动拼接 `/api/v1/...`，避免重复 `/api`。网页 `/login` 可用并不能单独证明接口可用；匿名请求 `/api/v1/user/me` 应返回 JSON 401。
- 默认禁止服务地址覆盖，历史 `native_base_url` 不影响线上请求。首次升级会清理来源不明/不同服务的登录态与订单草稿，要求重新登录；同一官方服务的后续启动保留会话。
- 即使维护者临时开启 `ALLOW_DEV_SERVER_OVERRIDE`，也只对 `develop` 版本生效，`trial` 和 `release` 始终使用官方根地址。
- 项目启用 URL 合法域名校验及 JS/WXML/WXSS 压缩。packOptions明确排除scripts、文档、包清单、node_modules和dist；只上传原生运行文件。

## 仍需提供的真实 AppID

核对 `project.config.json` 和开发者工具“详情 → 基本信息”中的实际 AppID，必须与后端生效的登录配置一致（形如 wx 开头的18位标识），不能使用游客或测试占位 AppID，不要填写 AppSecret：

```json
"appid": "你的真实小程序AppID"
```

这是唯一小程序 AppID 配置位置。游客 AppID 仅能查看界面，不能用于正式上传和真实微信换码。

## 微信公众平台设置

1. 使用对应小程序账号登录微信公众平台。
2. 开发管理 / 开发设置 / 服务器域名，添加 **request、uploadFile** 合法域名：`https://xt.allms.cn`（无路径、无端口）。头像公开读取也需符合微信图片 HTTPS 域名规则；域名变更时一并核对图片及上传配置。**用户端与骑手端是独立 AppID，需分别在各自小程序后台配置**；只配一端时另一端真机会报 `url not in domain list`。
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

更新配置后，由部署负责人按实际部署文件重建后端容器，例如：

```bash
docker compose up -d --build --force-recreate backend
```

微信登录调用 `POST /api/v1/auth/wechat-login`，后端通过微信官方 `jscode2session` 换码并签发平台JWT。客户端不提供 openid、手机号或 session_key。未配置或不匹配凭据时不能完成真实微信登录。若已在管理后台保存登录配置，数据库配置优先于环境变量；格式与完整性校验不会联网证明凭据有效。

### 头像、昵称和业务必需手机号

- 不会自动获取头像、昵称或手机号。微信及短信登录成功后留在同一登录页，以**新登录会话**读取 `GET /api/v1/user/me`。手机号已绑定且昵称有效则自动返回原业务页或“我的”；否则在当前页显示资料表单，不再跳转独立资料页。头像昵称不能通过一次微信登录授权自动取得，仍需原生选择/填写；手机号仍需独立授权。首页登录同样遵守此流程。
- 昵称由用户主动选择或填写并点击保存，作为平台展示用户名，不是微信号或真实姓名。微信默认昵称不代表已完善；`PUT /api/v1/user/profile {nickname}` 只提交昵称，返回完整 me。
- 头像由用户主动点击 `chooseAvatar` 选择；登录页点击“保存并继续”会依次上传选中的头像、保存昵称并重新核验 me，失败留在原页重试，已成功上传的头像不重复上传。“我的”独立资料页点击“保存头像”后调用 `wx.uploadFile`。可保留默认头像，不强制上传才能浏览或下单。支持5MB以内 JPEG/PNG；`POST /api/v1/user/avatar`，multipart 字段名 `file`，Bearer 认证，不能手工指定 multipart Content-Type。
- 上传接口、昵称保存、手机号绑定和 GET me 均返回完整 me，包含持久化 `avatarUrl`（`/api/v1/avatars/<uuid>.jpg|png`）。头像 URL 按服务 **origin** 解析，即使 API_BASE_URL 含 `/api` 也不重复拼接路径。服务必须持久化头像文件并允许公开读取；头像不可包含私密身份字段，不应上传证件、手机号等信息。客户端仅缓存合法服务端路径，绝不缓存 chooseAvatar 临时路径。客户端对文件大小/格式的探测只用于友好提示，探测能力缺失或失败不会拦截上传，最终由后端校验；上传失败会区分“uploadFile 合法域名未配置”“HTTP 413 体积上限”“证书/超时”等可执行原因。若经反向代理部署，代理请求体上限须不低于 6MB（后端 multipart 为 5MB），否则大图会以 413 失败；开发者工具本地 `urlCheck:false` 不能作为上线依据。
- 手机号绑定入口：登录后的授权按钮，或“我的 → 手机号绑定”（专用模式不要求先修改头像昵称）。绑定成功立即生效，“完成”返回“我的”后刷新资料。只有接口明确返回 `mobileBound: true` 才显示成功；未确认成功可重新读取状态，旧凭证不重放。
- 手动输入为另一条独立验证流程：`POST /api/v1/user/phone/sms-code {mobile}` 返回 `{retryAfterSeconds,mock,demoCode}`；`POST /api/v1/user/phone {mobile,code}` 返回完整me。两接口需要平台JWT，验证码绑定用户+号码+绑号用途，不能复用 `/auth/sms-code` 的登录凭证，不能只填号码直接绑定。
- 开发测试免验证码：`GET /api/v1/user/phone/options` 的 `developmentBypass` 决定是否显示“开发模式直接绑定”，提交 `/api/v1/user/phone/development {mobile}`，不发送/校验短信。后端仅在local/development profile且开发开关开启时允许；local默认开启（`SMS_BINDING_DEV_BYPASS_ENABLED=false`关闭），同时激活prod/production/docker一律拒绝。仅允许隔离开发数据和测试号码，未验证归属的数据不能迁入生产。选项查询失败或服务端拒绝时不放开免验证。
- `SMS_BINDING_DEV_BYPASS_ENABLED=true` 还要求 `SPRING_PROFILES_ACTIVE=local` 或 `development`，重启后生效；两个Compose固定使用docker，不允许免验证。`/phone/options` 返回reason/message诊断，页面显示当前后端地址并允许刷新。开发者工具里的小程序不会自动连接本机后端，当前配置仍是 `https://xt.allms.cn`，必须核对实际请求服务；不要为开发测试放开真实用户服务。
- 手动短信绑定默认关闭（`SMS_BINDING_MOCK_ENABLED=false`），未配置真实短信时返回503，不声称已发送。显式开启mock仅供本地联调，页面以“未发送真实短信”显示测试码；正式环境必须关闭，且真实网关未接入前只能使用微信授权。6位码有效5分钟，60秒冷却、5次错误作废、用户/号码每日各10次限制；进程重启后需重新获取，多实例需改为共享的验证码与限流存储。
- 微信方式须用户单独点击 `getPhoneNumber` 授权按钮。后端使用同一 AppID 的有效凭据获取应用 `access_token`，向微信换取手机号后才绑定；前端不得提交或缓存完整号码作为验证依据。`POST /api/v1/user/wechat-phone {code}` 中的 code 不是 `wx.login` 的 code。me 中 `mobile` 始终脱敏，以 `mobileBound === true` 判断是否绑定。
- **手机号和已保存的非空昵称是下单必填。** 拒绝、取消、无 code 或能力不可用时保留登录态，可返回浏览，但不能下单。创建页继续和确认页提交都实时读取服务端 me 做门禁，失败保留订单草稿。服务端创建订单业务码 `4091` 也引导完善资料，并清除匹配草稿的“创建待核实”标记以便完善后重试，不丢弃草稿。
- 号码冲突返回409，不切换账号、不覆盖占用号码、不合并订单或角色；已绑定账号可走独立换号流程，保持原userID/wallet/order/role。绑定不会自动填入配送联系电话。授权凭证失效返回业务400而非登录401；微信权限未开通返回503提示管理员检查，配额或频控返回429；未知微信错误/异常响应返回502且不回显微信原始错误、凭据或手机号。
- 在微信公众平台核对手机号能力的主体、认证、权限与额度要求；完善头像、昵称和手机号用途、存储、删除/更正渠道以及用户隐私保护指引，并真机验证平台隐私弹窗。不能用本地文案代替后台配置，也不要将手机号API填入定位类 `requiredPrivateInfos`。
- 手机号也是原有短信登录的查找键。**生产短信登录仍是演示能力，正式用户环境必须关闭演示验证码回传并接入真实短信验证服务，或禁用短信登录入口。** 资料绑定不将演示短信变成生产认证能力。
- 真机验收至少包含：微信/短信登录、首页登录回跳、头像选择和保存、上传失败重试/格式与大小限制、重新进入展示持久化头像、昵称选择/手填并主动保存、同意/拒绝手机号、未完善返回浏览但下单被拦截、草稿保留、同号幂等/跨账号冲突、业务401及退出/切号后的旧上传和资料响应隔离。离线测试不调用真实微信，不能证明手机号资格已开通。
- 微信接口说明参考：<https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-info/phone-number/getPhoneNumber.html>、<https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/mp-access-token/getStableAccessToken.html>。平台规则和接口要求应以当前官方文档及后台为准。

### 手机号换绑与接单转派联调

- 换绑保留原用户ID、钱包、订单与角色，只验证新号、不提交旧号。后端须提供 `POST /user/wechat-phone/change {code}`、`POST /user/phone/change/sms-code {mobile}`、`POST /user/phone/change {mobile,code}`、`POST /user/phone/development/change {mobile}`（均为`/api/v1`前缀；发送验证码返回SMS结果，其余返回完整masked `ProfileVO`）。`GET /user/phone/options`不变。开发免验证仅后端允许时可见，生产不得放开；默认未配置真实短信换号503，mock/noSMS不能声称真实验证。
- “我的 → 手机号绑定”和资料页已bound显示更换，phoneOnly同样可换；`changingPhone`与首次绑定严格分开，三方式提交前确认，取消清输入。成功只更新资料/新masked和toast，不重登、不递增session revision、不清订单或提现待核实草稿；冲突不覆盖占用号。手填新号码与code仅页内存，取消/成功/hide清空，切号及旧选项/短信/换号响应隔离。
- 转派创建路由必须为 **`POST /api/v1/rider/orders/{id}/transfers {targetRiderId,reason}`**；查询`GET /rider/transfers?page=0&direction=INCOMING|OUTGOING&status=可选`每页15，处理`POST /rider/transfers/{id}/respond {decision:ACCEPTED|REJECTED,reason:''}`与`POST /rider/transfers/{id}/cancel {reason:''}`，订单只读历史为`GET /orders/{id}/transfers`。完整TransferVO见本目录README。客户端不增加全国骑手搜索，不传用户ID作目标。
- 仅RIDER入口；工作台显示自己的riderId便于分享，每个ACCEPTED订单可申请。只允许未取件/未开始服务，原因1–255字且指定现役目标骑手ID。对方在工作台上线（onlineStatus 1/2）后于incoming接受才交接；拒绝/撤销仍原人负责，开始履约使pending过期。最终骑手取得原收入并负责投诉，原人保留转派记录访问但无履约或私有订单详情权限。专页带orderId才查历史，不扩大原详情请求。失败/未知结果查询服务端，不本地假成功；切号清列表，返回工作台刷新承接订单，原人pickup/serviceStart后端403不推进。
- 真机/联调覆盖：微信新号授权/短信新号/开发免验证、占用409与短信503/expired、取消、mock/开发文案、原号masked与成功新masked、会话和订单钱包草稿不变；两名现役骑手分享riderId、离线接受被阻、上线后接受/拒绝/撤销、Page15、待转派继续取件或开始服务自动过期、重复点击及超时核实、最终骑手原收入与投诉归属、原骑手历史可看/私有详情和履约403、切号/hide/unload旧回调隔离。离线wx-mock不证明后端部署、真实微信/短信授权或并发交接安全。

### 自定义任务及投诉联调

- 首页为飞毛腿、滴滴接送、自定义任务三入口。飞毛腿合并送件/代办，新单统一 SEND，保留 BUY 切换；ERRAND 深链进入合并表单，历史 ERRAND 及 CUSTOM/DELIVERY 订单保留原配送和支付流程。接送入口为 `pages/order/create?type=CUSTOM&service=transport`，只选 RIDE/DRIVING；原自定义入口仍只有 ONSITE/PART_TIME/ONLINE。后端须同时提供 `GET /api/v1/task-tags`、`POST /api/v1/custom-tasks/quote {priceFen}`、平铺 `POST /api/v1/custom-tasks {title,content,tagIds,taskMode,priceFen,contact,phone,locationAddress,serviceTime}`、`GET /api/v1/orders/{id}/custom-task`、`GET /api/v1/orders/{id}/complaints`、`POST /orders/{id}/service-start`及`service-complete`，并保留现有投诉提交与订单支付接口。详细请求契约见本目录README。发布返回必须是含订单ID的 `OrderVO`，不能返回仅有任务ID的TaskVO。
- 普通自定义标签来自后台启用记录，最多5个、可不选；taskModes 白名单扩大至五场景且仅展示适用于当前普通场景的标签，旧空分类不猜测。读取失败必须可重试，不能用固定标签或假空列表替代。普通三场景标题1–64字、内容1–1000字保持必填；接送/代驾不请求标签接口，提交空 tagIds、空 title 与可空 content（选填备注），固定标题和空备注默认说明由服务端按稳定 taskMode 生成，不依赖客户端标题分类。详情读取完整资料。
- `OrderVO.owner` 是当前登录人是否发布者的布尔值，不是角色或公开用户编号；骑手发布自己的任务也应返回true，承运他人订单应返回false。旧接口无owner时仅有限兼容，所有提交与查询最终由服务端判权。自定义任务的userId只用于页面私有内存兼容判断，不额外展示身份。
- 投诉描述1–255字，从已接单起至已完成可申请。活动仅 `OPEN/PROCESSING`，有活动单不能重复提交；`APPROVED/REJECTED/DONE` 终结后可申请不同问题，但累计赔付受本单骑手分账上限约束。详情按服务端记录展示状态、问题、结果与赔付；查询失败不得冒充暂无记录，提交失败保留描述并先刷新核实。
- 审核赔付目前 **MOCK，仅记账，未真实退款或转账**。前端不计算扣分账或自行修改审核状态，不展示经办人/骑手内部ID、扣减审计字段和内部操作明细。真实结算需要另行实现与验收。
- 真机/联调覆盖 USER和RIDER各自发布、0.01/0.29/1000000元精确总价及超限、多位小数/科学计数拒绝、服务端分成展示且缺失不推算、三场景与示例、ONLINE无地址和时间但要联系人电话、ONSITE一个下拉地址/自动联系人可改、PART_TIME一个工作地址+必填时间、切换清理、普通/自定义报价无串路、空标签/5标签/停用变更、标签/资料/投诉加载重试、草稿恢复与切号旧响应隔离、旧DELIVERY草稿提示/原pending保护、旧订单原取件配送、service开始/提交完成/用户确认无定位无取件码、承接骑手详情可看联系方式、owner:false不显示发布者动作、接单后投诉、重复工单、审核通过/驳回/结案后刷新、新问题累计限额及MOCK提示。小程序本地测试不请求外部接口，不证明后端已部署或资金已到账。

### 接送/代驾专项联调与发布边界

- 新模式复用 CUSTOM 报价、发布、MOCK 支付、分账与服务完成链路。接送/代驾只收一个起始地址、可修改联系人/电话、精确总价、必填时间说明（1–100字）、选填备注（最多1000字）；无目的地、取件配送参数、标题和标签操作。起点变更/删除、价格、时间、备注、联系人或分类变化必须重新报价，确认页总额和分账只取服务端响应。
- 发布前由后端部署负责人核实 RIDE/DRIVING 白名单、按模式条件校验、固定标题及骑手两项默认关闭能力和相应幂等迁移已在获准的隔离环境验证；小程序构建不执行 SQL、不自动迁移/部署，不启动默认/local 后端连接真实库。接送与代驾须分别授权，普通 RIDER 或车辆类型不等于有权。大厅、直接抢单、转派接受、首次开始的拒绝均由服务端兜底，不在客户端假开通；撤权后不可新增接单或首次开始，已开始本人仍可完成。
- 保持 C 端 OrderVO 19字段及 TransferVO 原公开字段契约。用户列表无真实时间摘要时引导详情；有权详情展示 custom-task 真实起点/时间，骑手大厅不展示联系人电话或完整备注，转派记录不扩展私有地址/时间。新服务仅调用 service-start/service-complete 再由发布者确认，无取件码、配送地图或定位；轨迹深链也返回详情。旧 DELIVERY/缺模式仍按配送回退。
- 草稿按 owner 及 RUNNER/CUSTOM/TRANSPORT 三组隔离，RUNNER 使用 `RUNNER_SEND` / `RUNNER_BUY` 子槽独立保存需求和 BUY 商品预算，历史 ERRAND 归 SEND，兼容原 RUNNER 单槽及旧 `native_draft`；接送组保留接送/代驾子类。`native_draft` 与匹配 owner+savedAt 的 pending 仍是创建/支付续办依据。已知或结果未知 pending 只锁所属子槽，其他组或 SEND/BUY 另一子槽可保存、重入恢复编辑，但不得改变原活动草稿、pending 对象及 savedAt；旧活动草稿也不得覆盖较新的分组编辑。任何入口遇到已创建或结果未知记录先续办/核实，不允许合并 ERRAND、切换分组/子类或缺字段时重复创建/支付。相同 payload 复用身份，完成只清匹配 owner+savedAt 的子草稿，旧异步结果不清新草稿，退出/切号清全部组和子槽。
- 微信开发者工具及隔离账号联调至少覆盖：三个入口与登录回跳、飞毛腿/BUY 切换及旧 ERRAND 深链、接送/代驾切换、一个起点及自动联系人可修改、空时间/超100字/金额边界/选填空备注、标签接口故障不影响新两类、分账核对、三组及 SEND/BUY 子槽草稿往返恢复/切号、BUY→首页 SEND→BUY 保留需求/预算、已知待支付与创建未知时其他组/子槽保存恢复但仍优先续办、完成仅清匹配子草稿、旧CUSTOM配送、普通/仅接送/仅代驾/两项授权骑手交叉抢单、转派邀请后撤权、未开始撤权403和已开始撤权后完成、发布者确认、投诉和钱包原账务链路。离线 mock 只验证客户端行为，不证明后端权限并发、账本或真实资金安全。
- “滴滴接送”为本平台入口名称，未接第三方滴滴接口；平台接单授权不是驾驶资质审核。无自动预约派单、时间冲突调度或真实支付，不把普通骑手身份宣传为驾驶资格。正式运营所需类目、许可、安全责任及法律文本需由运营方审核。

### 钱包与模拟提现联调

- 本轮只修改miniapp。后端需提供 `GET /api/v1/wallet`、`GET /api/v1/wallet/entries?page=0`、`GET /api/v1/wallet/withdrawals?page=0`、`POST /api/v1/wallet/withdrawals {amountFen,requestId}`，两类列表每页15条；身份从JWT读取，客户端不传userId/role。字段契约见README。接口缺失或失败明确显示未加载与重试，不展示假余额或假空记录。
- 前端金额为整数分，单笔1–100000000分；新请求需手机号已绑、实时余额足够、欠款清零和 `withdrawAllowed:true`，但最终冻结/拒绝必须服务端原子决定。承接者今后完成订单按原分账减本单投诉结算，投诉赔付模拟记入发布者钱包；旧单不保证补记。后台approve仅MOCK出账、reject解冻先抵debt；debt未清不能新提现。
- **所有钱包、支付、赔付、提现均MOCK，不发生真实微信支付或转账，不表示真钱到账。** 小程序只提交申请和查询，不提供管理员审核，也未实现任何真实资金API。此发布配置检查不证明服务端幂等、账本一致性或资金安全。
- 真机/联调覆盖：USER/RIDER各自钱包与越权拒绝、进入钱包实时请求、未加载/断网/重试不伪造0、余额/冻结/欠款解释、绑号入口、0.01/0.29/1000000元精确转换及超限/负数/多小数拒绝、两类Page15独立分页/终态、重复点击只冻结一次、网络超时后重入同key同金额重试、后台通过及驳回后实时刷新、欠款抵扣、新旧账号与hide/unload回调隔离。没有图表或“我的”余额KPI。
- 确认弹窗必须展示冻结原金额及MOCK边界。专用 `native_pending_withdrawal` 仅保存owner、金额和幂等编号，发送前落盘，未知结果锁定原金额/编号；匹配确认成功才清除并刷新钱包/记录。退出/切号/401清理本机key不取消服务端冻结，重新登录先核实记录，不能把清标记当作取消或盲目重建申请。
- 本环境没有官方wcc/wcsc，本轮只执行离线build与check:release，不尝试官方编译、不上传、不发布；正式体验仍需开发工具和真机验收。

## 本地校验与上传

在项目根运行（无需安装任何npm依赖）：

```bash
npm test --prefix miniapp
npm run check --prefix miniapp
npm run build --prefix miniapp
npm run check:wechat --prefix miniapp
npm run check:release --prefix miniapp
```

- `build`：静态检查和 wx-mock 行为回归，不请求外部接口。
- `check:wechat`：可选，依赖本机微信开发者工具中的官方编译器；非默认目录可设置 `WECHAT_DEVTOOLS_HOME`。
- `check:release`：检查真实 AppID、官方HTTPS根地址、锁定服务、域名校验及压缩。游客 AppID 会故意使检查失败，补真实AppID后再执行。

随后由有权限人员在微信开发者工具验收、预览和上传：

1. 导入 `miniapp`，核对 AppID 与入口 `./`，根目录应直接找到 `app.json`。
2. 编译，确认合法域名与证书校验开启。
3. 微信/短信登录 → 头像昵称和手机号资料 → 地址簿省市区联动/街道门牌编辑 → 选择取送地址/填写需求 → 一次获取费用进入确认 → 一次确认发布（MOCK） → 订单查询/骑手流程，逐项真机验收；默认取件地址须可更改，联系人修改和重量/备注折叠区域须能展开；确认页保留报价与模拟支付提示，不再弹重复确认。覆盖取消地区选择、旧地址编辑、空地址簿、刷新失败重试及切号后旧响应隔离。省市区来自微信原生地区数据，不代表配送服务范围或自动定位/测距。后端须部署 `20261115_address_region.sql` 及新版地址接口后再发布。
4. 验收无误后上传体验版；正式版需要提交审核并通过后发布。

代码修改与本地检查不自动执行预览、上传、审核或发布。

## 当前演示能力边界

- 支付、投诉钱包赔付与提现均为 **MOCK**，不发生真实扣款/退款/微信转账；审核通过仅模拟出账，不代表真钱到账，不要把体验版当作真实交易系统。
- 短信验证码与骑手入驻仍存在演示逻辑，生产须接入真实验证或禁用对应入口。
- 真实微信授权未因HTTPS配置而自动完成，必须用真实AppID/Secret实测。
- 服务/隐私页面是当前数据流说明，正式法律文本、业务资质及审核材料必须由运营方补全。
- 仅实现公开头像上传，送达照片凭证、OCR、真实支付、微信短信既有账号合并仍未实现；手机号换绑已接独立接口，须配套后端及真实授权验收。业务必需的手机号绑定不等于账户合并，也不解决真实微信登录的凭据错误。
