-- 微信参数管理：新增独立表，不修改既有订单/用户结构。不写入任何真实密钥。
CREATE TABLE IF NOT EXISTS t_wechat_config (
  section VARCHAR(16) PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  app_id VARCHAR(64) DEFAULT '',
  mch_id VARCHAR(32) DEFAULT '',
  merchant_serial_no VARCHAR(64) DEFAULT '',
  notify_url VARCHAR(1024) DEFAULT '',
  refund_notify_url VARCHAR(1024) DEFAULT '',
  platform_public_key_id VARCHAR(128) DEFAULT '',
  app_secret_encrypted TEXT,
  api_v3_key_encrypted TEXT,
  merchant_private_key_encrypted TEXT,
  platform_public_key_encrypted TEXT,
  updated_by BIGINT,
  updated_time DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信登录与支付参数，机密字段加密存储';

INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'wechat-settings', '微信配置', '/wechat-settings', 0, 85, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'wechat-settings');

INSERT INTO t_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM t_admin_role r CROSS JOIN t_admin_menu m
WHERE r.code = 'SUPER_ADMIN' AND m.code = 'wechat-settings'
  AND NOT EXISTS (SELECT 1 FROM t_admin_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);
