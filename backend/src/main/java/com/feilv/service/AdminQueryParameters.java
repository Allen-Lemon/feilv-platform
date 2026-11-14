package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.enums.OrderStatus;

/** 显式校验查询参数，避免类型转换异常被全局兜底当作500。 */
public final class AdminQueryParameters {
    private AdminQueryParameters() {}

    public static int page(String value) {
        try {
            int page = Integer.parseInt(value);
            if (page >= 0) return page;
        } catch (NumberFormatException ignored) { }
        throw BizException.of(400, "page必须是从0开始的非负整数");
    }

    public static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw BizException.of(400, "用户ID必须是正整数");
    }

    public static Integer userStatus(String value) {
        if (value == null) return null;
        if ("0".equals(value)) return 0;
        if ("1".equals(value)) return 1;
        throw BizException.of(400, "status必须是0或1");
    }

    public static Boolean mobileBound(String value) {
        if (value == null) return null;
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw BizException.of(400, "mobileBound必须是true或false");
    }

    public static OrderStatus orderStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OrderStatus.valueOf(value); }
        catch (IllegalArgumentException e) { throw BizException.of(400, "订单status无效"); }
    }
}
