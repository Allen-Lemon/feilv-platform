package com.feilv.service;

import com.feilv.common.*;
import com.feilv.domain.*;
import com.feilv.repository.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatConfigAccessTest {
    UserRepository users = mock(UserRepository.class);
    AdminAccountRepository accounts = mock(AdminAccountRepository.class);
    AdminUserRoleRepository userRoles = mock(AdminUserRoleRepository.class);
    AdminRoleRepository roles = mock(AdminRoleRepository.class);
    AdminRoleMenuRepository roleMenus = mock(AdminRoleMenuRepository.class);
    AdminMenuRepository menus = mock(AdminMenuRepository.class);
    WechatConfigAccess access = new WechatConfigAccess(users, accounts, userRoles, roles, roleMenus, menus);
    User user; AdminRole role;
    @BeforeEach void setup() {
        UserContext.set(new UserContext.CurrentUser(1, "ADMIN"));
        user = new User(); user.setId(1L); user.setRole("ADMIN"); user.setStatus(1);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(accounts.existsByUserIdAndEnabled(1L, 1)).thenReturn(true);
        var link = new AdminUserRole(); link.setRoleId(2L); link.setUserId(1L);
        when(userRoles.findByUserId(1L)).thenReturn(List.of(link));
        role = new AdminRole(); role.setId(2L); role.setCode("OPERATOR"); role.setEnabled(1);
        when(roles.findAllById(List.of(2L))).thenReturn(List.of(role));
    }
    @AfterEach void clear() { UserContext.clear(); }
    @Test void adminJwtAloneIsNotEnough() { assertThrows(BizException.class, access::requireManage); }
    @Test void enabledSuperAdminCanManageButDisabledRoleCannot() {
        role.setCode("SUPER_ADMIN"); assertDoesNotThrow(access::requireManage);
        role.setEnabled(0); assertThrows(BizException.class, access::requireManage);
    }
    @Test void enabledMenuGrantWorksAndDisabledMenuDoesNot() {
        var link = new AdminRoleMenu(); link.setRoleId(2L); link.setMenuId(3L);
        when(roleMenus.findByRoleId(2L)).thenReturn(List.of(link));
        var menu = new AdminMenu(); menu.setId(3L); menu.setCode("wechat-settings"); menu.setEnabled(1);
        when(menus.findAllById(List.of(3L))).thenReturn(List.of(menu));
        assertDoesNotThrow(access::requireManage);
        assertThrows(BizException.class, access::requireSystemAdmin);
        menu.setEnabled(0); assertThrows(BizException.class, access::requireManage);
    }
    @Test void frozenAccountOrAnonymousIsRejected() {
        role.setCode("SUPER_ADMIN"); user.setStatus(0); assertThrows(BizException.class, access::requireManage);
        UserContext.clear(); assertEquals(401, assertThrows(BizException.class, access::requireManage).getCode());
    }
}
