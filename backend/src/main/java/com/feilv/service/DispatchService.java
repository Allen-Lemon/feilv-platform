package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.Rider;
import com.feilv.dto.ApiDtos.GrabVO;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.OrderRepository;
import com.feilv.repository.RiderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调度域（对应功能设计文档 3.5/8.2）：
 * 抢单大厅 + 抢单公平锁 + 配送履约（取件核销→出发→送达）。
 * 派单（听单）模式排序算法：距离0.5 + 负载0.2 + 信用0.3，生产再接入实时位置计算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchService {

    @Value("${feilv.share.rider-percent}")
    private int riderPercent;

    @Value("${feilv.dispatch.order-grab-timeout-min}")
    private int grabTimeoutMin;

    private final OrderRepository orderRepository;
    private final RiderRepository riderRepository;
    private final OrderService orderService;

    /** 骑手上/下线：0下线 1抢单 2听单 */
    @Transactional
    public void online(long userId, int onlineStatus) {
        Rider rider = rider(userId);
        rider.setOnlineStatus(onlineStatus);
        riderRepository.save(rider);
        log.info("骑手{}切换接单模式为:{}", rider.getName(),
                onlineStatus == 0 ? "下线" : onlineStatus == 1 ? "抢单" : "听单");
    }

    /** 抢单大厅：待接单订单（酬劳=配送费×骑手分账比例） */
    @Transactional(readOnly = true)
    public List<GrabVO> waitGrabList() {
        List<com.feilv.domain.OrderMain> orders =
                orderRepository.findByStatusAndCreatedTimeBefore(
                        OrderStatus.WAIT_GRAB, LocalDateTime.now().plusMinutes(1));
        return orders.stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()) * -1)
                .limit(20)
                .map(o -> new GrabVO(o.getId(), o.getOrderNo(), o.getOrderType(),
                        o.getFromAddress(), o.getToAddress(), o.getDistanceM(),
                        o.getEstimateFee() * riderPercent / 100,
                        o.getCreatedTime() == null ? "" : o.getCreatedTime().toString()))
                .toList();
    }

    /**
     * 抢单（详细设计5.2.2）：数据库条件更新保证同一订单仅一名骑手抢成。
     * 影响行数=1 即抢成；=0 即已被他人抢走（公平锁，反馈≤2秒内完成）。
     */
    @Transactional
    public String grab(long userId, long orderId) {
        Rider rider = rider(userId);
        if (rider.getOnlineStatus() == 0) {
            throw BizException.of(400, "请先切换到抢单/听单模式");
        }
        int rows = orderRepository.grabOrder(orderId, rider.getId(),
                OrderStatus.ACCEPTED, OrderStatus.WAIT_GRAB, LocalDateTime.now());
        if (rows == 0) {
            throw BizException.of(4091, "手慢了，该订单已被其他骑手抢走");
        }
        var order = orderService.mustGet(orderId);
        orderService.saveLogForGrab(order); // 抢单成功流转日志（已由条件更新完成，此处补日志）
        log.info("骑手{}抢得订单{}，取件码{}", rider.getName(), orderId, order.getPickupCode());
        return order.getPickupCode();
    }

    /** 取件核销：ACCEPTED→PICKING（校验取件码，防冒领） */
    @Transactional
    public void pickup(long userId, long orderId, String pickupCode) {
        Rider rider = rider(userId);
        var order = mustOwn(userId, orderId);
        if (order.getStatus() == OrderStatus.PICKING) return;
        if (order.getStatus() != OrderStatus.ACCEPTED) {
            throw BizException.of(4090, "当前状态不可核销取件");
        }
        if (order.getPickupCode() == null || order.getPickupCode().isBlank()
                || pickupCode == null || !order.getPickupCode().equals(pickupCode)) {
            throw BizException.of(4092, "取件码不正确，连续错误5次将锁定转人工核实");
        }
        orderService.changeStatus(order, OrderStatus.PICKING, "RIDER", userId, "骑手到达取件点并核销");
    }

    /** 出发配送：PICKING→DELIVERING */
    @Transactional
    public void depart(long userId, long orderId) {
        mustOwn(userId, orderId);
        var order = orderService.mustGet(orderId);
        if (order.getStatus() == OrderStatus.DELIVERING) return;
        orderService.changeStatus(order, OrderStatus.DELIVERING, "RIDER", userId, "取件完成，开始配送");
    }

    /** 送达确认：DELIVERING→DELIVERED（生产须≥2张时间位置水印照片） */
    @Transactional
    public void deliver(long userId, long orderId) {
        mustOwn(userId, orderId);
        var order = orderService.mustGet(orderId);
        orderService.changeStatus(order, OrderStatus.DELIVERED, "RIDER", userId, "骑手送达");
    }

    private com.feilv.domain.OrderMain mustOwn(long userId, long orderId) {
        Rider rider = rider(userId);
        var order = orderService.mustGet(orderId);
        if (!rider.getId().equals(order.getRiderId())) {
            throw BizException.of(403, "该订单不属于当前骑手");
        }
        return order;
    }

    private Rider rider(long userId) {
        return riderRepository.findByUserId(userId)
                .orElseThrow(() -> BizException.of(403, "当前账户未注册为骑手"));
    }
}
