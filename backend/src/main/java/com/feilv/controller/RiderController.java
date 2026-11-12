package com.feilv.controller;

import com.feilv.common.BizException;
import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.dto.ApiDtos.GrabVO;
import com.feilv.dto.ApiDtos.TrackPointReq;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.OrderRepository;
import com.feilv.service.DispatchService;
import com.feilv.service.ReviewRiskService;
import com.feilv.service.TrackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 骑手端接口（对应详细设计 5.x）：接单大厅、抢单、履约、轨迹上报、信用查询 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RiderController {

    private final DispatchService dispatchService;
    private final TrackService trackService;
    private final ReviewRiskService reviewRiskService;
    private final RiderRepository riderRepository;
    private final OrderRepository orderRepository;

    /** 切换接单模式：0下线 1抢单 2听单 */
    @PostMapping("/rider/online")
    public Result<Void> online(@RequestBody Map<String, Integer> body) {
        UserContext.requireRole("RIDER");
        int mode = body.getOrDefault("onlineStatus", 1);
        dispatchService.online(UserContext.userId(), mode);
        return Result.ok();
    }

    /** 我的骑手档案（信用分等） */
    @GetMapping("/rider/me")
    public Result<Map<String, Object>> me() {
        UserContext.requireRole("RIDER");
        var rider = riderRepository.findByUserId(UserContext.userId())
                .orElseThrow(() -> BizException.of(403, "当前账户未注册为骑手"));
        return Result.ok(Map.of(
                "riderId", rider.getId(),
                "name", rider.getName(),
                "creditScore", rider.getCreditScore(),
                "onlineStatus", rider.getOnlineStatus(),
                "stars", reviewRiskService.riderStars(rider.getId())));
    }

    /** 抢单大厅：待接单订单（酬劳=配送费×70%） */
    @GetMapping("/grab/list")
    public Result<List<GrabVO>> grabList() {
        UserContext.requireRole("RIDER");
        return Result.ok(dispatchService.waitGrabList());
    }

    /** 抢单（公平锁：同一订单仅一人抢成，反馈≤2秒） */
    @PostMapping("/grab/{orderId}")
    public Result<Map<String, Object>> grab(@PathVariable long orderId) {
        UserContext.requireRole("RIDER");
        String pickupCode = dispatchService.grab(UserContext.userId(), orderId);
        return Result.ok(Map.of("pickupCode", pickupCode, "msg", "抢单成功"));
    }

    /** 我的承运订单 */
    @GetMapping("/rider/orders")
    public Result<?> myOrders(@RequestParam(defaultValue = "0") int page) {
        UserContext.requireRole("RIDER");
        var rider = riderRepository.findByUserId(UserContext.userId())
                .orElseThrow(() -> BizException.of(403, "当前账户未注册为骑手"));
        return Result.ok(orderRepository
                .findByRiderId(rider.getId(), PageRequest.of(page, 10))
                .map(o -> Map.of(
                        "id", o.getId(),
                        "orderNo", o.getOrderNo(),
                        "status", o.getStatus().label(),
                        "statusCode", o.getStatus().name(),
                        "fromAddress", o.getFromAddress(),
                        "toAddress", o.getToAddress(),
                        "rewardFen", o.getEstimateFee() * 70 / 100)));
    }

    /** 取件核销（ACCEPTED→PICKING，校验取件码防冒领） */
    @PostMapping("/orders/{id}/pickup")
    public Result<Void> pickup(@PathVariable long id,
                                @RequestBody(required = false) Map<String, String> body) {
        UserContext.requireRole("RIDER");
        dispatchService.pickup(UserContext.userId(), id, body == null ? null : body.get("pickupCode"));
        return Result.ok();
    }

    /** 出发配送（PICKING→DELIVERING） */
    @PostMapping("/orders/{id}/depart")
    public Result<Void> depart(@PathVariable long id) {
        UserContext.requireRole("RIDER");
        dispatchService.depart(UserContext.userId(), id);
        return Result.ok();
    }

    /** 送达确认（DELIVERING→DELIVERED；生产须≥2张时间位置水印照片） */
    @PostMapping("/orders/{id}/deliver")
    public Result<Void> deliver(@PathVariable long id) {
        UserContext.requireRole("RIDER");
        dispatchService.deliver(UserContext.userId(), id);
        return Result.ok();
    }

    /** 轨迹上报（配送中5-10秒自适应） */
    @PostMapping("/track/report")
    public Result<Void> report(@Valid @RequestBody TrackPointReq req) {
        UserContext.requireRole("RIDER");
        trackService.report(UserContext.userId(), req.orderId(), req.lng(), req.lat(), req.speed());
        return Result.ok();
    }

    /** 我的信用分变动明细（逐笔可追溯） */
    @GetMapping("/credit/logs")
    public Result<?> creditLogs() {
        UserContext.requireRole("RIDER");
        return Result.ok(reviewRiskService.creditLogs(UserContext.userId()));
    }
}
