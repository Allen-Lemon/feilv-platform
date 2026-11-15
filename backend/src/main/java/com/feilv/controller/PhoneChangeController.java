package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.dto.ManualPhoneBindingDtos.BindPhoneReq;
import com.feilv.dto.ManualPhoneBindingDtos.SmsCodeReq;
import com.feilv.dto.ManualPhoneBindingDtos.SmsCodeVO;
import com.feilv.dto.ManualPhoneBindingDtos.WechatChangeReq;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.service.PhoneChangeService;
import com.feilv.service.SmsPhoneBindingService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class PhoneChangeController {
    private final PhoneChangeService changes;
    private final SmsPhoneBindingService sms;

    // 参数解析/校验失败也不可缓存，避免错误响应被中间缓存保存。
    @org.springframework.web.bind.annotation.ModelAttribute
    public void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/wechat-phone/change")
    public Result<ProfileVO> wechat(@Valid @RequestBody WechatChangeReq req, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.ok(changes.changeWechatPhone(UserContext.userId(), req.code()));
    }

    @PostMapping("/phone/change/sms-code")
    public Result<SmsCodeVO> sendCode(@Valid @RequestBody SmsCodeReq req, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.ok(sms.sendChangeCode(UserContext.userId(), req.mobile()));
    }

    @PostMapping("/phone/change")
    public Result<ProfileVO> sms(@Valid @RequestBody BindPhoneReq req, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.ok(sms.changePhone(UserContext.userId(), req.mobile(), req.code()));
    }

    @PostMapping("/phone/development/change")
    public Result<ProfileVO> development(@Valid @RequestBody SmsCodeReq req, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.ok(changes.changeDevelopmentPhone(UserContext.userId(), req.mobile()));
    }
}
