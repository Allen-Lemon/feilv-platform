package com.feilv.common;

/** 请求级用户上下文：由认证拦截器写入，Controller/Service 内直接取用 */
public class UserContext {

    public record CurrentUser(long id, String role) {}

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public static void set(CurrentUser u) { HOLDER.set(u); }
    public static CurrentUser get() { return HOLDER.get(); }
    public static long userId() {
        CurrentUser u = HOLDER.get();
        if (u == null) throw BizException.of(401, "未登录");
        return u.id();
    }
    public static void requireRole(String role) {
        CurrentUser u = HOLDER.get();
        if (u == null || !role.equals(u.role())) {
            throw BizException.of(403, "无权限执行该操作");
        }
    }
    public static void clear() { HOLDER.remove(); }
}
