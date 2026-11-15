package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.dto.OrderTransferDtos.*;
import com.feilv.service.AdminMenuAccess;
import com.feilv.service.AdminQueryParameters;
import com.feilv.service.OrderTransferService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RiderTransferController {
    private final OrderTransferService transfers;
    private final AdminMenuAccess access;

    @ModelAttribute
    public void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @PostMapping("/rider/orders/{orderId}/transfers")
    public Result<TransferVO> create(@PathVariable String orderId, @Valid @RequestBody TransferReq req) {
        UserContext.requireRole("RIDER");
        return Result.ok(transfers.create(UserContext.userId(), AdminQueryParameters.id(orderId), req));
    }

    @GetMapping("/rider/transfers")
    public Result<Page<TransferVO>> mine(@RequestParam(defaultValue = "0") String page,
                                          @RequestParam String direction, @RequestParam(required = false) String status) {
        UserContext.requireRole("RIDER");
        return Result.ok(transfers.myList(UserContext.userId(), AdminQueryParameters.page(page), direction, status));
    }

    @GetMapping("/orders/{orderId}/transfers")
    public Result<List<TransferVO>> forOrder(@PathVariable String orderId) {
        return Result.ok(transfers.forOrder(UserContext.userId(), AdminQueryParameters.id(orderId)));
    }

    @PostMapping("/rider/transfers/{id}/respond")
    public Result<TransferVO> respond(@PathVariable String id, @Valid @RequestBody RespondReq req) {
        UserContext.requireRole("RIDER");
        return Result.ok(transfers.respond(UserContext.userId(), AdminQueryParameters.id(id), req));
    }

    @PostMapping("/rider/transfers/{id}/cancel")
    public Result<TransferVO> cancel(@PathVariable String id, @Valid @RequestBody(required = false) CancelReq req) {
        UserContext.requireRole("RIDER");
        return Result.ok(transfers.cancel(UserContext.userId(), AdminQueryParameters.id(id), req));
    }

    @GetMapping("/admin/order-transfers")
    public Result<Page<TransferVO>> admin(@RequestParam(defaultValue = "0") String page,
                                          @RequestParam(required = false) String orderId,
                                          @RequestParam(required = false) String status) {
        access.requireMenu("orders");
        return Result.ok(transfers.adminList(AdminQueryParameters.page(page), orderId == null ? null : AdminQueryParameters.id(orderId), status));
    }
}
