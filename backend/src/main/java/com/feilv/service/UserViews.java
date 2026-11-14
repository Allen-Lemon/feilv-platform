package com.feilv.service;

import com.feilv.domain.User;
import com.feilv.dto.AdminUserDtos.UserVO;

/** 后台用户信息的唯一出参转换点，不输出完整手机号或微信凭据。 */
public final class UserViews {
    private UserViews() {}

    public static String text(String value) { return value == null ? "" : value; }

    public static boolean mobileBound(User user) {
        return user != null && user.getMobile() != null && !user.getMobile().isBlank();
    }

    public static String maskedMobile(User user) {
        if (!mobileBound(user)) return "";
        String mobile = user.getMobile();
        // 非标准历史号码也不得原样回显。
        return mobile.matches("1[3-9][0-9]{9}")
                ? mobile.substring(0, 3) + "****" + mobile.substring(7) : "****";
    }

    public static UserVO user(User user) {
        return new UserVO(user.getId(), text(user.getNickname()), text(user.getAvatarUrl()), user.getRole(),
                maskedMobile(user), mobileBound(user), user.getStatus(), user.getCreatedTime());
    }
}
