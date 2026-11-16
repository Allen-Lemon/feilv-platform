-- 商品预算仅供采购参考，另行结算；旧订单保持NULL，不改任何历史支付和服务费。
SET @budget_tag_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_order ADD COLUMN goods_budget_fen INT NULL DEFAULT NULL', 'SELECT 1')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_order' AND column_name='goods_budget_fen');
PREPARE budget_tag_stmt FROM @budget_tag_sql;
EXECUTE budget_tag_stmt;
DEALLOCATE PREPARE budget_tag_stmt;

SET @budget_tag_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_order ADD CONSTRAINT chk_order_goods_budget CHECK (goods_budget_fen IS NULL OR (goods_budget_fen>=1 AND goods_budget_fen<=100000000))', 'SELECT 1')
  FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='t_order' AND constraint_name='chk_order_goods_budget');
PREPARE budget_tag_stmt FROM @budget_tag_sql;
EXECUTE budget_tag_stmt;
DEALLOCATE PREPARE budget_tag_stmt;

-- 标签由后台明确配置一个或多个场景，旧标签待分类，不按名称猜测或回填。
SET @budget_tag_sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE t_task_tag ADD COLUMN task_modes VARCHAR(64) NULL DEFAULT NULL', 'SELECT 1')
  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_task_tag' AND column_name='task_modes');
PREPARE budget_tag_stmt FROM @budget_tag_sql;
EXECUTE budget_tag_stmt;
DEALLOCATE PREPARE budget_tag_stmt;
