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
└── miniapp/         # 微信开发者工具导入此目录，native/ 为原生源码，src/ 为迁移前备份
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

- 微信开发者工具直接导入 `miniapp`，由 `project.config.json` 指向 `native/`，不再导入 `dist/build/mp-weixin`，不需要 uni-app 编译。
- 配置真实小程序 AppID；后端环境变量 `WECHAT_APP_ID` 必须与其一致，`WECHAT_APP_SECRET` 只在后端设置。
- 微信登录：`wx.login` 获取一次性 code → `POST /api/v1/auth/wechat-login` → 后端调用微信 `jscode2session` → 返回平台 JWT。openid、session_key 和 AppSecret 不下发客户端。
- 原生请求地址在 `miniapp/native/config.js` 配置。开发工具可使用本机地址；真机必须使用可达地址，上线需配置已备案 HTTPS request 合法域名。
- 默认及 local/docker profile 启动时执行 `db/migrations/20261112_wechat_login.sql`，将手机号改为可空并创建 `t_wechat_identity`；不会删除或重置现有用户。
- 微信登录不会自动得到手机号，也不会自动合并现有手机号账号；短信登录仍独立保留。未配置微信凭据时接口明确返回配置错误，不使用模拟 openid 冒充登录成功。
- 详见 `miniapp` 下迁移说明；旧 `src/` 仅用于迁移追溯。

后端启动前设置（不要将 AppSecret 写到前端）：

```bash
export WECHAT_APP_ID="你的小程序AppID"
export WECHAT_APP_SECRET="你的小程序AppSecret"
mvn -f backend/pom.xml spring-boot:run
```

IDEA 在 Run Configuration 的 Environment variables 中设置以上变量。Docker Compose 使用项目根 `.env`，模板已包含这两个变量。小程序 AppID 在开发者工具项目配置中填写。游客模式只能查看页面，不能验证真实微信登录。

测试命令：`mvn -f backend/pom.xml test`。微信登录回归测试使用 Mock 微信服务和 H2 内存库，不会修改远程业务数据；真实授权需配置凭据并在微信开发者工具/真机验证。

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
