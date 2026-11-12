package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.dto.ApiDtos.OrderCreateReq;
import com.feilv.dto.ApiDtos.OrderVO;
import com.feilv.dto.ApiDtos.QuoteReq;
import com.feilv.dto.ApiDtos.QuoteVO;
import com.feilv.dto.ApiDtos.TrackPoint;
import com.feilv.service.OrderService;
import com.feilv.service.PayService;
import com.feilv.service.TrackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** C端订单接口（对应详细设计10.2 订单域） */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AppOrderController {

    private final OrderService orderService;
    private final PayService payService;
    private final TrackService trackService;

    /** 费用预估（详细设计4.2.6：明细透明展示） */
    @PostMapping("/orders/quote")
    public Result<QuoteVO> quote(@Valid @RequestBody QuoteReq req) {
        return Result.ok(orderService.quote(req));
    }

    /** 创建订单（品类拦截→计价→建档） */
    @PostMapping("/orders")
    public Result<OrderVO> create(@Valid @RequestBody OrderCreateReq req) {
        return Result.ok(orderService.create(UserContext.userId(), req));
    }

    /** 模拟支付（生产对接微信/支付宝持牌渠道，回调≤5秒同步状态） */
    @PostMapping("/orders/{id}/pay")
    public Result<Map<String, Object>> pay(@PathVariable long id) {
        var record = payService.pay(UserContext.userId(), id);
        return Result.ok(Map.of(
                "payNo", record.getPayNo(),
                "amountFen", record.getAmount(),
                "status", record.getStatus()));
    }

    /** 取消订单（接单前免费，接单后按规则计费） */
    @PostMapping("/orders/{id}/cancel")
    public Result<OrderVO> cancel(@PathVariable long id, @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return Result.ok(orderService.cancel(UserContext.userId(), id, reason));
    }

    /** 确认收货：DELIVERED→FINISHED，触发分账 */
    @PostMapping("/orders/{id}/confirm")
    public Result<OrderVO> confirm(@PathVariable long id) {
        return Result.ok(orderService.confirm(UserContext.userId(), id));
    }

    /** 我的订单（近1年在线可查，超期归档库） */
    @GetMapping("/orders")
    public Result<Page<OrderVO>> list(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "10") int size) {
        return Result.ok(orderService.myList(UserContext.userId(), page, size));
    }

    /** 订单详情 */
    @GetMapping("/orders/{id}")
    public Result<OrderVO> detail(@PathVariable long id) {
        return Result.ok(orderService.detailForUser(UserContext.userId(), id));
    }

    /** 订单轨迹（仅订单用户或承运骑手可查看） */
    @GetMapping("/orders/{id}/track")
    public Result<List<TrackPoint>> track(@PathVariable long id) {
        orderService.detailForUser(UserContext.userId(), id);
        return Result.ok(trackService.query(id));
    }
}
