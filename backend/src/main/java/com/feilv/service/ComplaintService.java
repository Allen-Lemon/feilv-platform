package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.dto.ApiDtos.ComplaintReq;
import com.feilv.dto.ComplaintDtos.*;
import com.feilv.enums.Enums.ComplaintType;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 投诉审批与MOCK扣款/等额赔付闭环；绝不调用资金网关或修改原支付分账。 */
@Service
@RequiredArgsConstructor
public class ComplaintService {
    private static final Set<String> STATUSES = Set.of("OPEN", "PROCESSING", "APPROVED", "REJECTED", "DONE");
    private static final Set<OrderStatus> COMPLAINABLE = EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.PICKING,
            OrderStatus.DELIVERING, OrderStatus.DELIVERED, OrderStatus.FINISHED);

    private final ComplaintRepository complaintRepository;
    private final ComplaintActionRepository actionRepository;
    private final ComplaintDeductionRepository deductionRepository;
    private final ComplaintOrderLockRepository orderRepository;
    private final PayRecordRepository payRecordRepository;
    private final RiderRepository riderRepository;
    private final AuditService auditService;
    private final WalletAccountingService walletAccountingService;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long create(long userId, ComplaintReq req) {
        if (req == null || req.orderId() == null || req.orderId() <= 0) throw BizException.of(400, "订单ID无效");
        String description = requiredText(req.description(), "投诉描述");
        try { ComplaintType.valueOf(req.type() == null ? "" : req.type()); }
        catch (IllegalArgumentException e) { throw BizException.of(400, "投诉类型不合法"); }
        // 所有投诉创建和审核统一先锁订单，再锁工单/账本，串行化本单额度和活动工单判断。
        OrderMain order = lockedOrder(req.orderId());
        if (!Objects.equals(order.getUserId(), userId)) throw BizException.of(403, "无权投诉他人订单");
        if (!COMPLAINABLE.contains(order.getStatus())) throw BizException.of(4090, "只有已接单订单可以投诉");
        if (!complaintRepository.findActiveForUpdate(order.getId()).isEmpty()) {
            throw BizException.of(4090, "该订单已有活动投诉工单");
        }
        Complaint complaint = new Complaint();
        complaint.setOrderId(order.getId());
        complaint.setUserId(userId);
        complaint.setType(req.type());
        complaint.setDescription(description);
        complaint.setStatus("OPEN");
        complaintRepository.saveAndFlush(complaint);
        return complaint.getId();
    }

    @Transactional(readOnly = true)
    public List<ComplaintVO> listForOrder(long userId, long orderId) {
        OrderMain order = orderRepository.findById(orderId).orElseThrow(() -> BizException.of(404, "订单不存在"));
        if (!Objects.equals(order.getUserId(), userId)) {
            Rider rider = riderRepository.findByUserId(userId).orElse(null);
            if (rider == null || !Objects.equals(order.getRiderId(), rider.getId())) {
                throw BizException.of(403, "仅发布者或承运骑手可查看投诉");
            }
        }
        return views(complaintRepository.findByOrderIdOrderByIdDesc(orderId));
    }

    @Transactional(readOnly = true)
    public Page<ComplaintVO> list(String status, Long orderId, int page) {
        if (status != null && status.isBlank()) status = null;
        if (status != null && !STATUSES.contains(status)) throw BizException.of(400, "投诉状态不合法");
        if (orderId != null && orderId <= 0) throw BizException.of(400, "订单ID必须是正整数");
        if (page < 0) throw BizException.of(400, "page必须是非负整数");
        var pageable = PageRequest.of(page, 15, Sort.by(Sort.Direction.DESC, "id"));
        Page<Complaint> records = complaintRepository.search(status, orderId, pageable);
        return new PageImpl<>(views(records.getContent()), pageable, records.getTotalElements());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ComplaintVO review(long id, ComplaintReviewReq req) {
        if (req == null || !Set.of("APPROVED", "REJECTED", "PROCESSING").contains(req.decision() == null ? "" : req.decision())) {
            throw BizException.of(400, "审批决定不合法");
        }
        String reason = requiredText(req.reason(), "审批原因");
        if (req.amountFen() == null || req.amountFen() < 0) throw BizException.of(400, "amountFen必须为非负整数（分）");
        if (!"APPROVED".equals(req.decision()) && req.amountFen() != 0) {
            throw BizException.of(400, "驳回或受理金额必须为0");
        }
        return transition(id, req.decision(), reason, req.amountFen());
    }

    /** 旧接口只允许零元PROCESSING/DONE，不允许通过旧金额字段生成无账本的赔付。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ComplaintVO handle(long id, String status, String result, int compensationFen) {
        if (compensationFen != 0) throw BizException.of(400, "旧接口仅支持0元办结，扣款请使用review审核接口");
        if (!"PROCESSING".equals(status) && !"DONE".equals(status)) throw BizException.of(400, "工单状态不合法");
        return transition(id, status, requiredText(result, "处理原因"), 0);
    }

    private ComplaintVO transition(long id, String status, String reason, int amountFen) {
        long operatorId = UserContext.userId();
        Long orderId = complaintRepository.findOrderId(id).orElseThrow(() -> BizException.of(404, "投诉工单不存在"));
        OrderMain order = lockedOrder(orderId);
        Complaint complaint = complaintRepository.findByIdForUpdate(id).orElseThrow(() -> BizException.of(404, "投诉工单不存在"));
        if (!"OPEN".equals(complaint.getStatus()) && !"PROCESSING".equals(complaint.getStatus())) {
            throw BizException.of(4090, "该工单已办结，不可重复审核");
        }
        if (status.equals(complaint.getStatus())) throw BizException.of(4090, "该工单已受理");
        if (!Objects.equals(order.getUserId(), complaint.getUserId())) throw BizException.of(4090, "投诉发布者与订单不一致");
        if (amountFen > 0) {
            if (!deductibleStatus(order) || order.getRiderId() == null || !riderRepository.existsById(order.getRiderId())) {
                throw BizException.of(4090, "仅有承运骑手且已送达/完成的订单可MOCK扣款");
            }
            long share = payRecordRepository.findSuccessfulMockForUpdate(orderId).stream()
                    .mapToLong(PayRecord::getShareRider).sum();
            List<ComplaintDeduction> existing = deductionRepository.findByOrderIdForUpdate(orderId);
            long deducted = existing.stream().mapToLong(ComplaintDeduction::getAmountFen).sum();
            if (existing.stream().anyMatch(d -> Objects.equals(d.getComplaintId(), id))) {
                throw BizException.of(4090, "该投诉已记账，不可重复扣款");
            }
            long remaining = Math.max(0L, share - deducted);
            if (amountFen > remaining) throw BizException.of(4090, "超出本单MOCK分账剩余可扣额度（分）：" + remaining);
            ComplaintDeduction entry = new ComplaintDeduction();
            entry.setComplaintId(id);
            entry.setOrderId(orderId);
            entry.setRiderId(order.getRiderId());
            entry.setUserId(order.getUserId());
            entry.setAmountFen(amountFen);
            entry.setChannel("MOCK");
            entry.setCreatedBy(operatorId);
            entry.setCreatedTime(LocalDateTime.now());
            entry = deductionRepository.saveAndFlush(entry);
            walletAccountingService.applyComplaintDeduction(entry);
        }
        complaint.setStatus(status);
        complaint.setResult(reason);
        complaint.setCompensationFen(amountFen);
        complaint.setHandledBy(operatorId);
        complaint.setHandledTime(LocalDateTime.now());
        complaintRepository.saveAndFlush(complaint);
        action(id, status, reason, operatorId);
        if (amountFen > 0) action(id, "MOCK_COMPENSATION",
                "MOCK账务模拟：骑手本单扣款" + amountFen + "分，等额赔付发布者；无真实资金划转", operatorId);
        auditService.record("COMPLAINT_" + status, "complaintId=" + id + ", channel=MOCK, amountFen=" + amountFen);
        return views(List.of(complaint)).get(0);
    }

    private void action(long complaintId, String status, String detail, long operatorId) {
        ComplaintAction action = new ComplaintAction();
        action.setComplaintId(complaintId);
        action.setOperatorId(operatorId);
        action.setAction(status);
        action.setDetail(detail);
        actionRepository.save(action);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> settlements() {
        List<Object[]> shares = payRecordRepository.sumRiderShareGroupByRider();
        Map<Long, Long> deductions = amounts(deductionRepository.sumByRider());
        List<Long> riderIds = shares.stream().map(r -> ((Number) r[0]).longValue()).toList();
        Map<Long, Rider> riders = riderRepository.findAllById(riderIds).stream()
                .collect(Collectors.toMap(Rider::getId, Function.identity()));
        List<Map<String, Object>> rows = new ArrayList<>();
        shares.stream().sorted(Comparator.comparingLong(r -> ((Number) r[0]).longValue())).forEach(row -> {
            long riderId = ((Number) row[0]).longValue(), total = ((Number) row[1]).longValue();
            long deducted = deductions.getOrDefault(riderId, 0L);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("riderId", riderId);
            result.put("name", riders.containsKey(riderId) ? mask(riders.get(riderId).getName()) : "未知骑手");
            result.put("totalShareFen", total);
            result.put("totalShareYuan", java.math.BigDecimal.valueOf(total, 2).toPlainString());
            result.put("deductedFen", deducted);
            result.put("netShareFen", total - deducted);
            result.put("compensationFen", deducted);
            result.put("paymentChannel", "MOCK");
            rows.add(result);
        });
        return rows;
    }

    /** 每页批量加载订单、支付汇总、账本及行动记录，避免逐工单查询。 */
    private List<ComplaintVO> views(List<Complaint> complaints) {
        if (complaints.isEmpty()) return List.of();
        List<Long> orderIds = complaints.stream().map(Complaint::getOrderId).distinct().toList();
        Map<Long, OrderMain> orders = orderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(OrderMain::getId, Function.identity()));
        Map<Long, Long> shares = amounts(payRecordRepository.sumSuccessfulMockShareByOrderIds(orderIds));
        List<ComplaintDeduction> entries = deductionRepository.findByOrderIdIn(orderIds);
        Map<Long, Long> totals = entries.stream().collect(Collectors.groupingBy(ComplaintDeduction::getOrderId,
                Collectors.summingLong(ComplaintDeduction::getAmountFen)));
        Map<Long, Long> perComplaint = entries.stream().collect(Collectors.toMap(ComplaintDeduction::getComplaintId,
                d -> (long) d.getAmountFen()));
        Map<Long, List<ComplaintActionVO>> actions = actionRepository.findByComplaintIdInOrderByIdAsc(
                complaints.stream().map(Complaint::getId).toList()).stream().collect(Collectors.groupingBy(
                ComplaintAction::getComplaintId, Collectors.mapping(a -> new ComplaintActionVO(a.getAction(),
                        mask(a.getDetail()), a.getOperatorId(), a.getCreatedTime()), Collectors.toList())));
        Set<Long> riderIds = orders.values().stream().map(OrderMain::getRiderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> existingRiders = riderRepository.findAllById(riderIds).stream().map(Rider::getId).collect(Collectors.toSet());
        return complaints.stream().map(c -> {
            OrderMain order = orders.get(c.getOrderId());
            long deducted = totals.getOrDefault(c.getOrderId(), 0L);
            boolean eligible = order != null && deductibleStatus(order) && existingRiders.contains(order.getRiderId());
            long remaining = eligible ? Math.max(0L, shares.getOrDefault(c.getOrderId(), 0L) - deducted) : 0;
            return new ComplaintVO(c.getId(), c.getOrderId(), order == null ? null : order.getOrderNo(), c.getUserId(),
                    order == null ? null : order.getRiderId(), c.getType(), mask(c.getDescription()), c.getStatus(), mask(c.getResult()),
                    c.getCompensationFen(), perComplaint.getOrDefault(c.getId(), 0L), deducted, remaining,
                    shares.containsKey(c.getOrderId()) ? "MOCK" : null, c.getCreatedTime(), c.getHandledTime(), c.getHandledBy(),
                    actions.getOrDefault(c.getId(), List.of()));
        }).toList();
    }

    private static Map<Long, Long> amounts(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> ((Number) r[1]).longValue()));
    }

    private OrderMain lockedOrder(long id) {
        return orderRepository.findByIdForUpdate(id).orElseThrow(() -> BizException.of(404, "订单不存在"));
    }

    private static boolean deductibleStatus(OrderMain order) {
        return order.getStatus() == OrderStatus.DELIVERED || order.getStatus() == OrderStatus.FINISHED;
    }

    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 255) throw BizException.of(400, label + "必填且长度为1..255");
        return value.trim();
    }

    private static String mask(String value) {
        return value == null ? null : value.replaceAll("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)", "$1****$3");
    }
}
