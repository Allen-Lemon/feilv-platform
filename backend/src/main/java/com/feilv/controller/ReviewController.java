package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.dto.ApiDtos.ComplaintReq;
import com.feilv.dto.ApiDtos.ReviewReq;
import com.feilv.service.ReviewRiskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 评价与投诉接口（对应详细设计4.5/9.5 投诉赔付链路） */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewRiskService reviewRiskService;

    /** 提交评价（订单完成后7天内，一单一评，联动信用分） */
    @PostMapping("/reviews")
    public Result<Void> submit(@Valid @RequestBody ReviewReq req) {
        reviewRiskService.submitReview(UserContext.userId(), req);
        return Result.ok();
    }

    /** 提交投诉工单 */
    @PostMapping("/complaints")
    public Result<Map<String, Object>> complaint(@Valid @RequestBody ComplaintReq req) {
        long ticketId = reviewRiskService.complaint(UserContext.userId(), req);
        return Result.ok(Map.of("ticketId", ticketId, "sla", "一般投诉48小时内办结"));
    }
}
