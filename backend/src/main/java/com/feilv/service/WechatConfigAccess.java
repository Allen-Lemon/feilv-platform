package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 敏感配置访问必须验证数据库中的实时账号状态及授权，不能仅信任JWT中的ADMIN。 */
@Service
@RequiredArgsConstructor
public class WechatConfigAccess {
    private final UserRepository users;
    private final AdminAccountRepository accounts;
    private final AdminUserRoleRepository userRoles;
    private final AdminRoleRepository roles;
    private final AdminRoleMenuRepository roleMenus;
    private final AdminMenuRepository menus;

    public void requireManage() {
        requireAccess(false);
    }

    /** 账号、角色、授权管理只能由超级管理员执行，避免普通ADMIN自行提升权限。 */
    public void requireSystemAdmin() {
        requireAccess(true);
    }

    private void requireAccess(boolean superOnly) {
        var current = UserContext.get();
        if (current == null) throw BizException.of(401, "请先登录");
        var user = users.findById(current.id()).orElseThrow(() -> BizException.of(403, "无微信配置管理权限"));
        if (!"ADMIN".equals(current.role()) || !"ADMIN".equals(user.getRole()) || user.getStatus() != 1
                || !accounts.existsByUserIdAndEnabled(user.getId(), 1)) throw BizException.of(403, "无微信配置管理权限");
        var roleIds = userRoles.findByUserId(user.getId()).stream().map(com.feilv.domain.AdminUserRole::getRoleId).toList();
        var active = roles.findAllById(roleIds).stream().filter(r -> r.getEnabled() == 1).toList();
        if (active.stream().anyMatch(r -> "SUPER_ADMIN".equals(r.getCode()))) return;
        if (superOnly) throw BizException.of(403, "仅超级管理员可维护账号、角色和授权");
        var menuIds = active.stream().flatMap(r -> roleMenus.findByRoleId(r.getId()).stream())
                .map(com.feilv.domain.AdminRoleMenu::getMenuId).distinct().toList();
        if (menus.findAllById(menuIds).stream().anyMatch(m -> m.getEnabled() == 1 && "wechat-settings".equals(m.getCode()))) return;
        throw BizException.of(403, "无微信配置管理权限");
    }
}
