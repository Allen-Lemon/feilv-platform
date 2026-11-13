package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.dto.WechatConfigDtos.*;
import com.feilv.service.WechatConfigAccess;
import com.feilv.service.WechatConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/system/wechat")
@RequiredArgsConstructor
public class WechatConfigController {
    private final WechatConfigService service;
    private final WechatConfigAccess access;
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }
    @GetMapping
    public Result<Overview> overview() { access.requireManage(); return Result.ok(service.overview()); }
    @PutMapping("/login")
    public Result<LoginView> login(@Valid @RequestBody LoginRequest body) {
        access.requireManage(); return Result.ok(service.saveLogin(body));
    }
    @PutMapping("/payment")
    public Result<PaymentView> payment(@Valid @RequestBody PaymentRequest body) {
        access.requireManage(); return Result.ok(service.savePayment(body));
    }
    @PostMapping("/validate")
    public Result<ValidationView> validate(@Valid @RequestBody ValidateRequest body) {
        access.requireManage(); return Result.ok(service.validate(body.section()));
    }
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public Result<Void> conflict() { return Result.fail(409, "配置版本已变化或数据冲突，请刷新后重试"); }
}
