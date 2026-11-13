package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.dto.AddressDtos.AddressReq;
import com.feilv.dto.UserProfileDtos.ProfileReq;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.dto.UserProfileDtos.WechatPhoneReq;
import com.feilv.service.AddressService;
import com.feilv.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {
    private final UserProfileService profileService;
    private final AddressService addressService;

    @GetMapping("/me")
    public Result<ProfileVO> me() {
        return Result.ok(profileService.me(UserContext.userId()));
    }

    @PutMapping("/profile")
    public Result<ProfileVO> profile(@Valid @RequestBody ProfileReq req) {
        return Result.ok(profileService.updateNickname(UserContext.userId(), req.nickname()));
    }

    @PostMapping("/wechat-phone")
    public Result<ProfileVO> wechatPhone(@Valid @RequestBody WechatPhoneReq req) {
        return Result.ok(profileService.bindWechatPhone(UserContext.userId(), req.code()));
    }

    @PostMapping("/address")
    public Result<Void> addAddress(@Valid @RequestBody AddressReq req) {
        addressService.save(UserContext.userId(), req, null);
        return Result.ok();
    }

    @PutMapping("/address/{id}")
    public Result<Void> updateAddress(@PathVariable long id, @Valid @RequestBody AddressReq req) {
        addressService.save(UserContext.userId(), req, id);
        return Result.ok();
    }

    @DeleteMapping("/address/{id}")
    public Result<Void> deleteAddress(@PathVariable long id) {
        addressService.delete(UserContext.userId(), id);
        return Result.ok();
    }

    @GetMapping("/address")
    public Result<List<com.feilv.domain.UserAddress>> listAddress() {
        return Result.ok(addressService.list(UserContext.userId()));
    }
}
