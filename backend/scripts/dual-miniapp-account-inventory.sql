-- 双小程序切换前账号盘点（MySQL 8，只读，不是迁移/恢复脚本）。
-- 仅由获授权人员在隔离副本或已批准的只读连接上运行；本次开发不会执行此文件。
-- 前提：已有 t_wechat_identity、钱包和转派相关历史迁移。缺表时应停止，不建表补数据。
-- 不输出手机号、姓名、地址、openid、unionid、密钥、登录票据或关联码。
-- 同一手机号/unionid 不构成账号合并证明；任何结果都不能直接用于自动恢复账号。
-- 大表请先 EXPLAIN 并评估只读快照时长；结果也属于内部账户/账务资料，不应公开。

START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

-- 1. 现存微信身份按 AppID 分布。与两端实际配置核对，AppID 不是身份归属证明。
SELECT app_id, COUNT(*) AS identity_count, COUNT(DISTINCT user_id) AS platform_account_count
FROM t_wechat_identity
GROUP BY app_id
ORDER BY app_id;

-- 2. 骑手账号风险概览。NO_WECHAT_IDENTITY 是不能自助签发关联码的重点核验项；
--    HAS_WECHAT_IDENTITY 仅表示存在映射，不保证属于当前配置AppID或仍可登录。
SELECT u.role AS account_role, u.status AS account_status, r.status AS rider_status,
       CASE WHEN COALESCE(wi.identity_count, 0) = 0
            THEN 'NO_WECHAT_IDENTITY' ELSE 'HAS_WECHAT_IDENTITY' END AS identity_state,
       COUNT(*) AS rider_count
FROM t_rider r
JOIN t_user u ON u.id = r.user_id
LEFT JOIN (SELECT user_id, COUNT(*) AS identity_count FROM t_wechat_identity GROUP BY user_id) wi
       ON wi.user_id = u.id
GROUP BY u.role, u.status, r.status, identity_state
ORDER BY account_role, account_status, rider_status, identity_state;

-- 3. 无微信身份、冻结或角色不一致的骑手与其原主体账务/未决业务。
--    不关联其他用户、不更改归属、不移动余额；无钱包行按0展示不是补建钱包。
SELECT u.id AS user_id, r.id AS rider_id, u.role AS account_role,
       u.status AS account_status, r.status AS rider_status,
       COALESCE(wi.identity_count, 0) AS identity_count,
       r.ride_enabled, r.driving_enabled,
       COALESCE(wa.available_fen, 0) AS available_fen,
       COALESCE(wa.frozen_fen, 0) AS frozen_fen,
       COALESCE(wa.debt_fen, 0) AS debt_fen,
       COALESCE(wo.pending_count, 0) AS pending_withdrawal_count,
       COALESCE(wo.pending_fen, 0) AS pending_withdrawal_fen,
       COALESCE(ro.active_count, 0) AS assigned_unfinished_order_count,
       COALESCE(uo.active_count, 0) AS published_unfinished_order_count,
       COALESCE(tr.pending_count, 0) AS pending_transfer_count
FROM t_rider r
JOIN t_user u ON u.id = r.user_id
LEFT JOIN (SELECT user_id, COUNT(*) AS identity_count FROM t_wechat_identity GROUP BY user_id) wi
       ON wi.user_id = u.id
LEFT JOIN t_wallet_account wa ON wa.user_id = u.id
LEFT JOIN (SELECT user_id, COUNT(*) AS pending_count, SUM(amount_fen) AS pending_fen
           FROM t_wallet_withdrawal WHERE status = 'PENDING' GROUP BY user_id) wo
       ON wo.user_id = u.id
LEFT JOIN (SELECT rider_id, COUNT(*) AS active_count FROM t_order
           WHERE status IN ('WAIT_PAY','WAIT_GRAB','ACCEPTED','PICKING','DELIVERING','DELIVERED')
           GROUP BY rider_id) ro ON ro.rider_id = r.id
LEFT JOIN (SELECT user_id, COUNT(*) AS active_count FROM t_order
           WHERE status IN ('WAIT_PAY','WAIT_GRAB','ACCEPTED','PICKING','DELIVERING','DELIVERED')
           GROUP BY user_id) uo ON uo.user_id = u.id
LEFT JOIN (SELECT rider_id, COUNT(*) AS pending_count FROM (
             SELECT from_rider_id AS rider_id FROM t_order_transfer WHERE status = 'PENDING'
             UNION ALL
             SELECT to_rider_id AS rider_id FROM t_order_transfer WHERE status = 'PENDING'
           ) pending_transfers GROUP BY rider_id) tr ON tr.rider_id = r.id
WHERE COALESCE(wi.identity_count, 0) = 0 OR u.role <> 'RIDER' OR u.status <> 1 OR r.status <> 'ACTIVE'
ORDER BY u.id, r.id;

-- 4. 角色为骑手但缺档案的主体。不得通过重新入驻绕过历史冻结/账务异常。
SELECT u.id AS user_id, u.status AS account_status
FROM t_user u LEFT JOIN t_rider r ON r.user_id = u.id
WHERE u.role = 'RIDER' AND r.id IS NULL
ORDER BY u.id;

-- 5. 孤立身份或同一主体同一AppID的多身份，仅报告，不删除/重归属。
SELECT wi.id AS identity_id, wi.user_id, wi.app_id
FROM t_wechat_identity wi LEFT JOIN t_user u ON u.id = wi.user_id
WHERE u.id IS NULL
ORDER BY wi.id;

SELECT user_id, app_id, COUNT(*) AS identity_count
FROM t_wechat_identity
GROUP BY user_id, app_id
HAVING COUNT(*) > 1
ORDER BY user_id, app_id;

-- 6. 全平台未决账务/订单统计，维护窗口前另须与客户端“结果待核实”标记核对。
--    服务器无法从数据库推断客户端是否丢失某次响应；不要据此重新提交订单或提现。
SELECT status, channel, COUNT(*) AS withdrawal_count, SUM(amount_fen) AS amount_fen
FROM t_wallet_withdrawal WHERE status = 'PENDING'
GROUP BY status, channel;

SELECT status, COUNT(*) AS order_count
FROM t_order
WHERE status IN ('WAIT_PAY','WAIT_GRAB','ACCEPTED','PICKING','DELIVERING','DELIVERED')
GROUP BY status
ORDER BY status;

COMMIT;
