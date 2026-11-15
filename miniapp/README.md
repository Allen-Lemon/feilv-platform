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

## 发布任务（简化流程）

- 首页仅两个入口：“跑腿服务”默认进入SEND，填写页选项切换SEND/BUY/ERRAND；另一个为“自定义任务”。旧BUY/ERRAND深链继续有效，切换类型清理不适用的表单、地址与报价，不能覆盖待核实订单。普通用户和骑手账号均可作为发布者发布。订单统一走底部“订单”，地址簿与服务设置集中在“我的”，不重复放在首页。
- 填写页选择取送地址后展示联系人摘要，需要时才展开修改。已有默认地址会在空白取件地址处自动带出，送达地址仍需明确选择。
- 常见物品/事项可点选；重量默认1公斤并明确展示，实际更重请在“更多要求”修改，备注也在该区域。预估距离仍必须手填，不伪造地图测距。
- 点击“确认费用，下一步”会保存当前账号草稿、校验手机号和昵称、获取服务端报价并直接进入确认页，不再要求单独计算费用。
- 确认页一次点击“确认发布 · MOCK 支付”完成创建及模拟支付，不再弹第二次相同确认。网络失败或创建结果未知仍保留原订单保护，不允许覆盖待核实任务。
- 再次进入同账号、同服务类型的填写页可恢复上一次继续时保存的任务。报价失败、资料未完善、切号或旧异步响应不会替换其他账号/任务的草稿。

## 自定义任务与投诉接口

- 自定义任务沿用“填写 → 确认并 MOCK 支付”两步发布，标题1–64字、内容1–1000字。场景为 `ONLINE`（资料整理/设计，无地址）、默认 `ONSITE`（通马桶/家电清洁，一个服务地点）、`PART_TIME`（搬水泥/搬运，一个工作地点及必填时间）。联系人及11位电话始终必填；地点从已保存地址下拉选择或新增，自动带出联系人电话且可改；时间为最多100字人类说明，上门可选。切换场景清空地点和时间，ONLINE不提交地址或时间。CUSTOM无重量、距离、取送两地址或配送步骤。
- 标签来自 `GET /api/v1/task-tags` 的启用记录（`id,name,enabled,sort,version`），最多选5个，也可不选。后台配置是唯一来源；加载失败显示错误与重试，不生成演示标签或静默当作空列表。草稿保存任务标题、内容与已选标签，切号响应不得覆盖当前账号数据。
- 总价由用户填写 **0.01–1000000元，最多两位小数**，复用钱包十进制字符串精确元分转换，不使用浮点舍入。用户价是总额，不是骑手实拿。`POST /api/v1/custom-tasks/quote {priceFen}` 返回 `{totalFen,items,riderShareFen,platformShareFen}`；确认页展示发布总价/骑手预计所得/平台分成，校验返回总价相等及两项分成非负整数且合计一致。默认70%/30%只是规则说明，实际数值必须来自后端，不补算缺失分成。普通报价仍调用 `/orders/quote`。
- `POST /api/v1/custom-tasks {title,content,tagIds,taskMode,priceFen,contact,phone,locationAddress,serviceTime}`，无 `order` 嵌套或配送字段。内部保留 `taskTitle/taskContent/taskTagIds`，新增 `taskMode/taskPriceFen/contact/phone/locationAddress/serviceTime` 在API边界映射；编辑态金额为独立data.taskPrice字符串，tag IDs为排序逗号串，API才转数字数组。返回含订单ID的 `OrderVO`，继续复用原支付/确认接口；普通创建仍调用 `/orders`。旧CUSTOM配送草稿提示重新填写，已创建ID和未知结果保护不能被覆盖，已创建订单继续原确认支付流程。
- 仅自定义详情读取 `GET /api/v1/orders/{id}/custom-task`，返回 `TaskVO` 或 `null`，在旧字段上增加 `taskMode,priceFen,contact,phone,locationAddress,serviceTime,riderShareFen,platformShareFen`。`OrderVO.taskMode` 普通及旧CUSTOM为 `DELIVERY`，缺字段也兼容DELIVERY；非配送列表/详情展示线上任务或单服务地点、联系人与时间，隐藏取送、取件码及地图。身份和来源等内部字段不额外展示；接口失败须可重试，不能伪造任务内容。
- 仅现有RIDER从工作台承接，不增加普通用户任务大厅。`GET /grab/list` 追加 `title,taskMode,locationAddress,serviceTime`，不展示联系方式；`/rider/orders` 追加 `orderType,taskMode,title,serviceTime,locationAddress`，`rewardFen`仍表示骑手预计所得。承接订单可点击“查看任务详情”查看有权访问的完整要求与联系方式，`owner:false`不展示发布者支付、取消、确认或评价按钮。
- 非DELIVERY CUSTOM履约为 `ACCEPTED → POST /orders/{id}/service-start → PICKING服务中 → POST /orders/{id}/service-complete → DELIVERED待用户确认 → 用户确认完成`。无取件码、不调用pickup/depart/deliver或定位；grab返回空pickupCode不展示核对取件码弹窗。普通及旧DELIVERY订单继续原配送工作流。
- 投诉由订单发布者提出，不是骑手承运操作。订单状态为 `ACCEPTED/PICKING/DELIVERING/DELIVERED/FINISHED` 时可申请，提交保留 `POST /api/v1/complaints {orderId,type,description}`，描述去空白后为1–255字，返回 `ticketId,sla`。角色并不等同归属：骑手也可能是发布者。优先使用 `OrderVO.owner` 判断当前账号是否发布者；旧接口缺失该字段时，自定义单使用 TaskVO.userId 在页面私有内存判断，普通单仅对 USER 兼容开放、仍以后端403拒绝为准。不增加发布者身份展示。
- 订单详情通过 `GET /api/v1/orders/{id}/complaints` 读取服务端工单列表，展示 `OPEN/PROCESSING/APPROVED/REJECTED/DONE` 状态、问题、处理原因与赔付金额。每订单最多一条活动工单（`OPEN/PROCESSING`）；有活动单时隐藏重复提交表单。`APPROVED/REJECTED/DONE` 为已终结，可再次提出新问题，但累计限额由后端控制。读取失败明确提示并提供重试，不能冒充“暂无投诉”；提交描述在失败时保留。
- 审核通过后由后端扣减骑手**该订单**可分账金额赔付发布者，累计扣减和额度以服务端为准。当前赔付为 **MOCK，模拟记入订单发布者钱包，未真实退款或转账**。用户界面不展示内部骑手/经办人编号、扣款审计字段或内部操作明细；工单与赔付刷新不靠本地假状态。
- 后端须配套实现以上接口后才可联调；本地 wx-mock 验证不代表接口已部署、真实资金结算或微信审核通过。

## 钱包与 MOCK 模拟提现

- 入口为“我的 → 我的钱包”，只新增普通菜单行，不增加余额统计或KPI；`pages/wallet/wallet` 四件套提供余额表单、收支明细/提现记录 tabs，均为原生列表，不使用图表或npm依赖。USER/RIDER均只查看当前JWT所属钱包，不传 `userId/role`，授权与金额最终由后端决定。
- `wallet()` → `GET /api/v1/wallet`：`{userId,availableFen,frozenFen,debtFen,totalIncomeFen,totalWithdrawnFen,channel:'MOCK',withdrawAllowed}`。仅显示可用、冻结、待抵扣欠款，不把查询失败伪造成0；每次进入及提交成功后实时读取，不缓存钱包余额。
- `walletEntries(page=0)` → `GET /api/v1/wallet/entries?page=0`，每页15条，Spring Page `{content,number,last,...}`；EntryVO `{id,type,amountFen,availableAfterFen,frozenAfterFen,debtAfterFen,orderId,complaintId,withdrawalId,remark,createdTime}`。金额保留正负，展示必要关联编号、变动后三项余额与说明；不推算或制造本地收入。
- `withdrawals(page=0)` → `GET /api/v1/wallet/withdrawals?page=0`，每页15条；WithdrawalVO `{id,userId,amountFen,status,requestId,reason,reviewedBy,reviewedTime,createdTime,channel:'MOCK'}`。状态为 `PENDING/APPROVED/REJECTED`，分别明确“待审核（已冻结）/已通过·MOCK模拟出账/已驳回（解冻先抵欠款）”；模板不透传内部经办人身份，归属不符响应不展示。两类分页独立，失败重试同页，切号/hide/unload及重入前的旧响应不能追加或推进新列表。
- `requestWithdrawal({amountFen,requestId})` → `POST /api/v1/wallet/withdrawals`，只发送这两个字段，返回 WithdrawalVO。元输入仅接受十进制文本、最多两位小数，以整数元和小数位拼成整数分，范围 **1–100000000分（0.01–1000000元）**，不使用浮点元乘100/舍入；拒绝负数、多位小数、NaN/科学计数和超限。
- 今后订单完成触发承接者按**原分账减本单投诉赔付**的模拟结算；投诉赔付模拟记入发布者钱包。历史订单是否已入账以服务端明细为准，不保证旧订单补记或有余额。待抵扣欠款是投诉扣减未扣清的部分，不是可用资金；后续入账和解冻先抵扣欠款，欠款未清不能申请新提现。
- 新提现需实时钱包、手机号绑定状态及服务端 `withdrawAllowed` 允许，可用不足/有欠款/未加载均禁用；未绑手机引导 `/pages/profile/profile?from=wallet&mode=phone`；服务端403拒绝后重新读取钱包和me核实绑定状态，不假设4091、不把任意403判作未绑定。这些只是UI预检，不能替代服务端原子判定。已有不确定申请允许按原key核实，即使当前余额已冻结或有新欠款也不另建申请。
- 确认弹窗明确冻结的**原金额**及 **MOCK、非真实微信转账、不代表真钱到账**。申请冻结余额，后台 approve 仅模拟出账，reject 解冻先抵欠款。不调用真实微信支付/转账API，小程序没有审核按钮，不声称真实资金到账；后端审核及记账不在本目录实现。
- 幂等：新申请只生成一次独立随机UUID（优先 `wx.getRandomValues`，不可用时为时间+多段随机值，降级值仅用于幂等，不承担安全鉴权），**发送前**将 `{owner,amountFen,requestId}` 写入专用 `native_pending_withdrawal` 键。存储失败不发送。网络异常、未知/不匹配结果保留原key和原金额，锁定编辑，只能重试原申请；已确认响应须匹配owner/key/金额后才清除并实时刷新钱包与两类记录，不本地加减余额。
- hide/unload、切号后的旧弹窗/随机数/网络回调不能保存、删除或覆盖任何待核实标记；以owner+会话revision+页面代次及各列表请求身份隔离，无钱包轮询定时器。退出/切号/401会清除专用key，但不能取消服务端申请或冻结；重新登录须先核对提现记录，勿对已冻结申请另建重复请求。手机号与钱包金额不缓存。
- `scripts/test-native.cjs` 的wx-mock新增钱包四端点、元分边界、Page15/重试/终态、一次冻结与同key网络重试、错误不假余额、owner/revision/lifecycle隔离和展示最小化回归。mock“冻结一次”用于验证客户端复用幂等键，**不证明后端已部署、数据库幂等或真实微信到账**。

## 地址选择

- 地址簿新增/编辑：使用微信原生 `picker mode="region"` 选择省、市、区县，再手填街道、楼栋及门牌；默认游标在贵州省铜仁市德江县，但未确认选择前不视为已填写。
- 订单取件/收件：使用已保存地址的下拉列表，自动带出联系人、电话及地址，可按本单需要调整联系人；没有地址时先新增，返回后刷新选项。
- 服务端同时保存 `province/city/district/detailAddress` 与完整 `address`。原有未结构化地址仍能显示、选择和设默认；编辑保存时需补选地区并填写详细地址，不猜测或丢弃旧地址。
- 修改地区/街道会清理旧坐标；地区选择不等于地理编码。订单距离仍手工预估，未接入地图选点或自动测距。

## 授权绑定手机号

- 入口：登录后的手机号授权按钮，或“我的 → 手机号绑定”；未绑定时用户卡片显示专门的绑定提示。
- 绑定页只处理手机号，无需先改头像昵称。点击原生 `getPhoneNumber`、同意微信授权后，将动态 `code` 发往 `/api/v1/user/wechat-phone`；只有服务端返回 `mobileBound: true` 才显示成功。绑定立即生效，返回“我的”会重新读取状态。
- 隐私授权、手机号授权是独立步骤，拒绝保留登录态。凭证过期需重新点击原生授权按钮，不能重放旧 code；号码冲突不覆盖或合并账号。已绑定号码仅脱敏展示，暂不支持换绑。
- 手动方式：点击“手动填写手机号并短信验证”，输入本人号码，获取并填写6位验证码后绑定。发送接口为登录保护的 `POST /api/v1/user/phone/sms-code {mobile}`，绑定为 `POST /api/v1/user/phone {mobile,code}`，不复用公开短信登录验证码，不切换账号。验证码与当前用户、号码及绑定用途隔离；5分钟有效、60秒冷却、最多5次错误、用户和号码每日各10次限制。
- **开发模式可临时跳过短信**：后端 `local` profile 默认允许，`SMS_BINDING_DEV_BYPASS_ENABLED=false` 可关闭；普通/生产/Docker环境禁止。切换手动填写时从 `/user/phone/options` 读取开关，只有服务端明确允许才隐藏短信输入、显示“开发模式直接绑定”，不生成/发送短信。号码归属未经验证，仅限隔离开发库与测试账号；前端改标志不能绕过后端环境检查。
- 开关排查：手动绑定表单会展示后端未启用开发profile、开关未开、生产/Docker受保护或接口缺失的具体提示，并列出当前服务地址；更改后端配置并重启后可点“刷新绑定模式”。必须同时满足后端开发profile及开关，不能只改本机变量而继续请求固定的远程服务。
- **非开发环境当前没有真实短信网关，手动短信绑号默认不可用**：发送时明确返回503并提示改用微信授权。仅本地联调可显式配置 `SMS_BINDING_MOCK_ENABLED=true`，页面显示“未发送真实短信”和测试验证码，需要手动填写；正式部署必须保持关闭。接入真实短信发送后，服务端才可返回 `mock:false`，不得回传验证码或将演示模式冒充实际发送。
- 手动输入号码和验证码仅保存在当前页面内存，绑定成功清空，不写本机存储。更换输入号码清空旧验证码，已绑定其他账号的号码仍拒绝绑定，不支持直接换绑。
- 微信授权真机前提：使用微信登录的当前小程序身份，配置相同 AppID/AppSecret，并在微信后台开通手机号能力、检查可用额度及隐私指引。旧版微信或游客AppID不能作为实际绑定验收依据。

## 能力边界

已覆盖微信登录/短信备用、地址簿、送买办及自定义任务发布/标签选择与报价、MOCK支付、订单列表/任务详情、评价、投诉状态/处理结果与MOCK钱包赔付展示、钱包/收支明细/模拟提现及提现记录、轨迹、骑手入驻与工作台。自定义任务、投诉及钱包均依赖配套后端接口。

真实微信授权需配套后端凭据。微信及短信登录后在**同一登录页**以新会话获取 `me`，手机号未绑定或昵称未完善时直接展示头像、昵称及手机号表单，已完整则自动返回，不再跳转独立资料页。头像通过 `chooseAvatar` 主动选择，昵称通过 `type="nickname"` 主动选择或填写，点击一次“保存并继续”即可依次上传头像、保存昵称并核验资料。微信不支持一次登录授权自动获取真实头像昵称，手机号也需单独授权；微信默认昵称不算已完善。“我的”保留独立资料编辑入口，可单独保存头像、昵称。头像上传5MB以内 JPEG/PNG；登录页与资料页复用 `utils/profile-page.js` 的授权、校验和账户隔离逻辑。头像可保留默认，不影响浏览或下单。拒绝手机号授权保留登录态、可返回浏览，但**下单必须绑定手机号并保存非空昵称**。创建页继续和确认页提交均实时查询服务端 `me`，门禁失败保留草稿；服务端业务码 `4091` 也回到完善资料。手机号只展示服务端脱敏值，不缓存；头像只缓存服务端公开路径，不缓存临时路径。昵称不是微信号或真实姓名，手机号授权 code 与登录 code 不可混用。

手机号已属于其他账号或当前账号已绑不同号码时不会覆盖或自动合并。支付、短信及骑手准入仍存在演示逻辑；由于手机号也是短信登录键，正式用户环境必须关闭演示验证码回传并接入真实短信服务，或禁用短信登录。手机号能力资格和隐私保护指引需在微信后台核实。仅有公开头像上传，没有送达照片凭证、OCR、真实支付退款、换号或账号合并，不能把静态测试通过当作真实业务上线或微信审核通过。

详细发布步骤见 [WECHAT_DEPLOYMENT.md](WECHAT_DEPLOYMENT.md)，迁移与检查记录见 [NATIVE_MIGRATION.md](NATIVE_MIGRATION.md)。
