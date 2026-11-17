package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.dto.ApiDtos.OrderVO;
import com.feilv.dto.CustomTaskDtos.TaskVO;
import com.feilv.service.RiderOrderReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only carrier routes; no owner-payment/cancellation/review capabilities are exposed here. */
@RestController
@RequestMapping("/api/v1/rider/orders")
@RequiredArgsConstructor
public class RiderOrderReadController {
    private final RiderOrderReadService orders;

    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable long id) {
        return Result.ok(orders.detailForRider(UserContext.userId(), id));
    }

    @GetMapping("/{id}/custom-task")
    public Result<TaskVO> task(@PathVariable long id) {
        return Result.ok(orders.taskForRider(UserContext.userId(), id));
    }
}
