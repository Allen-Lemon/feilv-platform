-- 手机号换绑只修改当前用户mobile，无结构变更；订单转派新增审计记录，不重写历史骑手/支付/钱包。
CREATE TABLE IF NOT EXISTS t_order_transfer (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  from_rider_id BIGINT NOT NULL,
  to_rider_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  reason VARCHAR(255) NOT NULL,
  response_reason VARCHAR(255) DEFAULT NULL,
  created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  handled_time DATETIME(6) DEFAULT NULL,
  KEY idx_transfer_order_status (order_id,status,id),
  KEY idx_transfer_from_status (from_rider_id,status,id),
  KEY idx_transfer_to_status (to_rider_id,status,id),
  CONSTRAINT chk_order_transfer_parties CHECK (from_rider_id<>to_rider_id),
  CONSTRAINT chk_order_transfer_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED','CANCELED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='骑手未开始订单转派，接收者确认后生效';
