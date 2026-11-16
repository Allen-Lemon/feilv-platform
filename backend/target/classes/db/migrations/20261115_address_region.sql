-- MySQL 8 幂等迁移：仅追加可空结构化地址列，历史 address 保持原样，不猜测行政区。
SET @address_region_migration_sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_user_address ADD COLUMN province VARCHAR(64) NULL DEFAULT NULL AFTER address',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_user_address' AND column_name = 'province');
PREPARE address_region_migration_stmt FROM @address_region_migration_sql;
EXECUTE address_region_migration_stmt;
DEALLOCATE PREPARE address_region_migration_stmt;

SET @address_region_migration_sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_user_address ADD COLUMN city VARCHAR(64) NULL DEFAULT NULL AFTER province',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_user_address' AND column_name = 'city');
PREPARE address_region_migration_stmt FROM @address_region_migration_sql;
EXECUTE address_region_migration_stmt;
DEALLOCATE PREPARE address_region_migration_stmt;

SET @address_region_migration_sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_user_address ADD COLUMN district VARCHAR(64) NULL DEFAULT NULL AFTER city',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_user_address' AND column_name = 'district');
PREPARE address_region_migration_stmt FROM @address_region_migration_sql;
EXECUTE address_region_migration_stmt;
DEALLOCATE PREPARE address_region_migration_stmt;

SET @address_region_migration_sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_user_address ADD COLUMN detail_address VARCHAR(160) NULL DEFAULT NULL AFTER district',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_user_address' AND column_name = 'detail_address');
PREPARE address_region_migration_stmt FROM @address_region_migration_sql;
EXECUTE address_region_migration_stmt;
DEALLOCATE PREPARE address_region_migration_stmt;
