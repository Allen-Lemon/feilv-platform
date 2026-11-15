package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.*;
import com.feilv.domain.OrderTransfer.Status;
import com.feilv.dto.OrderTransferDtos.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import com.feilv.util.BizNoUtil;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 单订单行锁是转派、履约、投诉、结算的共同单写者；用户/骑手只读，不引入反向用户锁。 */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class OrderTransferService {
    private final OrderTransferRepository transfers;
    private final OrderRepository orders;
    private final RiderRepository riders;
    private final UserRepository users;
    private final OrderStatusLogRepository logs;
    private final OrderService orderService;
    private final AuditService audit;
    private final EntityManager entityManager;

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = ExpiredTransferException.class)
    public TransferVO create(long userId, long orderId, TransferReq req) {
        if (req == null || req.targetRiderId() == null || req.targetRiderId() <= 0) throw BizException.of(400, "目标骑手ID必须是正整数");
        String reason = reason(req.reason(), true);
        OrderMain order = orderService.lockForDispatch(orderId);
        Rider from = activeRiderForUser(userId);
        if (!Objects.equals(order.getRiderId(), from.getId())) throw BizException.of(403, "仅当前承接骑手可以发起转派");
        Rider to = activeRider(req.targetRiderId());
        if (Objects.equals(from.getId(), to.getId())) throw BizException.of(400, "不能转派给自己");
        if (Objects.equals(order.getUserId(), to.getUserId())) throw BizException.of(403, "不能转派给订单发布者");
        String unavailable = unavailable(order);
        if (unavailable != null) {
            expirePending(order, unavailable);
            throw new ExpiredTransferException(unavailable);
        }
        if (!transfers.findByOrderIdAndStatus(orderId, Status.PENDING).isEmpty()) throw BizException.of(409, "该订单已有待确认转派");
        OrderTransfer transfer = new OrderTransfer(); transfer.setOrderId(orderId);
        transfer.setFromRiderId(from.getId()); transfer.setToRiderId(to.getId()); transfer.setReason(reason);
        transfers.saveAndFlush(transfer);
        audit(transfer);
        return view(transfer);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = ExpiredTransferException.class)
    public TransferVO respond(long userId, long id, RespondReq req) {
        if (req == null || !("ACCEPTED".equals(req.decision()) || "REJECTED".equals(req.decision()))) throw BizException.of(400, "确认决定不合法");
        String reason = reason(req.reason(), false);
        OrderMain order = lockTransferOrder(id);
        OrderTransfer transfer = current(id);
        Rider to = activeRiderForUser(userId);
        if (!Objects.equals(to.getId(), transfer.getToRiderId())) throw BizException.of(403, "仅目标骑手可以确认或拒绝");
        if (transfer.getStatus() == Status.ACCEPTED && "ACCEPTED".equals(req.decision())) return view(transfer);
        requirePending(transfer);
        if (expireIfUnavailable(order, transfer)) throw new ExpiredTransferException("订单已不可转派");
        if ("REJECTED".equals(req.decision())) {
            handle(transfer, Status.REJECTED, reason);
            return view(transfer);
        }
        activeRider(transfer.getFromRiderId());
        if (to.getOnlineStatus() != 1 && to.getOnlineStatus() != 2) throw BizException.of(400, "请先切换到抢单/听单模式");
        if (Objects.equals(order.getUserId(), userId) || Objects.equals(transfer.getFromRiderId(), to.getId())) throw BizException.of(403, "目标骑手不合法");
        order.setRiderId(to.getId());
        // acceptedTime保留首次接单时间；轮换配送码且保证与旧码不同，服务任务的空码不动。
        if (!orderService.isServiceTask(order)) {
            String code;
            do { code = BizNoUtil.pickupCode(); } while (Objects.equals(code, order.getPickupCode()));
            order.setPickupCode(code);
        }
        orders.saveAndFlush(order);
        OrderStatusLog log = new OrderStatusLog(); log.setOrderId(order.getId());
        log.setFromStatus("ACCEPTED"); log.setToStatus("ACCEPTED"); log.setOperatorType("RIDER"); log.setOperatorId(userId);
        log.setRemark("确认转派 transferId=" + id + ", fromRiderId=" + transfer.getFromRiderId() + ", toRiderId=" + to.getId());
        logs.save(log);
        handle(transfer, Status.ACCEPTED, reason);
        return view(transfer);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = ExpiredTransferException.class)
    public TransferVO cancel(long userId, long id, CancelReq req) {
        String reason = reason(req == null ? null : req.reason(), false);
        OrderMain order = lockTransferOrder(id);
        OrderTransfer transfer = current(id);
        Rider from = activeRiderForUser(userId);
        if (!Objects.equals(from.getId(), transfer.getFromRiderId())) throw BizException.of(403, "仅发起骑手可以取消转派");
        requirePending(transfer);
        if (expireIfUnavailable(order, transfer)) throw new ExpiredTransferException("订单已不可转派");
        handle(transfer, Status.CANCELED, reason);
        return view(transfer);
    }

    public Page<TransferVO> myList(long userId, int page, String direction, String status) {
        Rider rider = activeRiderForUser(userId);
        if (!"INCOMING".equals(direction) && !"OUTGOING".equals(direction)) throw BizException.of(400, "direction必须为INCOMING或OUTGOING");
        Status filter = status(status); Pageable pageable = pageable(page);
        synchronizePending(rider.getId(), null);
        Page<OrderTransfer> result = transfers.search(rider.getId(), direction, filter, null, pageable);
        return new PageImpl<>(views(result.getContent()), pageable, result.getTotalElements());
    }

    public List<TransferVO> forOrder(long userId, long orderId) {
        OrderMain order = orderService.lockForDispatch(orderId);
        List<OrderTransfer> history = transfers.findByOrderIdOrderByIdDesc(orderId);
        Long riderId = riders.findByUserId(userId).map(Rider::getId).orElse(null);
        boolean involved = riderId != null && history.stream().anyMatch(t -> riderId.equals(t.getFromRiderId()) || riderId.equals(t.getToRiderId()));
        if (!Objects.equals(order.getUserId(), userId) && !(riderId != null && riderId.equals(order.getRiderId())) && !involved) {
            throw BizException.of(403, "无权查看该订单转派记录");
        }
        for (OrderTransfer transfer : history) if (transfer.getStatus() == Status.PENDING) expireIfUnavailable(order, transfer);
        return views(history);
    }

    public Page<TransferVO> adminList(int page, Long orderId, String status) {
        if (orderId != null && orderId <= 0) throw BizException.of(400, "订单ID必须是正整数");
        Status filter = status(status); Pageable pageable = pageable(page);
        synchronizePending(null, orderId);
        Page<OrderTransfer> result = transfers.search(null, null, filter, orderId, pageable);
        return new PageImpl<>(views(result.getContent()), pageable, result.getTotalElements());
    }

    /** 只能在已持有订单锁、实际开始履约的同一事务调用。失败核销/重复开始不会触发。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expirePendingOnStart(OrderMain order) {
        expirePending(order, "订单已开始履约");
    }

    private void synchronizePending(Long riderId, Long orderId) {
        // 多订单查询按ID升序锁，避免两个列表请求反向持锁。只同步相关PENDING，不扫描历史订单。
        for (Long id : transfers.pendingOrderIds(riderId, orderId, Status.PENDING)) {
            OrderMain order = orderService.lockForDispatch(id);
            for (OrderTransfer transfer : transfers.findByOrderIdAndStatus(id, Status.PENDING)) expireIfUnavailable(order, transfer);
        }
    }

    private boolean expireIfUnavailable(OrderMain order, OrderTransfer transfer) {
        String why = !Objects.equals(order.getRiderId(), transfer.getFromRiderId()) ? "当前承接骑手已改变" : unavailable(order);
        if (why == null) return false;
        handle(transfer, Status.EXPIRED, why);
        return true;
    }

    private void expirePending(OrderMain order, String reason) {
        for (OrderTransfer transfer : transfers.findByOrderIdAndStatus(order.getId(), Status.PENDING)) handle(transfer, Status.EXPIRED, reason);
    }

    /** 任意投诉历史或财务凭据均禁止责任漂移，拒绝脏数据/旧单已发生结算赔付的情况。 */
    private String unavailable(OrderMain order) {
        if (order.getStatus() != OrderStatus.ACCEPTED || order.getRiderId() == null
                || order.getDeliveredTime() != null || order.getFinishTime() != null) return "仅已接单且未开始履约订单可转派";
        if (!orderService.paidRiderShares(List.of(order)).containsKey(order.getId())) return "订单尚未MOCK支付成功";
        for (String entity : List.of("Complaint", "ComplaintDeduction", "WalletOrderSettlement", "WalletComplaintReceipt", "WalletEntry")) {
            if (entityManager.createQuery("select count(e) from " + entity + " e where e.orderId = :orderId", Long.class)
                    .setParameter("orderId", order.getId()).getSingleResult() > 0) return "订单已有投诉或财务记录，不可转派";
        }
        return null;
    }

    private Rider activeRiderForUser(long userId) {
        Rider rider = riders.findByUserId(userId).orElseThrow(() -> BizException.of(403, "当前账户未注册为骑手"));
        return eligible(rider);
    }
    private Rider activeRider(long riderId) {
        return eligible(riders.findById(riderId).orElseThrow(() -> BizException.of(403, "目标骑手不存在或不可用")));
    }
    private Rider eligible(Rider rider) {
        // OSIV/外层事务可能已缓存认证时的主档；订单锁等待后重新只读刷新，不获取user行锁。
        entityManager.refresh(rider);
        User user = users.findById(rider.getUserId()).orElseThrow(() -> BizException.of(403, "骑手账户不可用"));
        entityManager.refresh(user);
        if (user.getStatus() != 1 || !"RIDER".equals(user.getRole()) || !"ACTIVE".equals(rider.getStatus())) throw BizException.of(403, "仅正常现役骑手可发起或接收转派");
        // 不调用requireBoundMobile（会先锁user），避免user->order与钱包order->user交叉死锁。
        if (user.getMobile() == null || user.getMobile().isBlank()) throw BizException.of(4091, "请先绑定手机号");
        return rider;
    }
    private OrderMain lockTransferOrder(long id) {
        return orderService.lockForDispatch(transfers.findOrderId(id).orElseThrow(() -> BizException.of(404, "转派不存在")));
    }
    private OrderTransfer current(long id) {
        OrderTransfer transfer = transfers.findById(id).orElseThrow(() -> BizException.of(404, "转派不存在"));
        entityManager.refresh(transfer); // 即便外层事务曾读过，也以订单锁后的最新终态为准。
        return transfer;
    }
    private void requirePending(OrderTransfer transfer) {
        if (transfer.getStatus() != Status.PENDING) throw BizException.of(409, "转派已处理，不可重复操作");
    }
    private void handle(OrderTransfer transfer, Status status, String reason) {
        transfer.setStatus(status); transfer.setResponseReason(reason); transfer.setHandledTime(LocalDateTime.now());
        transfers.saveAndFlush(transfer); audit(transfer);
    }
    private void audit(OrderTransfer transfer) {
        audit.record("ORDER_TRANSFER_" + transfer.getStatus(), "transferId=" + transfer.getId() + ", orderId=" + transfer.getOrderId()
                + ", fromRiderId=" + transfer.getFromRiderId() + ", toRiderId=" + transfer.getToRiderId());
    }
    private TransferVO view(OrderTransfer transfer) { return views(List.of(transfer)).get(0); }
    private List<TransferVO> views(List<OrderTransfer> records) {
        if (records.isEmpty()) return List.of();
        List<OrderMain> orderList = orders.findAllById(records.stream().map(OrderTransfer::getOrderId).distinct().toList());
        Map<Long, OrderMain> orderIndex = orderList.stream().collect(Collectors.toMap(OrderMain::getId, Function.identity()));
        Map<Long, CustomTask> taskIndex = orderService.taskIndex(orderList);
        Set<Long> ids = new HashSet<>(); records.forEach(t -> { ids.add(t.getFromRiderId()); ids.add(t.getToRiderId()); });
        Map<Long, Rider> riderIndex = riders.findAllById(ids).stream().collect(Collectors.toMap(Rider::getId, Function.identity()));
        return records.stream().map(t -> {
            OrderMain order = orderIndex.get(t.getOrderId()); CustomTask task = taskIndex.get(t.getOrderId());
            return new TransferVO(t.getId(), t.getOrderId(), order == null ? null : order.getOrderNo(),
                    t.getFromRiderId(), name(riderIndex.get(t.getFromRiderId())), t.getToRiderId(), name(riderIndex.get(t.getToRiderId())),
                    t.getStatus().name(), mask(t.getReason()), mask(t.getResponseReason()), t.getCreatedTime(), t.getHandledTime(),
                    OrderService.taskMode(task), mask(task != null ? task.getTitle() : order == null ? "" : order.getGoodsDesc()));
        }).toList();
    }
    private static String mask(String value) {
        return value == null ? null : value.replaceAll("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)", "$1****$3");
    }
    private static String name(Rider rider) { return rider == null ? "未知骑手" : mask(rider.getName()); }
    private static String reason(String value, boolean required) {
        if ((required && (value == null || value.isBlank())) || (value != null && value.length() > 255)) throw BizException.of(400, "原因长度必须为1..255，响应原因可选");
        return value == null ? null : value.trim();
    }
    private static Status status(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Status.valueOf(value); } catch (IllegalArgumentException e) { throw BizException.of(400, "转派status无效"); }
    }
    private static Pageable pageable(int page) {
        if (page < 0) throw BizException.of(400, "page必须为非负整数");
        return PageRequest.of(page, 15, Sort.by(Sort.Direction.DESC, "id"));
    }
    /** 仅该409要提交自动过期记录；其他异常照常回滚，不让过期后又恢复PENDING。 */
    static class ExpiredTransferException extends BizException {
        ExpiredTransferException(String message) { super(409, message); }
    }
}
