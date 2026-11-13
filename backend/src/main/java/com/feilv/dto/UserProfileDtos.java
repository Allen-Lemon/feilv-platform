package com.feilv.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class UserProfileDtos {
    private UserProfileDtos() {}

    public record ProfileReq(@NotBlank @Size(max = 128) String nickname) {}
    public record WechatPhoneReq(@NotBlank @Size(max = 256) String code) {
        @Override public String toString() { return "WechatPhoneReq[code=REDACTED]"; }
    }
    public record ProfileVO(long userId, String nickname, String role, String mobile, boolean mobileBound) {}
}
