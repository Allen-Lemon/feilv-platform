-- 将新增自定义任务改为用户定价的线上/上门/兼职服务；原任务保留DELIVERY，不改历史订单或支付金额。
SET @task_service_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_custom_task ADD COLUMN task_mode VARCHAR(16) NOT NULL DEFAULT ''DELIVERY''', 'SELECT 1')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_custom_task' AND column_name='task_mode');
PREPARE task_service_stmt FROM @task_service_sql;
EXECUTE task_service_stmt;
DEALLOCATE PREPARE task_service_stmt;

SET @task_service_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_custom_task ADD COLUMN price_fen INT NULL DEFAULT NULL', 'SELECT 1')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_custom_task' AND column_name='price_fen');
PREPARE task_service_stmt FROM @task_service_sql;
EXECUTE task_service_stmt;
DEALLOCATE PREPARE task_service_stmt;

SET @task_service_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_custom_task ADD COLUMN contact VARCHAR(32) NULL DEFAULT NULL', 'SELECT 1')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_custom_task' AND column_name='contact');
PREPARE task_service_stmt FROM @task_service_sql;
EXECUTE task_service_stmt;
DEALLOCATE PREPARE task_service_stmt;

SET @task_service_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_custom_task ADD COLUMN phone VARCHAR(20) NULL DEFAULT NULL', 'SELECT 1')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_custom_task' AND column_name='phone');
PREPARE task_service_stmt FROM @task_service_sql;
EXECUTE task_service_stmt;
DEALLOCATE PREPARE task_service_stmt;

SET @task_service_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_custom_task ADD COLUMN location_address VARCHAR(255) NULL DEFAULT NULL', 'SELECT 1')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_custom_task' AND column_name='location_address');
PREPARE task_service_stmt FROM @task_service_sql;
EXECUTE task_service_stmt;
DEALLOCATE PREPARE task_service_stmt;

SET @task_service_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_custom_task ADD COLUMN service_time VARCHAR(100) NULL DEFAULT NULL', 'SELECT 1')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_custom_task' AND column_name='service_time');
PREPARE task_service_stmt FROM @task_service_sql;
EXECUTE task_service_stmt;
DEALLOCATE PREPARE task_service_stmt;

SET @task_service_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_custom_task ADD CONSTRAINT chk_custom_task_price CHECK (price_fen IS NULL OR (price_fen>=1 AND price_fen<=100000000))', 'SELECT 1')
  FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='t_custom_task' AND constraint_name='chk_custom_task_price');
PREPARE task_service_stmt FROM @task_service_sql;
EXECUTE task_service_stmt;
DEALLOCATE PREPARE task_service_stmt;
