package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.controller.SystemAdminController;
import com.feilv.domain.*;
import com.feilv.dto.ApiDtos.AdminMenuVO;
import com.feilv.repository.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Repository doubles only: cover legacy enabled rows without touching any database. */
class AdminMenuRetirementTest {
    private final UserRepository users = mock(UserRepository.class);
    private final RiderRepository riders = mock(RiderRepository.class);
    private final MessageService messages = mock(MessageService.class);
    private final AdminAccountRepository accounts = mock(AdminAccountRepository.class);
    private final AdminRoleRepository roles = mock(AdminRoleRepository.class);
    private final AdminMenuRepository menus = mock(AdminMenuRepository.class);
    private final AdminUserRoleRepository userRoles = mock(AdminUserRoleRepository.class);
    private final AdminRoleMenuRepository roleMenus = mock(AdminRoleMenuRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final WechatConfigAccess configAccess = mock(WechatConfigAccess.class);
    private final UserService userService = new UserService(users, riders, messages, accounts, roles, menus, userRoles, roleMenus);
    private final AdminMenuAccess access = new AdminMenuAccess(users, accounts, userRoles, roles, roleMenus, menus);
    private final SystemAdminController system = new SystemAdminController(menus, roles, accounts, userRoles, roleMenus, users, audit, configAccess);
    private AdminRole role;
    private List<AdminMenu> legacyMenus;
    private static final List<String> KEPT = List.of("dashboard", "orders", "users", "tasks", "complaints", "system");

    @BeforeEach void setup() {
        UserContext.set(new UserContext.CurrentUser(1L, "ADMIN"));
        var user = new User(); user.setId(1L); user.setRole("ADMIN"); user.setStatus(1); user.setNickname("运营");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        var account = new AdminAccount(); account.setUserId(1L); account.setUsername("operator"); account.setEnabled(1);
        when(accounts.findAll()).thenReturn(List.of(account));
        when(accounts.existsByUserIdAndEnabled(1L, 1)).thenReturn(true);
        var userRole = new AdminUserRole(); userRole.setUserId(1L); userRole.setRoleId(2L);
        when(userRoles.findByUserId(1L)).thenReturn(List.of(userRole));
        role = new AdminRole(); role.setId(2L); role.setCode("OPERATOR"); role.setEnabled(1);
        when(roles.findAllById(List.of(2L))).thenReturn(List.of(role));
        legacyMenus = List.of(
                menu(1L, "dashboard", "/dashboard"), menu(2L, "orders", "/orders"), menu(3L, "users", "/users"),
                menu(4L, "tasks", "/tasks"), menu(5L, "complaints", "/complaints"), menu(6L, "system", "/system"),
                menu(7L, "marketing", "/marketing"), menu(8L, "content", "/content"), menu(9L, "risk", "/risk"),
                menu(10L, "legacy-risk", "/risk/reports"), menu(11L, "legacy-content", "/content?old=1"),
                menu(12L, "legacy-marketing", "/MARKETING/"), menu(13L, "disabled", "/disabled"));
        legacyMenus.get(12).setEnabled(0);
        var links = legacyMenus.stream().map(menu -> {
            var link = new AdminRoleMenu(); link.setRoleId(2L); link.setMenuId(menu.getId()); return link;
        }).toList();
        when(roleMenus.findByRoleId(2L)).thenReturn(links);
        when(menus.findAllById(any())).thenReturn(legacyMenus);
        when(menus.findByEnabledOrderBySortAscIdAsc(1)).thenReturn(legacyMenus);
    }

    @AfterEach void clear() { UserContext.clear(); }

    @Test void profileExcludesLegacyEnabledMenusForOrdinaryAndSuperAdminsWithoutMutatingRows() {
        for (String code : List.of("OPERATOR", "SUPER_ADMIN")) {
            role.setCode(code);
            var profile = userService.adminProfile(1L);
            assertEquals(KEPT, profile.menus().stream().map(AdminMenuVO::code).toList());
            assertEquals(List.of(code), profile.roles());
        }
        // Filtering is read-only; historical menu rows/role links are not deleted or rewritten.
        assertEquals(1, legacyMenus.get(6).getEnabled());
        verify(menus, never()).save(any());
        verify(roleMenus, never()).save(any());
        verify(roleMenus, never()).deleteAll(any());
    }

    @Test void systemMenuTreeHidesOldRowsAndPreservesBusinessTools() {
        assertEquals(KEPT, system.menus().getData().stream().map(AdminMenu::getCode).toList());
        verify(menus, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test void enabledLegacyGrantsAndSuperAdminBypassCannotAuthorizeRetiredCodes() {
        for (String roleCode : List.of("OPERATOR", "SUPER_ADMIN")) {
            role.setCode(roleCode);
            for (String code : List.of("marketing", "content", "risk")) {
                assertEquals(403, assertThrows(BizException.class, () -> access.requireMenu(code)).getCode());
            }
            for (String code : KEPT) assertDoesNotThrow(() -> access.requireMenu(code));
        }
    }

    @Test void aliasesPointingAtRetiredPathsCannotGrantAccessToOrdinaryRoles() {
        for (String code : List.of("legacy-risk", "legacy-content", "legacy-marketing", "disabled")) {
            assertEquals(403, assertThrows(BizException.class, () -> access.requireMenu(code)).getCode());
        }
    }

    @Test void retiredMenuSaveCannotRecreateCodeOrOldPath() {
        for (String code : List.of("marketing", "content", "risk")) {
            assertEquals(400, system.saveMenu(Map.of("code", code, "path", "/other")).getCode());
            assertEquals(400, system.saveMenu(Map.of("code", "alias", "path", "/" + code)).getCode());
            assertEquals(400, system.saveMenu(Map.of("code", "alias", "path", "/" + code + "/old")).getCode());
        }
        verify(menus, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test void remainingMenusCanStillBeSaved() {
        when(menus.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        for (String code : List.of("orders", "users", "tasks", "complaints")) {
            var result = system.saveMenu(Map.of("code", code, "name", code, "path", "/" + code));
            assertEquals(0, result.getCode());
            assertEquals(code, result.getData().getCode());
        }
    }

    @Test void anonymousStillGetsLoginErrorForRetiredMenu() {
        UserContext.clear();
        assertEquals(401, assertThrows(BizException.class, () -> access.requireMenu("risk")).getCode());
    }

    @Test void matchingOnlyRetiresWholePathSegmentsAndHandlesEmptyPaths() {
        assertFalse(AdminMenuAccess.isRetiredMenu("risk-analysis", "/risk-analysis"));
        assertFalse(AdminMenuAccess.isRetiredMenu("users", null));
        assertFalse(AdminMenuAccess.isRetiredMenu("complaints", "/complaints"));
        for (String path : List.of("/risk", "/risk/", "/RISK/reports", "/risk?old=1", "/risk#old")) {
            assertTrue(AdminMenuAccess.isRetiredMenu("alias", path), path);
        }
    }

    private static AdminMenu menu(long id, String code, String path) {
        var menu = new AdminMenu(); menu.setId(id); menu.setCode(code); menu.setName(code); menu.setPath(path);
        menu.setSort((int) id); menu.setEnabled(1); return menu;
    }
}
