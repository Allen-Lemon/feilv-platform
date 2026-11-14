package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.dto.ManualPhoneBindingDtos.BindPhoneReq;
import com.feilv.dto.ManualPhoneBindingDtos.SmsCodeReq;
import com.feilv.dto.ManualPhoneBindingDtos.SmsCodeVO;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.service.SmsPhoneBindingService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/phone")
@RequiredArgsConstructor
public class ManualPhoneBindingController {
    private final SmsPhoneBindingService binding;

    @PostMapping("/sms-code")
    public Result<SmsCodeVO> sendCode(@Valid @RequestBody SmsCodeReq req, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.ok(binding.sendCode(UserContext.userId(), req.mobile()));
    }

    @PostMapping
    public Result<ProfileVO> bindPhone(@Valid @RequestBody BindPhoneReq req, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.ok(binding.bindPhone(UserContext.userId(), req.mobile(), req.code()));
    }
}
