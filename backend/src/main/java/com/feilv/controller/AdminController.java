package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.BizException;
import com.feilv.dto.ComplaintDtos.ComplaintReviewReq;
import com.feilv.dto.ComplaintDtos.ComplaintHandleReq;
import jakarta.validation.Valid;
import com.feilv.domain.OrderMain;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.ComplaintRepository;
import com.feilv.repository.OrderRepository;
import com.feilv.repository.PayRecordRepository;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import com.feilv.service.ComplaintService;
import com.feilv.service.OrderService;
import lombok.RequiredArgsConstructor;
import com.feilv.service.AdminMenuAccess;
import com.feilv.service.AdminOrderService;
import com.feilv.service.AdminQueryParameters;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台接口（对应功能设计文档 7.x，须 ADMIN 角色）：
 * 经营看板、订单监控与干预、骑手管理、结算中心、投诉工单。
 * 全部干预操作生产须落 t_op_log 审计（≥6个月）。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final OrderService orderService;
    private final ComplaintService complaintService;
    private final com.feilv.service.AuditService auditService;
    private final OrderRepository orderRepository;
    private final RiderRepository riderRepository;
    private final PayRecordRepository payRecordRepository;
    private final ComplaintRepository complaintRepository;
    private final UserRepository userRepository;
    private final AdminOrderService adminOrderService;
    private final AdminMenuAccess adminMenuAccess;

    /** 经营看板（对应功能设计文档7.6） */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        long todayOrders = orderRepository.countByCreatedTimeAfter(todayStart);
        long totalOrders = orderRepository.count();
        long incomeToday = payRecordRepository
                .findByPayTimeBetween(todayStart, LocalDateTime.now()).stream()
                .mapToInt(p -> p.getSharePlatform()).sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userCount", userRepository.count() - 1); // 扣除内置管理员
        data.put("riderCount", riderRepository.count());
        data.put("orderCountToday", todayOrders);
        data.put("orderCountTotal", totalOrders);
        data.put("incomeTodayFen", incomeToday);
        return Result.ok(data);
    }

    /** 订单监控：全量订单分页 */
    @GetMapping("/orders")
    public Result<?> orders(@RequestParam(defaultValue = "0") String page,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String userId) {
        adminMenuAccess.requireMenu("orders");
        return Result.ok(adminOrderService.list(AdminQueryParameters.page(page),
                userId == null ? null : AdminQueryParameters.id(userId), AdminQueryParameters.orderStatus(status)));
    }

    /** 人工取消（订单干预，必填原因，生产落审计日志） */
    @PostMapping("/orders/{id}/cancel")
    public Result<?> cancelOrder(@PathVariable long id, @RequestBody Map<String, String> body) {
        adminMenuAccess.requireMenu("orders");
        OrderMain order = orderService.mustGet(id);
        String reason = body == null ? "" : body.getOrDefault("reason", "");
        if (reason.isBlank()) return Result.fail(400, "干预取消原因不能为空");
        orderService.changeStatus(order, OrderStatus.CANCELED, "ADMIN", UserContextLong(), "后台干预取消：" + reason);
        auditService.record("ORDER_CANCEL", "orderId=" + id + ", reason=" + reason);
        return Result.ok();
    }

    @PostMapping("/orders/{id}/complete")
    public Result<?> forceComplete(@PathVariable long id, @RequestBody Map<String, String> body) {
        adminMenuAccess.requireMenu("orders");
        OrderMain order = orderService.mustGet(id);
        if (order.getStatus() != OrderStatus.DELIVERED) return Result.fail(4090, "只有已送达订单可以强制完成");
        String reason = body == null ? "" : body.getOrDefault("reason", "");
        if (reason.isBlank()) return Result.fail(400, "强制完成原因不能为空");
        orderService.changeStatus(order, OrderStatus.FINISHED, "ADMIN", UserContextLong(), "后台强制完成：" + reason);
        auditService.record("ORDER_FORCE_COMPLETE", "orderId=" + id + ", reason=" + reason);
        return Result.ok();
    }

    /** 骑手列表（在线状态、信用分） */
    @GetMapping("/riders")
    public Result<List<Map<String, Object>>> riders() {
        List<Map<String, Object>> list = new ArrayList<>();
        riderRepository.findAll().forEach(r -> list.add(Map.of(
                "riderId", r.getId(),
                "name", r.getName(),
                "creditScore", r.getCreditScore(),
                "onlineStatus", r.getOnlineStatus(),
                "status", r.getStatus())));
        return Result.ok(list);
    }

    /** 结算中心：骑手分账汇总（对应功能设计文档7.3；生产：结算单→财务审核→批量打款） */
    @GetMapping("/settlements")
    public Result<List<Map<String, Object>>> settlements() {
        adminMenuAccess.requireMenu("settlements");
        return Result.ok(complaintService.settlements());
    }

    /** 投诉工单列表 */
    @GetMapping("/complaints")
    public Result<?> complaints(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String orderId,
                                @RequestParam(defaultValue = "0") String page) {
        adminMenuAccess.requireMenu("complaints");
        return Result.ok(complaintService.list(status, orderId == null ? null : AdminQueryParameters.id(orderId),
                AdminQueryParameters.page(page)));
    }

    /** 审批和MOCK扣款/等额赔付同事务，金额单位分。 */
    @PostMapping("/complaints/{id}/review")
    public Result<?> reviewComplaint(@PathVariable long id, @Valid @RequestBody ComplaintReviewReq req) {
        adminMenuAccess.requireMenu("complaints");
        return Result.ok(complaintService.review(id, req));
    }

    /** 旧接口仅兼容零元受理/办结，不可用于绕过MOCK扣款审核。 */
    @PostMapping("/complaints/{id}/handle")
    public Result<?> handleComplaint(@PathVariable long id, @Valid @RequestBody ComplaintHandleReq req) {
        adminMenuAccess.requireMenu("complaints");
        if ((req.compensationFen() != null && req.compensationFen() != 0)
                || (req.amountFen() != null && req.amountFen() != 0)) {
            throw BizException.of(400, "旧接口仅支持0元办结，扣款请使用review审核接口");
        }
        return Result.ok(complaintService.handle(id, req.status() == null ? "PROCESSING" : req.status(), req.result(), 0));
    }

    private Long UserContextLong() {
        var u = com.feilv.common.UserContext.get();
        return u == null ? null : u.id();
    }
}
