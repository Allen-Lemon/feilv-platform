package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.User;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No application context or datasource; both phone entry points must follow the signed client. */
class MiniappPhoneSourceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final WechatIdentityRepository identities = mock(WechatIdentityRepository.class);
    private final WechatConfigService settings = mock(WechatConfigService.class);
    private final WechatPhoneClient phone = mock(WechatPhoneClient.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final WechatConfigService.LoginCredentials rider = new WechatConfigService.LoginCredentials("rider-app", "rider-secret");
    private User user;
    private UserProfileService profiles;
    private PhoneChangeService changes;

    @BeforeEach
    void setup() {
        user = new User(); user.setId(42L); user.setRole("RIDER");
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        when(users.saveAndFlush(user)).thenReturn(user);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> new SimpleTransactionStatus());
        when(settings.effectiveLogin("RIDER")).thenReturn(rider);
        when(identities.existsByUserIdAndAppId(42L, "rider-app")).thenReturn(true);
        when(phone.exchange("code", rider)).thenReturn(new WechatPhoneClient.Phone("rider-app", "13800138000"));
        profiles = new UserProfileService(users, identities, settings, phone, manager);
        changes = new PhoneChangeService(users, identities, settings, phone,
                mock(DevelopmentPhoneBindingService.class), mock(AuditService.class), mock(EntityManager.class), manager);
        UserContext.set(new UserContext.CurrentUser(42L, "RIDER", "RIDER", "rider-app"));
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void riderBindingAndChangeOnlyUseRiderCredentials() {
        assertTrue(profiles.bindWechatPhone(42L, "code").mobileBound());
        user.setMobile("13900139000");
        assertEquals("138****8000", changes.changeWechatPhone(42L, "code").mobile());
        verify(settings, times(4)).effectiveLogin("RIDER");
        verify(settings, never()).effectiveLogin();
        verify(settings, never()).effectiveLogin("USER");
        verify(phone, times(2)).exchange("code", rider);
    }

    @Test
    void userSourceIsIndependentOfTheLiveBusinessRole() {
        var customer = new WechatConfigService.LoginCredentials("user-app", "user-secret");
        UserContext.set(new UserContext.CurrentUser(42L, "RIDER", "USER", "user-app"));
        when(settings.effectiveLogin("USER")).thenReturn(customer);
        when(identities.existsByUserIdAndAppId(42L, "user-app")).thenReturn(true);
        when(phone.exchange("code", customer)).thenReturn(new WechatPhoneClient.Phone("user-app", "13800138000"));
        assertTrue(profiles.bindWechatPhone(42L, "code").mobileBound());
        verify(phone).exchange("code", customer);
        verify(settings, never()).effectiveLogin("RIDER");
    }

    @Test
    void mismatchedSignedAppAndOtherAccountAreRejectedBeforeExchange() {
        UserContext.set(new UserContext.CurrentUser(42L, "RIDER", "RIDER", "old-rider-app"));
        assertCode(401, () -> profiles.bindWechatPhone(42L, "code"));
        user.setMobile("13900139000");
        assertCode(401, () -> changes.changeWechatPhone(42L, "code"));
        UserContext.set(new UserContext.CurrentUser(99L, "RIDER", "RIDER", "rider-app"));
        assertCode(403, () -> profiles.bindWechatPhone(42L, "code"));
        assertCode(403, () -> changes.changeWechatPhone(42L, "code"));
        verifyNoInteractions(phone);
    }

    @Test
    void signedUnknownSourceCannotFallBackToUserCredentials() {
        UserContext.set(new UserContext.CurrentUser(42L, "RIDER", "OTHER", "rider-app"));
        assertCode(401, () -> profiles.bindWechatPhone(42L, "code"));
        user.setMobile("13900139000");
        assertCode(401, () -> changes.changeWechatPhone(42L, "code"));
        verifyNoInteractions(settings, phone);
    }

    @Test
    void appIdOrSecretRotationDuringExchangeNeverWrites() {
        for (var rotated : new WechatConfigService.LoginCredentials[]{
                new WechatConfigService.LoginCredentials("rider-app", "rotated"),
                new WechatConfigService.LoginCredentials("new-app", "rider-secret")}) {
            user.setMobile(null);
            when(settings.effectiveLogin("RIDER")).thenReturn(rider, rotated);
            assertCode(409, () -> profiles.bindWechatPhone(42L, "code"));
            user.setMobile("13900139000");
            when(settings.effectiveLogin("RIDER")).thenReturn(rider, rotated);
            assertCode(409, () -> changes.changeWechatPhone(42L, "code"));
            assertEquals("13900139000", user.getMobile());
        }
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void unsignedLegacyFixtureContextCannotBeUsedFromAnHttpRequest() {
        UserContext.set(new UserContext.CurrentUser(42L, "RIDER"));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
        assertCode(401, () -> profiles.bindWechatPhone(42L, "code"));
        user.setMobile("13900139000");
        assertCode(401, () -> changes.changeWechatPhone(42L, "code"));
        verifyNoInteractions(settings, phone);
    }

    private static void assertCode(int expected, Runnable action) {
        assertEquals(expected, assertThrows(BizException.class, action::run).getCode());
    }
}
