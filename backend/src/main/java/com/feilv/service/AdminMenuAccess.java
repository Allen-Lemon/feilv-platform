package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.AdminMenu;
import com.feilv.domain.AdminRoleMenu;
import com.feilv.domain.AdminUserRole;
import com.feilv.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 后台接口的实时授权：JWT角色、主档、启用账号、启用角色及启用菜单均需匹配。 */
@Service
@RequiredArgsConstructor
public class AdminMenuAccess {
    private final UserRepository users;
    private final AdminAccountRepository accounts;
    private final AdminUserRoleRepository userRoles;
    private final AdminRoleRepository roles;
    private final AdminRoleMenuRepository roleMenus;
    private final AdminMenuRepository menus;

    /** 即使旧库尚未执行迁移，退役菜单也不再被 profile、菜单树或授权服务使用。 */
    public static boolean isRetiredMenu(String code, String path) {
        return "marketing".equals(code) || "content".equals(code) || "risk".equals(code)
                || (path != null && path.matches("(?i)^/(?:marketing|content|risk)(?:[/?#].*)?$"));
    }

    public static boolean isAvailableMenu(AdminMenu menu) {
        return menu.getEnabled() == 1 && !isRetiredMenu(menu.getCode(), menu.getPath());
    }

    public void requireMenu(String menuCode) {
        var current = UserContext.get();
        if (current == null) throw BizException.of(401, "请先登录");
        if (isRetiredMenu(menuCode, null)) throw BizException.of(403, "此菜单已下线");
        var user = users.findById(current.id()).orElseThrow(() -> BizException.of(403, "无管理后台权限"));
        if (!"ADMIN".equals(current.role()) || !"ADMIN".equals(user.getRole()) || user.getStatus() != 1
                || !accounts.existsByUserIdAndEnabled(user.getId(), 1)) {
            throw BizException.of(403, "无管理后台权限");
        }
        var roleIds = userRoles.findByUserId(user.getId()).stream().map(AdminUserRole::getRoleId).toList();
        var active = roles.findAllById(roleIds).stream().filter(r -> r.getEnabled() == 1).toList();
        if (active.stream().anyMatch(r -> "SUPER_ADMIN".equals(r.getCode()))) return;
        var menuIds = active.stream().flatMap(r -> roleMenus.findByRoleId(r.getId()).stream())
                .map(AdminRoleMenu::getMenuId).distinct().toList();
        if (menus.findAllById(menuIds).stream().anyMatch(m -> isAvailableMenu(m) && menuCode.equals(m.getCode()))) return;
        throw BizException.of(403, "无此菜单访问权限");
    }
}
