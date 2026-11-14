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

    // ---------- 计价 ----------
    public QuoteVO quote(QuoteReq req) {
        OrderType.of(req.orderType());
        return pricingService.quote(req.distanceM(), req.weightKg());
    }

    // ---------- 下单（详细设计4.2：品类拦截→计价→建档） ----------
    @Transactional
    public OrderVO create(long userId, OrderCreateReq req) {
        userProfileService.requireBoundMobile(userId);
        OrderType type = OrderType.of(req.orderType());
        banWordService.checkGoods(req.goodsDesc());   // 合规底线：禁发品类拦截

        QuoteVO q = pricingService.quote(req.distanceM(), req.weightKg());
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
        order.setRemark(req.remark() == null ? "" : req.remark());
        order.setEstimateFee(q.totalFen());
        order.setRealFee(q.totalFen());
        order.setStatus(OrderStatus.WAIT_PAY);
        order.setPickupCode(BizNoUtil.pickupCode());
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

    // ---------- 状态机统一流转入口（功能设计文档8.1：单一事实源） ----------
    @Transactional
    public OrderMain changeStatus(OrderMain order, OrderStatus to, String operatorType, Long operatorId, String remark) {
        OrderStatus from = order.getStatus();
        if (!from.canTo(to)) {
            throw BizException.of(4090, "订单当前[" + from.label() + "]状态不允许流转到[" + to.label() + "]");
        }
        if (to == OrderStatus.DELIVERED) order.setDeliveredTime(LocalDateTime.now());
        if (to == OrderStatus.FINISHED) order.setFinishTime(LocalDateTime.now());
        order.setStatus(to);
        orderRepository.save(order);
        saveLog(order.getId(), from, to, operatorType, operatorId, remark);
        messageService.notifyStatusChange(order.getOrderNo(), from.label(), to.label(),
                "USER".equals(operatorType) ? "用户" : "骑手/平台");
        return order;
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
        OrderMain order = mustGet(orderId);
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
        OrderMain order = mustGet(orderId);
        if (order.getUserId() != userId) throw BizException.of(403, "无权操作他人订单");
        if (order.getStatus() == OrderStatus.FINISHED) return toVO(order); // 幂等
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw BizException.of(4090, "订单尚未送达，无法确认收货");
        }
        changeStatus(order, OrderStatus.FINISHED, "USER", userId, "用户确认收货");
        return toVO(order);
    }

    // ---------- 查询 ----------
    public Page<OrderVO> myList(long userId, int page, int size) {
        return orderRepository.findByUserId(userId, PageRequest.of(page, size)).map(this::toVO);
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

    public OrderMain mustGet(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> BizException.of(404, "订单不存在"));
    }

    public OrderVO toVO(OrderMain o) {
        return new OrderVO(o.getId(), o.getOrderNo(), o.getOrderType(),
                o.getStatus().name(), o.getStatus().label(),
                o.getFromAddress(), o.getToAddress(),
                o.getGoodsDesc(), o.getEstimateFee(), o.getRealFee(), o.getRiderId(),
                o.getPickupCode(),
                o.getCreatedTime() == null ? null : o.getCreatedTime().toString(),
                o.getAcceptedTime() == null ? null : o.getAcceptedTime().toString(),
                o.getDeliveredTime() == null ? null : o.getDeliveredTime().toString(),
                o.getFinishTime() == null ? null : o.getFinishTime().toString());
    }
}
