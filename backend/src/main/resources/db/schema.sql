-- =====================================================
-- 德江县"飞毛腿"城市同城跑腿平台 数据库初始化脚本
-- 对应《功能详细设计说明书》第九章核心数据实体设计
-- 金额单位：分；时间：datetime；引擎 InnoDB；字符集 utf8mb4
-- =====================================================

CREATE TABLE IF NOT EXISTS t_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  mobile        VARCHAR(20) DEFAULT NULL,
  nickname      VARCHAR(32) DEFAULT '',
  avatar_url    VARCHAR(255) DEFAULT NULL,
  role          VARCHAR(16) NOT NULL DEFAULT 'USER',   -- USER/RIDER/ADMIN
  status        TINYINT NOT NULL DEFAULT 1,            -- 1正常 0冻结
  created_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_mobile (mobile)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端用户/骑手主档';

CREATE TABLE IF NOT EXISTS t_user_address (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  contact    VARCHAR(32) NOT NULL,
  phone      VARCHAR(20) NOT NULL,
  poi_name   VARCHAR(128) DEFAULT '',
  address    VARCHAR(255) NOT NULL,
  province   VARCHAR(64) DEFAULT NULL,
  city       VARCHAR(64) DEFAULT NULL,
  district   VARCHAR(64) DEFAULT NULL,
  detail_address VARCHAR(160) DEFAULT NULL,
  lng        DECIMAL(10,6) DEFAULT 0,
  lat        DECIMAL(10,6) DEFAULT 0,
  is_default TINYINT DEFAULT 0,
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户地址簿（容量≥50条）';

CREATE TABLE IF NOT EXISTS t_rider (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT NOT NULL UNIQUE,
  name          VARCHAR(32) NOT NULL,
  id_card_enc   VARCHAR(128) DEFAULT '',               -- 生产须加密存储，此处演示
  vehicle_type  VARCHAR(16) DEFAULT 'E-BIKE',
  plate_no      VARCHAR(16) DEFAULT '',
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/FROZEN
  online_status TINYINT DEFAULT 0,                     -- 0下线 1抢单 2听单
  credit_score  INT NOT NULL DEFAULT 100,              -- 信用分（初始100）
  created_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_rider_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='骑手档案';

CREATE TABLE IF NOT EXISTS t_merchant (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNIQUE,
  shop_name    VARCHAR(64) NOT NULL,
  category     VARCHAR(32) DEFAULT '',
  address      VARCHAR(255) DEFAULT '',
  status       VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/APPROVED/FROZEN
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户主档';

CREATE TABLE IF NOT EXISTS t_order (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no      VARCHAR(32) NOT NULL UNIQUE,           -- 全局唯一可读订单号
  order_type    VARCHAR(8) NOT NULL,                   -- SEND送/BUY买/ERRAND办/CUSTOM自定义
  user_id       BIGINT NOT NULL,
  rider_id      BIGINT,
  from_contact  VARCHAR(32) NOT NULL,
  from_phone    VARCHAR(20) NOT NULL,
  from_address  VARCHAR(255) NOT NULL,
  from_lng      DECIMAL(10,6) DEFAULT 0,
  from_lat      DECIMAL(10,6) DEFAULT 0,
  to_contact    VARCHAR(32) NOT NULL,
  to_phone      VARCHAR(20) NOT NULL,
  to_address    VARCHAR(255) NOT NULL,
  to_lng        DECIMAL(10,6) DEFAULT 0,
  to_lat        DECIMAL(10,6) DEFAULT 0,
  distance_m    INT DEFAULT 0,
  weight_kg     INT DEFAULT 1,
  goods_desc    VARCHAR(128) DEFAULT '',
  remark        VARCHAR(255) DEFAULT '',
  estimate_fee  INT NOT NULL DEFAULT 0,                -- 预估费用（分）
  real_fee      INT NOT NULL DEFAULT 0,                -- 实收（分）
  status        VARCHAR(16) NOT NULL DEFAULT 'WAIT_PAY',
  pickup_code   VARCHAR(8) DEFAULT '',                 -- 取件码
  accepted_time DATETIME,
  delivered_time DATETIME,
  finish_time   DATETIME,
  created_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_user (user_id), KEY idx_rider (rider_id), KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表（保留≥3年）';

CREATE TABLE IF NOT EXISTS t_task_tag (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务标签';

CREATE TABLE IF NOT EXISTS t_custom_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  title VARCHAR(64) NOT NULL,
  content VARCHAR(1000) NOT NULL,
  publish_source VARCHAR(16) NOT NULL,
  created_by BIGINT NOT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_custom_task_order (order_id),
  KEY idx_custom_task_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自定义任务';

CREATE TABLE IF NOT EXISTS t_custom_task_tag (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  tag_name VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_custom_task_tag (task_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务标签快照';

CREATE TABLE IF NOT EXISTS t_complaint_deduction (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  complaint_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  rider_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  amount_fen INT NOT NULL,
  channel VARCHAR(16) NOT NULL DEFAULT 'MOCK',
  created_by BIGINT NOT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_complaint_deduction_complaint (complaint_id),
  KEY idx_complaint_deduction_order (order_id),
  KEY idx_complaint_deduction_rider (rider_id),
  CONSTRAINT chk_complaint_deduction_amount CHECK (amount_fen > 0),
  CONSTRAINT chk_complaint_deduction_channel CHECK (channel = 'MOCK')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉扣款赔付模拟账本';

CREATE TABLE IF NOT EXISTS t_wallet_account (
  user_id BIGINT PRIMARY KEY,
  available_fen BIGINT NOT NULL DEFAULT 0,
  frozen_fen BIGINT NOT NULL DEFAULT 0,
  debt_fen BIGINT NOT NULL DEFAULT 0,
  total_income_fen BIGINT NOT NULL DEFAULT 0,
  total_withdrawn_fen BIGINT NOT NULL DEFAULT 0,
  created_time DATETIME(6) NOT NULL,
  updated_time DATETIME(6) NOT NULL,
  CONSTRAINT chk_wallet_account_balances CHECK (available_fen>=0 AND frozen_fen>=0 AND debt_fen>=0 AND total_income_fen>=0 AND total_withdrawn_fen>=0 AND (available_fen=0 OR debt_fen=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户钱包MOCK余额';

CREATE TABLE IF NOT EXISTS t_wallet_entry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  business_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  type VARCHAR(32) NOT NULL,
  amount_fen BIGINT NOT NULL,
  available_after_fen BIGINT NOT NULL,
  frozen_after_fen BIGINT NOT NULL,
  debt_after_fen BIGINT NOT NULL,
  order_id BIGINT DEFAULT NULL,
  complaint_id BIGINT DEFAULT NULL,
  withdrawal_id BIGINT DEFAULT NULL,
  remark VARCHAR(255) NOT NULL,
  created_time DATETIME(6) NOT NULL,
  UNIQUE KEY uk_wallet_entry_business (business_key),
  KEY idx_wallet_entry_user_id (user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包不可变MOCK流水';

CREATE TABLE IF NOT EXISTS t_wallet_order_settlement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  rider_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  gross_fen BIGINT NOT NULL,
  deducted_fen BIGINT NOT NULL,
  net_fen BIGINT NOT NULL,
  channel VARCHAR(16) NOT NULL DEFAULT 'MOCK',
  created_time DATETIME(6) NOT NULL,
  UNIQUE KEY uk_wallet_settlement_order (order_id),
  CONSTRAINT chk_wallet_settlement_amount CHECK (gross_fen>=0 AND deducted_fen>=0 AND net_fen>=0 AND gross_fen=deducted_fen+net_fen),
  CONSTRAINT chk_wallet_settlement_channel CHECK (channel='MOCK')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每单仅一次确认完成入账凭据';

CREATE TABLE IF NOT EXISTS t_wallet_complaint_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  complaint_id BIGINT NOT NULL,
  deduction_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  rider_id BIGINT NOT NULL,
  rider_user_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  amount_fen BIGINT NOT NULL,
  created_by BIGINT NOT NULL,
  rider_debited BOOLEAN NOT NULL,
  created_time DATETIME(6) NOT NULL,
  UNIQUE KEY uk_wallet_receipt_complaint (complaint_id),
  UNIQUE KEY uk_wallet_receipt_deduction (deduction_id),
  CONSTRAINT chk_wallet_receipt_amount CHECK (amount_fen>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉钱包扣赔防重复凭据';

CREATE TABLE IF NOT EXISTS t_wallet_withdrawal (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  amount_fen BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  reason VARCHAR(255) DEFAULT NULL,
  reviewed_by BIGINT DEFAULT NULL,
  reviewed_time DATETIME(6) DEFAULT NULL,
  channel VARCHAR(16) NOT NULL DEFAULT 'MOCK',
  created_time DATETIME(6) NOT NULL,
  UNIQUE KEY uk_wallet_withdrawal_request (user_id,request_id),
  KEY idx_wallet_withdrawal_user_id (user_id,id),
  KEY idx_wallet_withdrawal_status_id (status,id),
  CONSTRAINT chk_wallet_withdrawal_amount CHECK (amount_fen>=1 AND amount_fen<=100000000),
  CONSTRAINT chk_wallet_withdrawal_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  CONSTRAINT chk_wallet_withdrawal_channel CHECK (channel='MOCK')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现冻结审核MOCK';

CREATE TABLE IF NOT EXISTS t_order_status_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id      BIGINT NOT NULL,
  from_status   VARCHAR(16),
  to_status     VARCHAR(16) NOT NULL,
  operator_type VARCHAR(16) NOT NULL,                   -- USER/RIDER/SYSTEM/ADMIN
  operator_id   BIGINT,
  remark        VARCHAR(255) DEFAULT '',
  op_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态流转日志（保留≥3年）';

CREATE TABLE IF NOT EXISTS t_order_fee_detail (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id   BIGINT NOT NULL,
  fee_type   VARCHAR(16) NOT NULL,                     -- BASE/DISTANCE/WEIGHT/NIGHT
  amount     INT NOT NULL,
  KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单费用明细';

CREATE TABLE IF NOT EXISTS t_pay_record (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  pay_no      VARCHAR(32) NOT NULL UNIQUE,
  order_id    BIGINT NOT NULL,
  channel     VARCHAR(16) NOT NULL DEFAULT 'MOCK',     -- 生产：WECHAT/ALIPAY，经持牌机构
  amount      INT NOT NULL,
  share_rider INT NOT NULL DEFAULT 0,                  -- 骑手分账（分）
  share_platform INT NOT NULL DEFAULT 0,               -- 平台分账（分）
  status      VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
  pay_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付流水（保留≥3年）';

CREATE TABLE IF NOT EXISTS t_rider_track (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id     BIGINT NOT NULL,
  rider_id     BIGINT NOT NULL,
  lng          DECIMAL(10,6),
  lat          DECIMAL(10,6),
  speed        DECIMAL(6,2) DEFAULT 0,
  report_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='骑手轨迹（保留≥90天）';

CREATE TABLE IF NOT EXISTS t_review (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id    BIGINT NOT NULL UNIQUE,                  -- 一单一评
  user_id     BIGINT NOT NULL,
  rider_id    BIGINT NOT NULL,
  stars       TINYINT NOT NULL,                        -- 1-5
  content     VARCHAR(255) DEFAULT '',
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价记录';

CREATE TABLE IF NOT EXISTS t_credit_log (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  rider_id     BIGINT NOT NULL,
  change_value INT NOT NULL,                           -- 正加负减
  reason_code  VARCHAR(32) NOT NULL,                   -- FIVE_STAR/BAD_REVIEW/PUNISH...
  ref_order_id BIGINT,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_rider (rider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用分变动（保留≥2年）';

CREATE TABLE IF NOT EXISTS t_complaint (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id     BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  type         VARCHAR(16) NOT NULL,                   -- LOST/DAMAGE/OVERTIME/ATTITUDE/OTHER
  description  VARCHAR(255) DEFAULT '',
  status       VARCHAR(16) NOT NULL DEFAULT 'OPEN',    -- OPEN/DONE
  result       VARCHAR(255) DEFAULT '',
  handled_by   BIGINT,
  handled_time DATETIME,
  sla_deadline DATETIME,
  compensation_fen INT NOT NULL DEFAULT 0,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_complaint_order_status (order_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉工单';

CREATE TABLE IF NOT EXISTS t_complaint_action (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  complaint_id  BIGINT NOT NULL,
  operator_id   BIGINT,
  action        VARCHAR(32) NOT NULL,
  detail        VARCHAR(255) DEFAULT '',
  created_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_complaint_action (complaint_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉处理记录';

CREATE TABLE IF NOT EXISTS t_ban_word (
  id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  word   VARCHAR(32) NOT NULL UNIQUE,
  level  TINYINT NOT NULL DEFAULT 1,                   -- 1禁发（拦截）2限发（提示）
  enabled TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='禁发/限发品类清单（管理后台可维护）';

CREATE TABLE IF NOT EXISTS t_op_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  operator_id BIGINT,
  action      VARCHAR(64) NOT NULL,
  detail      VARCHAR(255) DEFAULT '',
  op_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台操作日志（≥6个月）';

CREATE TABLE IF NOT EXISTS t_admin_account (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT NOT NULL UNIQUE,
  username      VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  enabled       TINYINT NOT NULL DEFAULT 1,
  created_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_admin_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台管理员账号';

CREATE TABLE IF NOT EXISTS t_admin_role (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  code         VARCHAR(32) NOT NULL UNIQUE,
  name         VARCHAR(64) NOT NULL,
  enabled      TINYINT NOT NULL DEFAULT 1,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台角色';

CREATE TABLE IF NOT EXISTS t_admin_menu (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  code         VARCHAR(32) NOT NULL,
  name         VARCHAR(64) NOT NULL,
  path         VARCHAR(128) DEFAULT '',
  parent_id    BIGINT NOT NULL DEFAULT 0,
  sort         INT NOT NULL DEFAULT 0,
  enabled      TINYINT NOT NULL DEFAULT 1,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_admin_menu_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台菜单与权限点';

CREATE TABLE IF NOT EXISTS t_admin_user_role (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  UNIQUE KEY uk_admin_user_role (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员角色关联';

CREATE TABLE IF NOT EXISTS t_admin_role_menu (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  UNIQUE KEY uk_admin_role_menu (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关联';

INSERT INTO t_user (mobile, nickname, role, status)
SELECT 'admin', '系统管理员', 'ADMIN', 1
WHERE NOT EXISTS (SELECT 1 FROM t_user WHERE mobile = 'admin');

INSERT INTO t_admin_account (user_id, username, password_hash, enabled)
SELECT u.id, 'admin', 'ac0e7d037817094e9e0b4441f9bae3209d67b02fa484917065f71b16109a1a78', 1
FROM t_user u
WHERE u.mobile = 'admin'
  AND NOT EXISTS (SELECT 1 FROM t_admin_account WHERE username = 'admin');

INSERT INTO t_admin_role (code, name, enabled)
SELECT 'SUPER_ADMIN', '超级管理员', 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_role WHERE code = 'SUPER_ADMIN');

INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'dashboard', '经营看板', '/dashboard', 0, 10, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'dashboard');
INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'orders', '订单管理', '/orders', 0, 20, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'orders');
INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'riders', '运力管理', '/riders', 0, 30, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'riders');
INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'settlements', '结算中心', '/settlements', 0, 40, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'settlements');
INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'marketing', '营销管理', '/marketing', 0, 50, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'marketing');
INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'content', '内容与客服', '/content', 0, 60, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'content');
INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'risk', '风控与合规', '/risk', 0, 70, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'risk');
INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'system', '系统管理', '/system', 0, 80, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'system');

INSERT INTO t_admin_user_role (user_id, role_id)
SELECT u.id, r.id FROM t_user u CROSS JOIN t_admin_role r
WHERE u.mobile = 'admin' AND r.code = 'SUPER_ADMIN'
  AND NOT EXISTS (SELECT 1 FROM t_admin_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO t_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM t_admin_role r CROSS JOIN t_admin_menu m
WHERE r.code = 'SUPER_ADMIN'
  AND NOT EXISTS (SELECT 1 FROM t_admin_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);
