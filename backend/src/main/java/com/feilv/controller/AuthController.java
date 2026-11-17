package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.dto.ApiDtos.AdminLoginReq;
import com.feilv.dto.ApiDtos.AdminProfileVO;
import com.feilv.dto.ApiDtos.LoginReq;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.dto.ApiDtos.RiderRegisterReq;
import com.feilv.dto.ApiDtos.SmsCodeReq;
import com.feilv.common.UserContext;
import com.feilv.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口；公开入口逐项放行，admin-profile 仍要求管理员认证。 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final com.feilv.service.WechatLoginService wechatLoginService;

    /** code 仅由 wx.login 取得，后端向微信验证身份；不接收客户端 openid。 */
    @PostMapping("/wechat-login")
    public Result<LoginVO> wechatLogin(@Valid @RequestBody com.feilv.dto.ApiDtos.WechatLoginReq req) {
        return Result.ok(wechatLoginService.login(req.code(), Boolean.TRUE.equals(req.agreed())));
    }

    /** 发送验证码：60秒冷却、60秒有效期（演示模式回传验证码） */
    @PostMapping("/sms-code")
    public Result<String> smsCode(@Valid @RequestBody SmsCodeReq req) {
        return Result.ok(userService.sendSmsCode(req.mobile()));
    }

    /** 手机号验证码登录，未注册自动注册 */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginReq req) {
        return Result.ok(userService.login(req.mobile(), req.code()));
    }

    /** Retired: never select or promote a C-end account by a public mobile number. */
    @PostMapping("/rider-register")
    public Result<LoginVO> riderRegister(@Valid @RequestBody RiderRegisterReq req) {
        return Result.ok(userService.registerRider(req.mobile(), req.name(), req.code(), req.plateNo()));
    }

    /** 管理后台登录；账号和权限从数据库读取 */
    @PostMapping("/admin-login")
    public Result<LoginVO> adminLogin(@Valid @RequestBody AdminLoginReq req) {
        return Result.ok(userService.adminLogin(req.username(), req.password()));
    }

    @PostMapping("/admin-profile")
    public Result<AdminProfileVO> adminProfile() {
        UserContext.requireRole("ADMIN");
        return Result.ok(userService.adminProfile(UserContext.userId()));
    }
}
