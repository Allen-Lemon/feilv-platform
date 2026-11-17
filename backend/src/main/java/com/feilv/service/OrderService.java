package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.OrderFeeDetail;
import com.feilv.domain.OrderMain;
import com.feilv.domain.OrderStatusLog;
import com.feilv.dto.ApiDtos.OrderCreateReq;
import com.feilv.dto.ApiDtos.OrderVO;
import com.feilv.dto.ApiDtos.QuoteReq;
import com.feilv.dto.ApiDtos.QuoteVO;
import com.feilv.enums.Enums.OrderType;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.OrderFeeDetailRepository;
import com.feilv.repository.OrderRepository;
import com.feilv.repository.OrderStatusLogRepository;
import com.feilv.repository.RiderRepository;
import com.feilv.util.BizNoUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单域（对应功能设计文档 4.2/8.1）：
 * 订单创建、状态机统一流转入口、取消与确认、查询。
 * 全平台状态变更必须经 changeStatus()，禁止任何地方直接 set status。
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderStatusLogRepository statusLogRepository;
    private final OrderFeeDetailRepository feeDetailRepository;
    private final PricingService pricingService;
    private final BanWordService banWordService;
    private final MessageService messageService;
    private final RiderRepository riderRepository;
    private final UserProfileService userProfileService;
    private final WalletAccountingService walletAccountingService;
    private final com.feilv.repository.CustomTaskRepository customTaskRepository;
    private final com.feilv.repository.PayRecordRepository payRecordRepository;

    @org.springframework.beans.factory.annotation.Value("${feilv.share.rider-percent}")
    private int riderPercent;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    // ---------- 计价 ----------
    public QuoteVO quote(QuoteReq req) {
        if (OrderType.of(req.orderType()) == OrderType.CUSTOM) throw BizException.of(400, "请使用自定义任务计价接口");
        return pricingService.quote(req.distanceM(), req.weightKg());
    }

    // ---------- 下单（详细设计4.2：品类拦截→计价→建档） ----------
    @Transactional
    public OrderVO create(long userId, OrderCreateReq req) {
        if (OrderType.of(req.orderType()) == OrderType.CUSTOM) throw BizException.of(400, "请使用自定义任务发布接口");
        return createOrder(userId, req);
    }

    /** 旧签名仅保留源码兼容，不允许绕过任务元数据与必填发布价。 */
    @Deprecated
    public OrderVO createCustom(long userId, OrderCreateReq req) {
        throw BizException.of(400, "请使用自定义任务发布接口并填写发布价");
    }

    /** 包内专用：只能由CustomTaskService传入已验证的服务任务总价，外部API不绑定此方法。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    OrderVO createCustom(long userId, OrderCreateReq req, int priceFen) {
        if (OrderType.of(req.orderType()) != OrderType.CUSTOM || priceFen < 1 || priceFen > 100000000) {
            throw BizException.of(400, "自定义任务类型或发布价不合法");
        }
        return createOrder(userId, req, new QuoteVO(priceFen, List.of(
                new com.feilv.dto.ApiDtos.FeeItem("CUSTOM", "发布者自定任务总价", priceFen))), true);
    }

    private OrderVO createOrder(long userId, OrderCreateReq req) {
        return createOrder(userId, req, null, false);
    }

    private OrderVO createOrder(long userId, OrderCreateReq req, QuoteVO manualQuote, boolean serviceTask) {
        userProfileService.requireBoundMobile(userId);
        OrderType type = OrderType.of(req.orderType());
        validateGoodsBudget(type, req.goodsBudgetFen());
        banWordService.checkGoods(req.goodsDesc());   // 合规底线：禁发品类拦截

        QuoteVO q = manualQuote != null ? manualQuote : pricingService.quote(req.distanceM(), req.weightKg());
        OrderMain order = new OrderMain();
        order.setOrderNo(BizNoUtil.orderNo());
        order.setOrderType(type.name());
        order.setUserId(userId);
        order.setFromContact(req.fromContact());
        order.setFromPhone(req.fromPhone());
        order.setFromAddress(req.fromAddress());
        order.setFromLng(req.fromLng());
        order.setFromLat(req.fromLat());
        order.setToContact(req.toContact());
        order.setToPhone(req.toPhone());
        order.setToAddress(req.toAddress());
        order.setToLng(req.toLng());
        order.setToLat(req.toLat());
        order.setDistanceM(req.distanceM());
        order.setWeightKg(req.weightKg());
        order.setGoodsDesc(req.goodsDesc() == null ? "" : req.goodsDesc());
        order.setGoodsBudgetFen(req.goodsBudgetFen());
        order.setRemark(req.remark() == null ? "" : req.remark());
        order.setEstimateFee(q.totalFen());
        order.setRealFee(q.totalFen());
        order.setStatus(OrderStatus.WAIT_PAY);
        order.setPickupCode(serviceTask ? "" : BizNoUtil.pickupCode());
        order = orderRepository.save(order);

        for (var item : q.items()) {
            OrderFeeDetail d = new OrderFeeDetail();
            d.setOrderId(order.getId());
            d.setFeeType(item.feeType());
            d.setAmount(item.amountFen());
            feeDetailRepository.save(d);
        }
        saveLog(order.getId(), null, OrderStatus.WAIT_PAY, "USER", userId, "用户下单");
        return toVO(order);
    }

    /** 仅新建时校验预算；历史BUY空预算不影响查询、支付和履约。 */
    private static void validateGoodsBudget(OrderType type, Integer goodsBudgetFen) {
        if (type != OrderType.BUY) {
            if (goodsBudgetFen != null) throw BizException.of(400, "仅代购订单可填写商品预算");
            return;
        }
        if (goodsBudgetFen == null || goodsBudgetFen < 1 || goodsBudgetFen > 100000000) {
            throw BizException.of(400, "代购商品预算必须为1至100000000整数分");
        }
    }

    // ---------- 状态机统一流转入口（功能设计文档8.1：单一事实源） ----------
    @Transactional
    public OrderMain changeStatus(OrderMain order, OrderStatus to, String operatorType, Long operatorId, String remark) {
        // 所有履约入口共用订单锁；refresh确保并发完成/投诉等待后读取已提交状态，避免旧实体重复记账。
        OrderMain supplied = order;
        order = orderRepository.findByIdForUpdate(order.getId()).orElseThrow(() -> BizException.of(404, "订单不存在"));
        entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        OrderStatus from = order.getStatus();
        if (from == OrderStatus.FINISHED && to == OrderStatus.FINISHED) {
            // 已完成历史订单不在重复确认时补发钱包收入。
            syncStatus(supplied, order);
            return order;
        }
        if (!from.canTo(to)) {
            throw BizException.of(4090, "订单当前[" + from.label() + "]状态不允许流转到[" + to.label() + "]");
        }
        if (to == OrderStatus.DELIVERED) order.setDeliveredTime(LocalDateTime.now());
        if (to == OrderStatus.FINISHED) order.setFinishTime(LocalDateTime.now());
        order.setStatus(to);
        orderRepository.saveAndFlush(order);
        if (to == OrderStatus.FINISHED) walletAccountingService.settleFinishedOrder(order.getId());
        syncStatus(supplied, order);
        saveLog(order.getId(), from, to, operatorType, operatorId, remark);
        messageService.notifyStatusChange(order.getOrderNo(), from.label(), to.label(),
                "USER".equals(operatorType) ? "用户" : "骑手/平台");
        return order;
    }

    private void syncStatus(OrderMain supplied, OrderMain current) {
        if (supplied == current) return;
        supplied.setStatus(current.getStatus());
        supplied.setDeliveredTime(current.getDeliveredTime());
        supplied.setFinishTime(current.getFinishTime());
    }

    private void saveLog(Long orderId, OrderStatus from, OrderStatus to, String opType, Long opId, String remark) {
        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(orderId);
        log.setFromStatus(from == null ? null : from.name());
        log.setToStatus(to.name());
        log.setOperatorType(opType);
        log.setOperatorId(opId);
        log.setRemark(remark);
        statusLogRepository.save(log);
    }

    // ---------- 取消（详细设计4.5.3：接单前免费，接单后按规则） ----------
    @Transactional
    public OrderVO cancel(long userId, long orderId, String reason) {
        OrderMain order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> BizException.of(404, "订单不存在"));
        entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (order.getUserId() != userId) throw BizException.of(403, "无权操作他人订单");
        switch (order.getStatus()) {
            case WAIT_PAY -> messageService.notifyPush("用户", "取消成功，无需支付");
            case WAIT_GRAB -> messageService.notifyPush("用户", "已接单前取消，全额原路退款（模拟）");
            case ACCEPTED, PICKING -> messageService.notifyPush("用户", "已接单后取消，按规则收取起步价50%取消费");
            default -> throw BizException.of(4090, "配送中/已完成订单不可取消，请联系客服");
        }
        changeStatus(order, OrderStatus.CANCELED, "USER", userId, reason == null ? "用户取消" : reason);
        return toVO(order);
    }

    // ---------- 用户确认收货（DELIVERED→FINISHED，触发分账） ----------
    @Transactional
    public OrderVO confirm(long userId, long orderId) {
        OrderMain order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> BizException.of(404, "订单不存在"));
        entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (order.getUserId() != userId) throw BizException.of(403, "无权操作他人订单");
        if (order.getStatus() == OrderStatus.FINISHED) return toVO(order); // 幂等
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw BizException.of(4090, isServiceTask(order) ? "任务尚未提交完成，无法确认" : "订单尚未送达，无法确认收货");
        }
        changeStatus(order, OrderStatus.FINISHED, "USER", userId, isServiceTask(order) ? "发布者确认服务完成" : "用户确认收货");
        return toVO(order);
    }

    // ---------- 查询 ----------
    public Page<OrderVO> myList(long userId, int page, int size) {
        var result = orderRepository.findByUserId(userId, PageRequest.of(page, size));
        var tasks = taskIndex(result.getContent());
        return result.map(o -> toVO(o, taskMode(tasks.get(o.getId()))));
    }

    @Transactional(readOnly = true)
    public OrderVO detailForUser(long userId, long orderId) {
        OrderMain order = mustGet(orderId);
        if (!isOrderParticipant(userId, order)) {
            throw BizException.of(403, "无权查看该订单");
        }
        return toVO(order);
    }

    public boolean isOrderParticipant(long userId, OrderMain order) {
        if (order.getUserId() != null && order.getUserId().equals(userId)) return true;
        return order.getRiderId() != null
                && riderRepository.findByUserId(userId).map(r -> order.getRiderId().equals(r.getId())).orElse(false);
    }

    /** 供 DispatchService 抢单成功后补记流转日志与通知（状态已由条件更新改写） */
    @Transactional
    public void saveLogForGrab(OrderMain order) {
        saveLog(order.getId(), OrderStatus.WAIT_GRAB, OrderStatus.ACCEPTED,
                "RIDER", order.getRiderId(), "抢单成功（公平锁）");
        messageService.notifyStatusChange(order.getOrderNo(),
                OrderStatus.WAIT_GRAB.label(), OrderStatus.ACCEPTED.label(), "用户与骑手");
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    OrderMain lockForDispatch(long orderId) {
        var order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> BizException.of(404, "订单不存在"));
        entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return order;
    }

    public OrderMain mustGet(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> BizException.of(404, "订单不存在"));
    }

    /** 列表统一批量取任务元数据，普通订单和历史无元数据CUSTOM均视作DELIVERY。 */
    public java.util.Map<Long, com.feilv.domain.CustomTask> taskIndex(List<OrderMain> orders) {
        var ids = orders.stream().filter(o -> "CUSTOM".equals(o.getOrderType())).map(OrderMain::getId).toList();
        if (ids.isEmpty()) return java.util.Map.of();
        return customTaskRepository.findByOrderIdIn(ids).stream().collect(java.util.stream.Collectors.toMap(
                com.feilv.domain.CustomTask::getOrderId, java.util.function.Function.identity()));
    }

    public static String taskMode(com.feilv.domain.CustomTask task) {
        return task == null || task.getTaskMode() == null ? "DELIVERY" : task.getTaskMode();
    }

    public String serviceTaskMode(OrderMain order) {
        if (!"CUSTOM".equals(order.getOrderType())) return "DELIVERY";
        return taskMode(customTaskRepository.findByOrderId(order.getId()).orElse(null));
    }

    public boolean isServiceTask(OrderMain order) { return !"DELIVERY".equals(serviceTaskMode(order)); }

    public static String statusLabel(OrderStatus status, String taskMode) {
        if ("DELIVERY".equals(taskMode)) return status.label();
        return switch (status) {
            case PICKING -> "服务中";
            case DELIVERING -> "提交完成中";
            case DELIVERED -> "待确认完成";
            default -> status.label();
        };
    }

    /** long中间值保证百万元任务分账不溢出；所有预估使用同一配置。 */
    public int estimatedRiderShare(int amountFen) { return Math.toIntExact((long) amountFen * riderPercent / 100); }

    /** 支付后必须展示实付分账快照，不随后续配置变化而重算。 */
    public java.util.Map<Long, Integer> riderShares(List<OrderMain> orders) {
        if (orders.isEmpty()) return java.util.Map.of();
        var result = new java.util.HashMap<Long, Integer>();
        for (var order : orders) result.put(order.getId(), estimatedRiderShare(order.getEstimateFee()));
        result.putAll(paidRiderShares(orders));
        return result;
    }

    public java.util.Map<Long, Integer> paidRiderShares(List<OrderMain> orders) {
        if (orders.isEmpty()) return java.util.Map.of();
        var result = new java.util.HashMap<Long, Integer>();
        for (var row : payRecordRepository.sumSuccessfulMockShareByOrderIds(orders.stream().map(OrderMain::getId).toList())) {
            result.put(((Number) row[0]).longValue(), Math.toIntExact(((Number) row[1]).longValue()));
        }
        return result;
    }

    public OrderVO toVO(OrderMain o) { return toVO(o, serviceTaskMode(o)); }

    public OrderVO toVO(OrderMain o, String taskMode) {
        return toVO(o, taskMode, com.feilv.common.UserContext.get() != null && o.getUserId() != null
                && o.getUserId().equals(com.feilv.common.UserContext.get().id()));
    }

    /** Rider read routes must explicitly suppress owner capabilities, including historical self-orders. */
    OrderVO toVO(OrderMain o, String taskMode, boolean owner) {
        return new OrderVO(o.getId(), o.getOrderNo(), o.getOrderType(),
                o.getStatus().name(), statusLabel(o.getStatus(), taskMode),
                o.getFromAddress(), o.getToAddress(),
                o.getGoodsDesc(), o.getEstimateFee(), o.getRealFee(), o.getRiderId(),
                "DELIVERY".equals(taskMode) ? o.getPickupCode() : "",
                o.getCreatedTime() == null ? null : o.getCreatedTime().toString(),
                o.getAcceptedTime() == null ? null : o.getAcceptedTime().toString(),
                o.getDeliveredTime() == null ? null : o.getDeliveredTime().toString(),
                o.getFinishTime() == null ? null : o.getFinishTime().toString(),
                owner, taskMode, o.getGoodsBudgetFen());
    }
}
