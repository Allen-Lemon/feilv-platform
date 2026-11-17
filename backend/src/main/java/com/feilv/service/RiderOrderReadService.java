package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.OrderMain;
import com.feilv.dto.ApiDtos.OrderVO;
import com.feilv.dto.CustomTaskDtos.TaskVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A rider-source session can read details only while its live rider remains the actual carrier. */
@Service
@RequiredArgsConstructor
public class RiderOrderReadService {
    private final DispatchService dispatch;
    private final OrderService orders;
    private final CustomTaskService tasks;

    @Transactional(readOnly = true)
    public OrderVO detailForRider(long userId, long orderId) {
        OrderMain order = requireCarrier(userId, orderId);
        return orders.toVO(order, orders.serviceTaskMode(order), false);
    }

    @Transactional(readOnly = true)
    public TaskVO taskForRider(long userId, long orderId) {
        requireCarrier(userId, orderId);
        // Preserve tag, contact, price and paid-share snapshots in the established task projection.
        return tasks.detail(orderId, userId);
    }

    private OrderMain requireCarrier(long userId, long orderId) {
        var current = UserContext.get();
        if (current == null) throw BizException.of(401, "请先登录骑手小程序");
        if (current.id() != userId || !"RIDER".equals(current.source()) || !"RIDER".equals(current.role())
                || current.appId() == null || current.appId().isBlank()) {
            throw BizException.of(403, "请使用骑手小程序查看承运订单");
        }
        if (orderId <= 0) throw BizException.of(400, "订单ID必须为正整数");
        var rider = dispatch.requireActiveRider(userId);
        OrderMain order = orders.mustGet(orderId);
        if (rider.getId() == null || !rider.getId().equals(order.getRiderId())) {
            throw BizException.of(403, "该订单不属于当前骑手");
        }
        return order;
    }
}
