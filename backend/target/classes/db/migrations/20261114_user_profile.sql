-- MySQL 8 幂等迁移：已有用户保留，头像为空时客户端使用默认头像。
SET @profile_migration_sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_user ADD COLUMN avatar_url VARCHAR(255) NULL DEFAULT NULL AFTER nickname',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_user' AND column_name = 'avatar_url');
PREPARE profile_migration_stmt FROM @profile_migration_sql;
EXECUTE profile_migration_stmt;
DEALLOCATE PREPARE profile_migration_stmt;

INSERT INTO t_admin_menu (code, name, path, parent_id, sort, enabled)
SELECT 'users', '用户管理', '/users', 0, 15, 1
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu WHERE code = 'users');

INSERT INTO t_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM t_admin_role r CROSS JOIN t_admin_menu m
WHERE r.code = 'SUPER_ADMIN' AND m.code = 'users'
  AND NOT EXISTS (SELECT 1 FROM t_admin_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);
