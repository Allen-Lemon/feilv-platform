package com.feilv.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** 后台专用白名单视图，禁止直接序列化用户实体。 */
public final class AdminUserDtos {
    private AdminUserDtos() {}

    public record UserVO(long userId, String nickname, String avatarUrl, String role, String mobile,
                         boolean mobileBound, int status, LocalDateTime createdTime) {}

    public record StatusReq(@NotNull @Min(0) @Max(1) Integer status) {}
}
