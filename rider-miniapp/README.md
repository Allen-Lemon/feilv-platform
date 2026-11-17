# 飞毛腿骑手微信原生小程序

本目录为独立可导入的骑手端原生源码根：App/Page、CommonJS、WXML、WXSS、JSON，无框架、外部运行时依赖、跨根 require、软链接或 dist。用户端继续使用同级 `miniapp` 与原 AppID。

## 页面与业务边界

共十页，工作台与我的为两个 tab；普通页面为入驻、转派、登录、资料、钱包、设置、协议及承接订单只读详情。

- 工作台保留上线、抢单大厅、承接订单分页、取件码核销、出发配送、送达、服务开始/提交完成、信用明细与转派。只有真实 RIDER 角色可进入工作流程；未入驻账号引导当前账号入驻，不按客户端来源强制骑手角色。
- 接送与代驾分别由后端授权，默认关闭，普通骑手角色不等于驾驶资质。非配送服务不使用取件码或定位。
- 普通配送仅在本人点击且确认后通过 `wx.getLocation` 获取本次真实位置，使用 GCJ-02；无后台持续定位、无虚构轨迹。
- 只读详情使用 `GET /api/v1/rider/orders/{id}` 和 `/rider/orders/{id}/custom-task`，仅后端确认的当前承运骑手可读。无用户发布、支付、取消、确认、评价、投诉申请或轨迹按钮，也没有相应消费写 API。交接后的原骑手仅可保留转派记录读取权，不得继续读私有详情。
- 资料、头像、手机号更换和钱包复用共享源。支付、钱包、提现全部为 MOCK，不表示真钱到账；不制造余额、分成或本地成功状态。

## 身份、关联与入驻

`config.js` 固定 `CLIENT: 'RIDER'`。微信登录调用 `/api/v1/auth/miniapps/RIDER/login`，发送 `{code,agreed:true}`。已关联返回 `{state:'AUTHENTICATED',login:LoginVO}`；未关联返回 `{state:'IDENTITY_REQUIRED',loginTicket,expiresAt}`，必须明确选择关联已有平台账号，或通过本次原生微信手机号授权创建新账号。不会按手机号、unionid、客户端 role 自动合并账号。

在另一端的“我的”登录原平台账号，明确确认后使用新的 `wx.login` 凭证签发临时一次性关联码。再在本端输入临时码并再次确认。临时 ticket、码、手机号 code 只留页面内存，不写 URL、日志或本机持久存储；离页隐藏。两端不互传 bearer token、手机号、草稿或 pending。

入驻为受保护 `POST /api/v1/rider/enrollment {name,plateNo,phoneCode,agreed:true}`：当前已认证平台主体不变，姓名最多30字、可选车牌最多16字，新入驻必须本次真实微信手机号授权及隐私门禁，即使之前已经 `mobileBound`。已有 RIDER 先读取真实骑手档案，再 switchTab 返回工作台，不重新走手机号注册。结果未知时先重新微信登录读取档案核实。旧仅手机号且无微信身份的账号需运营方受控人工核实，不能用演示短信恢复或继承资产。

骑手端 `/auth/miniapps/RIDER/options` 当前固定返回 `smsLoginEnabled:false`，仅使用本端微信登录；旧演示短信接口只签发USER来源，不能充作骑手端登录。共享登录页在能力接口不可用时同样默认隐藏短信入口，不宣称已接入真实短信。

## 会话与未知结果保护

401 或业务码40101过期清除凭证与私有页面缓存、递增会话 revision，但将属于当前 owner 和后端的业务标记隔离到 `native_expired_business`。同一服务、同一确定 userId 重新登录才恢复原待核实提现金额和 requestId；不同账号、显式退出或切换服务地址清除旧标记。匿名页面不能展示原金额。普通业务403不退出登录，旧 token 的晚到401不能使新账号退出。

提现未知结果只能以原金额与 requestId 重试，不得另建。查询金额来自服务端，不缓存或推算钱包余额。

## 独立导入与离线检查

1. 微信开发者工具直接导入本目录，`miniprogramRoot: './'`。
2. `project.config.json` 的 `UNCONFIGURED_RIDER_APPID` 是明确未配置状态，不是虚构 wx AppID。须由负责人填写真实且不同于用户端的 AppID；AppSecret 只能配置后端。未复制用户端个人 private config。
3. 本地无需 npm install。项目根可运行 `node scripts/check-miniapps.cjs`，或分别运行两个根的 `npm run build`、`npm run check:wechat`。
4. `miniapp-shared/manifest.json` 是24个稳定共用源码文件的明确清单。修改共享源后运行 `node scripts/sync-miniapp-shared.cjs`；`--check` 只读核对两根副本一致，不改文件。上传工程内已包含副本，不依赖运行时生成或父目录。

真实 AppID、微信登录/手机号能力、真机隐私授权、历史账号恢复和维护窗口 JWT 切换均未由离线测试证明。发布阻塞与人工验收见 [WECHAT_DEPLOYMENT.md](WECHAT_DEPLOYMENT.md)。本轮不上传、不部署、不接线上库。
