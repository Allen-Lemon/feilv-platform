package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.dto.ManualPhoneBindingDtos.SmsCodeReq;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.service.DevelopmentPhoneBindingService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user/phone")
@RequiredArgsConstructor
public class DevelopmentPhoneBindingController {
    private final DevelopmentPhoneBindingService development;

    public record BindingOptions(boolean developmentBypass, String reason, String message) {}

    @GetMapping("/options")
    public Result<BindingOptions> options(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.ok(new BindingOptions(development.enabled(), development.status().name(), development.modeMessage()));
    }

    @PostMapping("/development")
    public Result<ProfileVO> bind(@Valid @RequestBody SmsCodeReq req, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.ok(development.bind(UserContext.userId(), req.mobile()));
    }
}
