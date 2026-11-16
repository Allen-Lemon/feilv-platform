-- 钱包仅MOCK模拟账务，新增结构不回填历史已完成订单/投诉，不修改原支付流水。
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户钱包MOCK余额，欠款不挪用提现冻结';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包不可变MOCK流水，业务键防重复记账';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉骑手扣款和发布者钱包赔付防重复凭据';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户提现冻结审核MOCK，不触发真实出款';

INSERT INTO t_admin_menu (code,name,path,parent_id,sort,enabled)
SELECT 'wallets','用户钱包','/wallets',0,55,1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code='wallets');
INSERT INTO t_admin_menu (code,name,path,parent_id,sort,enabled)
SELECT 'withdrawals','提现审核','/withdrawals',0,56,1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code='withdrawals');
INSERT INTO t_admin_role_menu (role_id,menu_id)
SELECT r.id,m.id FROM t_admin_role r CROSS JOIN t_admin_menu m
WHERE r.code='SUPER_ADMIN' AND m.code IN ('wallets','withdrawals')
  AND NOT EXISTS (SELECT 1 FROM t_admin_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id);
