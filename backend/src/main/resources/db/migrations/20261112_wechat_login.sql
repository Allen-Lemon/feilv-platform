-- 微信原生小程序登录迁移。MySQL 8；可重复执行；保留现有用户及手机号唯一索引。
-- 微信首次登录没有手机号，不伪造号码，也不通过昵称/客户端 openid 合并账号。
SET @wechat_migration_sql = (SELECT IF(COUNT(*) = 1,
  'ALTER TABLE t_user MODIFY COLUMN mobile VARCHAR(20) NULL DEFAULT NULL',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_user'
    AND column_name = 'mobile' AND is_nullable = 'NO');
PREPARE wechat_migration_stmt FROM @wechat_migration_sql;
EXECUTE wechat_migration_stmt;
DEALLOCATE PREPARE wechat_migration_stmt;

CREATE TABLE IF NOT EXISTS t_wechat_identity (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_id VARCHAR(64) NOT NULL,
  openid VARCHAR(128) NOT NULL,
  unionid VARCHAR(128) DEFAULT NULL,
  user_id BIGINT NOT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wechat_app_openid (app_id, openid),
  KEY idx_wechat_user (user_id),
  CONSTRAINT fk_wechat_user FOREIGN KEY (user_id) REFERENCES t_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信身份与平台用户映射';
