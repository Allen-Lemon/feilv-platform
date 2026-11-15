package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhoneChangeServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final WechatIdentityRepository identities = mock(WechatIdentityRepository.class);
    private final WechatConfigService settings = mock(WechatConfigService.class);
    private final WechatPhoneClient phone = mock(WechatPhoneClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final WechatConfigService.LoginCredentials credentials = new WechatConfigService.LoginCredentials("change-app", "secret");
    private User user;
    private DevelopmentPhoneBindingService development;
    private PhoneChangeService changes;

    @BeforeEach
    void setup() {
        user = new User(); user.setId(42L); user.setMobile("13900139000"); user.setRole("RIDER");
        user.setNickname("原昵称"); user.setAvatarUrl("/original.png");
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        when(users.saveAndFlush(user)).thenReturn(user);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> new SimpleTransactionStatus());
        when(settings.effectiveLogin()).thenReturn(credentials);
        when(identities.existsByUserIdAndAppId(42L, credentials.appId())).thenReturn(true);
        when(phone.exchange("authorization", credentials)).thenReturn(new WechatPhoneClient.Phone(credentials.appId(), "13800138000"));
        environment.setActiveProfiles("local");
        development = new DevelopmentPhoneBindingService(users, manager, environment, true);
        changes = new PhoneChangeService(users, identities, settings, phone, development, audit, entityManager, manager);
    }

    @Test
    void wechatChangesOnlyMobileAndAuditsMaskedNumbers() {
        var view = changes.changeWechatPhone(42L, "authorization");
        assertEquals(42L, view.userId()); assertEquals("RIDER", view.role());
        assertEquals("原昵称", view.nickname()); assertEquals("/original.png", view.avatarUrl());
        assertEquals("138****8000", view.mobile()); assertTrue(view.mobileBound());
        verify(audit).record("PHONE_CHANGE", "userId=42,channel=WECHAT,old=139****9000,new=138****8000");
        verify(identities, times(2)).existsByUserIdAndAppId(42L, credentials.appId());
        verify(users).saveAndFlush(user);
    }

    @Test
    void sameNumberStillExchangesAndVerifiesIdentityBeforeIdempotentSuccess() {
        user.setMobile("13800138000");
        assertTrue(changes.changeWechatPhone(42L, "authorization").mobileBound());
        verify(phone).exchange("authorization", credentials);
        verify(users, never()).saveAndFlush(any());
        verify(audit).record("PHONE_CHANGE", "userId=42,channel=WECHAT,old=138****8000,new=138****8000");
    }

    @Test
    void exchangeFailureNeverStartsWriteOrLeaksCode() {
        when(phone.exchange(anyString(), any())).thenThrow(BizException.of(502, "微信兑换失败"));
        assertError(502, () -> changes.changeWechatPhone(42L, "authorization"));
        verify(users, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(audit, manager);
        assertEquals("13900139000", user.getMobile());
    }

    @Test
    void rejectsWrongAppAndMalformedWechatPhoneBeforeWrite() {
        for (var response : new WechatPhoneClient.Phone[]{null, new WechatPhoneClient.Phone("wrong-app", "13800138000"),
                new WechatPhoneClient.Phone(credentials.appId(), null), new WechatPhoneClient.Phone(credentials.appId(), "+8613800138000")}) {
            when(phone.exchange(anyString(), any())).thenReturn(response);
            assertError(502, () -> changes.changeWechatPhone(42L, "authorization"));
        }
        verify(users, never()).findByIdForUpdate(anyLong()); verifyNoInteractions(audit);
    }

    @Test
    void freezeRoleChangeOrMobileChangeDuringExchangeIsRecheckedUnderWriteLock() {
        doAnswer(call -> {
            user.setStatus(0); return new WechatPhoneClient.Phone(credentials.appId(), "13800138000");
        }).when(phone).exchange(anyString(), any());
        assertError(403, () -> changes.changeWechatPhone(42L, "authorization"));
        user.setStatus(1);
        doAnswer(call -> {
            user.setRole("ADMIN"); return new WechatPhoneClient.Phone(credentials.appId(), "13800138000");
        }).when(phone).exchange(anyString(), any());
        assertError(403, () -> changes.changeWechatPhone(42L, "authorization"));
        user.setRole("RIDER");
        doAnswer(call -> {
            user.setMobile("13700137000"); return new WechatPhoneClient.Phone(credentials.appId(), "13800138000");
        }).when(phone).exchange(anyString(), any());
        assertError(409, () -> changes.changeWechatPhone(42L, "authorization"));
        assertEquals("13700137000", user.getMobile());
        verify(users, never()).saveAndFlush(any()); verifyNoInteractions(audit);
    }

    @Test
    void appIdAndSecretRotationOrIdentityRemovalDuringExchangeInvalidateResponse() {
        for (var next : new WechatConfigService.LoginCredentials[]{
                new WechatConfigService.LoginCredentials("new-app", "secret"),
                new WechatConfigService.LoginCredentials(credentials.appId(), "new-secret")}) {
            when(settings.effectiveLogin()).thenReturn(credentials, next);
            assertError(409, () -> changes.changeWechatPhone(42L, "authorization"));
        }
        when(settings.effectiveLogin()).thenReturn(credentials);
        when(identities.existsByUserIdAndAppId(42L, credentials.appId())).thenReturn(true, false);
        assertError(403, () -> changes.changeWechatPhone(42L, "authorization"));
        verify(users, never()).saveAndFlush(any()); verifyNoInteractions(audit);
    }

    @Test
    void missingUnboundFrozenAdminOrMissingAppIdentityNeverExchanges() {
        user.setMobile(null); assertError(409, () -> changes.changeWechatPhone(42L, "authorization"));
        user.setMobile("13900139000"); user.setStatus(0);
        assertError(403, () -> changes.changeWechatPhone(42L, "authorization"));
        user.setStatus(1); user.setRole("ADMIN");
        assertError(403, () -> changes.changeWechatPhone(42L, "authorization"));
        user.setRole("USER");
        when(identities.existsByUserIdAndAppId(anyLong(), anyString())).thenReturn(false);
        assertError(403, () -> changes.changeWechatPhone(42L, "authorization"));
        when(users.findById(42L)).thenReturn(Optional.empty());
        assertError(403, () -> changes.changeWechatPhone(42L, "authorization"));
        verifyNoInteractions(phone, audit);
    }

    @Test
    void developmentUsesExistingEnvironmentProtectionAndNoClientBypass() {
        for (String[] profiles : new String[][]{{}, {"test"}, {"local", "prod"}, {"development", "docker"}, {"production"}}) {
            environment.setActiveProfiles(profiles);
            assertError(403, () -> changes.changeDevelopmentPhone(42L, "13800138000"));
        }
        environment.setActiveProfiles("local");
        var disabled = new DevelopmentPhoneBindingService(users, manager, environment, false);
        var service = new PhoneChangeService(users, identities, settings, phone, disabled, audit, entityManager, manager);
        assertError(403, () -> service.changeDevelopmentPhone(42L, "13800138000"));
        verify(users, never()).findByIdForUpdate(anyLong()); verifyNoInteractions(phone, audit);
    }

    @Test
    void developmentStillRequiresBoundActiveRoleAndUniqueMobile() {
        user.setMobile(null); assertError(409, () -> changes.changeDevelopmentPhone(42L, "13800138000"));
        user.setMobile("13900139000"); user.setStatus(0);
        assertError(403, () -> changes.changeDevelopmentPhone(42L, "13800138000"));
        user.setStatus(1); user.setRole("ADMIN");
        assertError(403, () -> changes.changeDevelopmentPhone(42L, "13800138000"));
        user.setRole("RIDER");
        User other = new User(); other.setId(43L); other.setRole("ADMIN"); other.setStatus(0);
        when(users.findByMobile("13800138000")).thenReturn(Optional.of(other));
        assertError(409, () -> changes.changeDevelopmentPhone(42L, "13800138000"));
        when(users.findByMobile("13800138000")).thenReturn(Optional.empty());
        assertEquals("138****8000", changes.changeDevelopmentPhone(42L, "13800138000").mobile());
        verify(audit).record("PHONE_CHANGE", "userId=42,channel=DEVELOPMENT,old=139****9000,new=138****8000");
        verifyNoInteractions(phone, identities);
    }

    private static void assertError(int code, Runnable call) {
        var error = assertThrows(BizException.class, call::run);
        assertEquals(code, error.getCode());
        assertFalse(error.getMessage().contains("13800138000"));
        assertFalse(error.getMessage().contains("authorization"));
    }
}
