-- Additive, idempotent, identity-only migration. No existing account, identity, wallet or capability is moved.
CREATE TABLE IF NOT EXISTS t_miniapp_login_ticket (
  token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  purpose VARCHAR(32) NOT NULL DEFAULT 'IDENTITY_LOGIN',
  client VARCHAR(8) NOT NULL,
  app_id VARCHAR(64) NOT NULL,
  openid VARCHAR(128) NOT NULL,
  unionid VARCHAR(128) DEFAULT NULL,
  config_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  consumed BOOLEAN NOT NULL DEFAULT FALSE,
  failures INT NOT NULL DEFAULT 0,
  KEY idx_miniapp_ticket_expiry (expires_at),
  CONSTRAINT chk_miniapp_ticket_client CHECK (client IN ('USER','RIDER')),
  CONSTRAINT chk_miniapp_ticket_failures CHECK (failures >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短效微信登录身份票据（仅存哈希）';

CREATE TABLE IF NOT EXISTS t_miniapp_link_code (
  token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  purpose VARCHAR(32) NOT NULL DEFAULT 'LINK_IDENTITY',
  user_id BIGINT NOT NULL,
  source_client VARCHAR(8) NOT NULL,
  target_client VARCHAR(8) NOT NULL,
  source_app_id VARCHAR(64) NOT NULL,
  target_app_id VARCHAR(64) NOT NULL,
  source_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  consumed BOOLEAN NOT NULL DEFAULT FALSE,
  KEY idx_miniapp_link_expiry (expires_at),
  CONSTRAINT chk_miniapp_link_clients CHECK (source_client IN ('USER','RIDER') AND target_client IN ('USER','RIDER') AND source_client <> target_client)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双向短效账号关联凭证（仅存哈希）';
