-- 接送和代驾是独立平台接单授权，旧骑手默认关闭；不更新已有授权及任何订单账务。
SET @rider_capability_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_rider ADD COLUMN ride_enabled TINYINT NOT NULL DEFAULT 0', 'SELECT 1')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_rider' AND column_name='ride_enabled');
PREPARE rider_capability_stmt FROM @rider_capability_sql;
EXECUTE rider_capability_stmt;
DEALLOCATE PREPARE rider_capability_stmt;

SET @rider_capability_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_rider ADD COLUMN driving_enabled TINYINT NOT NULL DEFAULT 0', 'SELECT 1')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_rider' AND column_name='driving_enabled');
PREPARE rider_capability_stmt FROM @rider_capability_sql;
EXECUTE rider_capability_stmt;
DEALLOCATE PREPARE rider_capability_stmt;
