package com.feilv.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.feilv.common.Result;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.dto.MiniappEnrollmentDtos;
import com.feilv.service.MiniappEnrollmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rider")
@RequiredArgsConstructor
public class MiniappEnrollmentController {
    private final MiniappEnrollmentService enrollment;

    @PostMapping("/enrollment")
    public Result<LoginVO> enroll(@RequestBody JsonNode body) {
        return Result.ok(enrollment.enroll(MiniappEnrollmentDtos.parse(body)));
    }
}
