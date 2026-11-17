package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.controller.TestAuth;
import com.feilv.domain.Rider;
import com.feilv.domain.User;
import com.feilv.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoAuthBoundaryTest {
    private final UserRepository users = mock(UserRepository.class);
    private final RiderRepository riders = mock(RiderRepository.class);
    private final MessageService messages = mock(MessageService.class);
    private final AdminAccountRepository accounts = mock(AdminAccountRepository.class);
    private final AdminRoleRepository roles = mock(AdminRoleRepository.class);
    private final AdminMenuRepository menus = mock(AdminMenuRepository.class);
    private final AdminUserRoleRepository userRoles = mock(AdminUserRoleRepository.class);
    private final AdminRoleMenuRepository roleMenus = mock(AdminRoleMenuRepository.class);
    private final WechatConfigService settings = mock(WechatConfigService.class);

    @Test void smsRequiresExplicitOptInAndDevelopmentProfileWithNoProtectedProfile() {
        assertFalse(policy(true).enabled());
        for (String profile : new String[]{"dev", "development", "local"}) {
            assertTrue(policy(true, profile).enabled(), profile);
            assertFalse(policy(false, profile).enabled(), profile);
            for (String protectedProfile : new String[]{"prod", "production", "docker"}) {
                assertFalse(policy(true, profile, protectedProfile).enabled(), profile + "/" + protectedProfile);
            }
        }
        for (String profile : new String[]{"test", "prod", "production", "docker"}) {
            assertFalse(policy(true, profile).enabled(), profile);
        }
    }

    @Test void disabledDemoRejectsBeforeReadingOrMutatingAccountsOrSendingMessages() {
        UserService service = service(policy(false, "local"));
        assertCode(403, () -> service.sendSmsCode("13800000001"));
        assertCode(403, () -> service.login("13800000001", "123456"));
        verifyNoInteractions(users, riders, messages, accounts, roles, menus, userRoles, roleMenus, settings);
    }

    @Test void retiredPublicEnrollmentNeverReadsOrPromotesAnAccountEvenInDemoMode() {
        UserService service = service(policy(true, "local"));
        assertCode(410, () -> service.registerRider("13800000001", "申请人", "123456", "贵A12345"));
        verifyNoInteractions(users, riders, messages, accounts, roles, menus, userRoles, roleMenus, settings);
    }

    @Test void demoCannotAuthenticateAdminFrozenUserOrFrozenRiderAndDoesNotLogCredentials() {
        UserService service = service(policy(true, "local"));
        String[] mobiles = {"13800000001", "13800000002", "13800000003"};
        for (int i = 0; i < mobiles.length; i++) {
            User user = new User(); user.setId(42L + i); user.setMobile(mobiles[i]);
            user.setRole(i == 0 ? "ADMIN" : i == 2 ? "RIDER" : "USER");
            user.setStatus(i == 1 ? 0 : 1);
            when(users.findByMobile(mobiles[i])).thenReturn(Optional.of(user));
            if (i == 2) {
                Rider rider = new Rider(); rider.setUserId(user.getId()); rider.setStatus("FROZEN");
                when(riders.findByUserId(user.getId())).thenReturn(Optional.of(rider));
            }
            String code = service.sendSmsCode(mobiles[i]);
            assertTrue(code.matches("[0-9]{6}"));
            int index = i;
            assertCode(403, () -> service.login(mobiles[index], code));
        }
        verify(users, never()).save(any());
        verifyNoInteractions(messages, settings, accounts, roles, menus, userRoles, roleMenus);
    }

    @Test void demoRiderConsumerLoginHasUserSourceAndPreservesSingleUseCode() {
        UserService service = service(policy(true, "local"));
        User user = new User(); user.setId(42L); user.setRole("RIDER"); user.setMobile("13800000001");
        Rider rider = new Rider(); rider.setUserId(42L);
        when(users.findByMobile(user.getMobile())).thenReturn(Optional.of(user));
        when(riders.findByUserId(42L)).thenReturn(Optional.of(rider));
        when(settings.effectiveLogin("USER")).thenReturn(new WechatConfigService.LoginCredentials(TestAuth.USER_APP_ID, "not-used"));
        String code = service.sendSmsCode(user.getMobile());
        var login = service.login(user.getMobile(), code);
        var claims = TestAuth.tokens().parse(login.token());
        assertNotNull(claims); assertEquals("RIDER", claims.role); assertEquals("USER", claims.aud);
        assertEquals(TestAuth.USER_APP_ID, claims.appId); assertEquals(42L, login.userId());
        assertCode(4010, () -> service.login(user.getMobile(), code));
        verify(users, never()).save(any()); verifyNoInteractions(messages);
    }

    private DemoAuthPolicy policy(boolean requested, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new DemoAuthPolicy(requested, environment);
    }
    private UserService service(DemoAuthPolicy policy) {
        return new UserService(users, riders, messages, accounts, roles, menus, userRoles, roleMenus,
                TestAuth.tokens(), policy, settings);
    }
    private void assertCode(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BizException.class, action).getCode());
    }
}
