package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.OrderMain;
import com.feilv.domain.Rider;
import com.feilv.dto.ApiDtos.GrabVO;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.OrderRepository;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** 调度域：配送订单与服务型任务共享骑手、支付、接单和完成结算。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchService {
    private final OrderRepository orderRepository;
    private final RiderRepository riderRepository;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final OrderTransferService orderTransferService;

    @Transactional
    public void online(long userId, int onlineStatus) {
        Rider rider = requireActiveRider(userId);
        if (onlineStatus < 0 || onlineStatus > 2) throw BizException.of(400, "接单模式不合法");
        rider.setOnlineStatus(onlineStatus);
        riderRepository.save(rider);
    }

    /** 仅现役骑手可看大厅；不展示内容、联系人或电话，且只展示已有成功支付的订单。 */
    @Transactional(readOnly = true)
    public List<GrabVO> waitGrabList(long userId) {
        requireActiveRider(userId);
        List<OrderMain> orders = orderRepository.findPaidGrabOrders(OrderStatus.WAIT_GRAB, userId, PageRequest.of(0, 20));
        var paidShares = orderService.paidRiderShares(orders);
        var tasks = orderService.taskIndex(orders);
        return orders.stream().map(o -> {
            var task = tasks.get(o.getId());
            return new GrabVO(o.getId(), o.getOrderNo(), o.getOrderType(), o.getFromAddress(), o.getToAddress(), o.getDistanceM(),
                    paidShares.get(o.getId()), o.getCreatedTime() == null ? "" : o.getCreatedTime().toString(),
                    task == null ? o.getGoodsDesc() : task.getTitle(), OrderService.taskMode(task),
                    task == null ? "" : text(task.getLocationAddress()), task == null ? "" : text(task.getServiceTime()), o.getGoodsBudgetFen());
        }).toList();
    }

    /** 保留旧Java调用签名，但仍使用当前身份校验。 */
    public List<GrabVO> waitGrabList() { return waitGrabList(com.feilv.common.UserContext.userId()); }

    @Transactional
    public String grab(long userId, long orderId) {
        Rider rider = requireActiveRider(userId);
        if (rider.getOnlineStatus() != 1 && rider.getOnlineStatus() != 2) throw BizException.of(400, "请先切换到抢单/听单模式");
        var order = orderService.lockForDispatch(orderId);
        if (Objects.equals(order.getUserId(), userId)) throw BizException.of(403, "不能承接自己发布的订单或任务");
        if (order.getStatus() != OrderStatus.WAIT_GRAB) throw BizException.of(4091, "订单已不可接单");
        if (!orderService.paidRiderShares(List.of(order)).containsKey(orderId)) throw BizException.of(4090, "订单尚未支付成功");
        int rows = orderRepository.grabOrder(orderId, rider.getId(), OrderStatus.ACCEPTED, OrderStatus.WAIT_GRAB, LocalDateTime.now());
        if (rows == 0) throw BizException.of(4091, "手慢了，该订单已被其他骑手抢走");
        order = orderService.mustGet(orderId);
        orderService.saveLogForGrab(order);
        log.info("骑手{}抢得订单{}", rider.getId(), orderId);
        return orderService.isServiceTask(order) ? "" : text(order.getPickupCode());
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> myOrders(long userId, int page) {
        Rider rider = requireActiveRider(userId);
        if (page < 0) throw BizException.of(400, "分页不合法");
        var result = orderRepository.findByRiderId(rider.getId(), PageRequest.of(page, 10));
        var tasks = orderService.taskIndex(result.getContent());
        var shares = orderService.riderShares(result.getContent());
        return result.map(o -> {
            var task = tasks.get(o.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.getId()); row.put("orderNo", o.getOrderNo()); row.put("status", OrderService.statusLabel(o.getStatus(), OrderService.taskMode(task))); row.put("statusCode", o.getStatus().name());
            row.put("fromAddress", o.getFromAddress()); row.put("toAddress", o.getToAddress()); row.put("rewardFen", shares.get(o.getId()));
            row.put("orderType", o.getOrderType()); row.put("goodsBudgetFen", o.getGoodsBudgetFen());
            row.put("taskMode", OrderService.taskMode(task)); row.put("title", task == null ? text(o.getGoodsDesc()) : task.getTitle());
            row.put("serviceTime", task == null ? "" : text(task.getServiceTime())); row.put("locationAddress", task == null ? "" : text(task.getLocationAddress()));
            return row;
        });
    }

    @Transactional
    public void pickup(long userId, long orderId, String pickupCode) {
        var order = mustOwn(userId, orderId);
        requireDelivery(order);
        if (order.getStatus() == OrderStatus.PICKING) return;
        if (order.getStatus() != OrderStatus.ACCEPTED) throw BizException.of(4090, "当前状态不可核销取件");
        if (order.getPickupCode() == null || order.getPickupCode().isBlank() || pickupCode == null || !order.getPickupCode().equals(pickupCode)) {
            throw BizException.of(4092, "取件码不正确，连续错误5次将锁定转人工核实");
        }
        orderService.changeStatus(order, OrderStatus.PICKING, "RIDER", userId, "骑手到达取件点并核销");
        orderTransferService.expirePendingOnStart(order);
    }

    @Transactional
    public void depart(long userId, long orderId) {
        var order = mustOwn(userId, orderId);
        requireDelivery(order);
        if (order.getStatus() == OrderStatus.DELIVERING) return;
        orderService.changeStatus(order, OrderStatus.DELIVERING, "RIDER", userId, "取件完成，开始配送");
    }

    @Transactional
    public void deliver(long userId, long orderId) {
        var order = mustOwn(userId, orderId);
        requireDelivery(order);
        orderService.changeStatus(order, OrderStatus.DELIVERED, "RIDER", userId, "骑手送达");
    }

    @Transactional
    public void serviceStart(long userId, long orderId) {
        var order = mustOwn(userId, orderId);
        requireService(order);
        if (Set.of(OrderStatus.PICKING, OrderStatus.DELIVERING, OrderStatus.DELIVERED, OrderStatus.FINISHED).contains(order.getStatus())) return;
        if (order.getStatus() != OrderStatus.ACCEPTED) throw BizException.of(4090, "当前状态不可开始服务");
        orderService.changeStatus(order, OrderStatus.PICKING, "RIDER", userId, "骑手开始服务");
        orderTransferService.expirePendingOnStart(order);
    }

    /** 两个内部状态及日志在一个订单锁/事务内提交；并发重试不会重复写日志。 */
    @Transactional
    public void serviceComplete(long userId, long orderId) {
        var order = mustOwn(userId, orderId);
        requireService(order);
        if (order.getStatus() == OrderStatus.DELIVERED || order.getStatus() == OrderStatus.FINISHED) return;
        if (order.getStatus() != OrderStatus.PICKING) throw BizException.of(4090, "请先开始服务再提交完成");
        order = orderService.changeStatus(order, OrderStatus.DELIVERING, "RIDER", userId, "骑手提交服务完成");
        orderService.changeStatus(order, OrderStatus.DELIVERED, "RIDER", userId, "服务已提交完成，等待发布者确认");
    }

    private OrderMain mustOwn(long userId, long orderId) {
        Rider rider = requireActiveRider(userId);
        var order = orderService.lockForDispatch(orderId);
        if (!rider.getId().equals(order.getRiderId())) throw BizException.of(403, "该订单不属于当前骑手");
        return order;
    }

    public Rider requireActiveRider(long userId) {
        var user = userRepository.findById(userId).orElseThrow(() -> BizException.of(403, "账户不可用"));
        if (user.getStatus() != 1 || !"RIDER".equals(user.getRole())) throw BizException.of(403, "仅正常骑手账户可接单或履约");
        Rider rider = riderRepository.findByUserId(userId).orElseThrow(() -> BizException.of(403, "当前账户未注册为骑手"));
        if (!"ACTIVE".equals(rider.getStatus())) throw BizException.of(403, "骑手账户已冻结");
        return rider;
    }

    private void requireDelivery(OrderMain order) {
        if (orderService.isServiceTask(order)) throw BizException.of(4090, "服务型任务请使用开始服务/提交完成接口");
    }
    private void requireService(OrderMain order) {
        if (!orderService.isServiceTask(order)) throw BizException.of(4090, "配送订单请使用取件/配送接口");
    }
    private static String text(String value) { return value == null ? "" : value; }
}
