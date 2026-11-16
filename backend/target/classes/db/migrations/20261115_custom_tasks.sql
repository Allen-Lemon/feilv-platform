-- 自定义任务、发布时标签快照、投诉模拟扣款账本。仅新增表，不改写历史支付或订单金额。
CREATE TABLE IF NOT EXISTS t_task_tag (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台配置任务标签';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自定义任务，关联原订单履约流程';

CREATE TABLE IF NOT EXISTS t_custom_task_tag (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  tag_name VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_custom_task_tag (task_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务发布时标签名称快照';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉审核骑手扣款及发布者赔付模拟账本';

SET @complaint_index_sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_complaint ADD INDEX idx_complaint_order_status (order_id, status)', 'SELECT 1')
  FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='t_complaint' AND index_name='idx_complaint_order_status');
PREPARE complaint_index_stmt FROM @complaint_index_sql;
EXECUTE complaint_index_stmt;
DEALLOCATE PREPARE complaint_index_stmt;

INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'tasks', '自定义任务', '/tasks', 0, 25, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'tasks');
INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'complaints', '投诉审核', '/complaints', 0, 65, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'complaints');

INSERT INTO t_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM t_admin_role r CROSS JOIN t_admin_menu m
WHERE r.code = 'SUPER_ADMIN' AND m.code IN ('tasks', 'complaints')
  AND NOT EXISTS (SELECT 1 FROM t_admin_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id);
