package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserProfileServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final WechatIdentityRepository identities = mock(WechatIdentityRepository.class);
    private final WechatConfigService settings = mock(WechatConfigService.class);
    private final WechatPhoneClient phone = mock(WechatPhoneClient.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final WechatConfigService.LoginCredentials credentials = new WechatConfigService.LoginCredentials("app-a", "secret-a");
    private UserProfileService service;
    private User user;

    @BeforeEach
    void setup() {
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> new SimpleTransactionStatus());
        user = new User(); user.setId(42L); user.setNickname("微信用户");
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(settings.effectiveLogin()).thenReturn(credentials);
        when(identities.existsByUserIdAndAppId(42L, "app-a")).thenReturn(true);
        when(phone.exchange("phone-code", credentials)).thenReturn(new WechatPhoneClient.Phone("app-a", "13800138000"));
        service = new UserProfileService(users, identities, settings, phone, manager);
    }

    @Test
    void avatarOnlyAcceptsServerStoredPathAndDoesNotAlterOtherFields() {
        String path = "/api/v1/avatars/12345678-1234-1234-1234-123456789abc.png";
        assertEquals(path, service.updateAvatar(42L, path).avatarUrl());
        assertEquals("微信用户", user.getNickname());
        assertNull(user.getMobile());
        for (String invalid : new String[]{"wxfile://temp.png", "https://external.invalid/avatar.png", "../avatar.png"}) {
            assertThrows(BizException.class, () -> service.updateAvatar(42L, invalid));
        }
    }

    @Test
    void orderGateRequiresActiveUserAndBoundMobile() {
        assertEquals(4091, assertThrows(BizException.class, () -> service.requireBoundMobile(42L)).getCode());
        user.setMobile("13800138000");
        assertDoesNotThrow(() -> service.requireBoundMobile(42L));
        user.setStatus(0);
        assertEquals(403, assertThrows(BizException.class, () -> service.requireBoundMobile(42L)).getCode());
    }

    @Test
    void nicknameIsDisplayNameAndDoesNotChangeIdentityOrRole() {
        user.setRole("RIDER");
        var profile = service.updateNickname(42L, "  新昵称  ");
        assertEquals("新昵称", profile.nickname());
        assertEquals("RIDER", profile.role());
        assertEquals(42L, profile.userId());
        assertFalse(profile.mobileBound());
        assertEquals("", profile.mobile());
        verifyNoInteractions(identities, settings, phone);
        verify(users).findByIdForUpdate(42L);
    }

    @Test
    void nicknameBoundariesUseCodePointsAndRejectControls() {
        assertEquals("😀".repeat(32), service.updateNickname(42L, "😀".repeat(32)).nickname());
        for (String invalid : new String[]{"", "   ", "a".repeat(33), "a\nb", "a" + (char) 0 + "b", "a\tb"}) {
            assertEquals(400, assertThrows(BizException.class, () -> service.updateNickname(42L, invalid)).getCode());
        }
        assertEquals(400, assertThrows(BizException.class, () -> service.updateNickname(42L, null)).getCode());
    }

    @Test
    void rejectsMissingFrozenAndAdministrativeAccountsBeforeExchange() {
        when(users.findById(42L)).thenReturn(Optional.empty());
        assertEquals(401, assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "phone-code")).getCode());
        when(users.findById(42L)).thenReturn(Optional.of(user));
        user.setStatus(0);
        assertEquals(403, assertThrows(BizException.class, () -> service.me(42L)).getCode());
        assertEquals(403, assertThrows(BizException.class, () -> service.updateNickname(42L, "昵称")).getCode());
        user.setStatus(1); user.setRole("ADMIN");
        assertEquals(403, assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "phone-code")).getCode());
        verifyNoInteractions(phone);
    }

    @Test
    void requiresCurrentWechatIdentityAndIndependentNonemptyPhoneCode() {
        assertEquals(400, assertThrows(BizException.class, () -> service.bindWechatPhone(42L, " ")).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "x".repeat(257))).getCode());
        when(identities.existsByUserIdAndAppId(42L, "app-a")).thenReturn(false);
        assertEquals(403, assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "phone-code")).getCode());
        verifyNoInteractions(phone);
    }

    @Test
    void bindsVerifiedPhoneAndOnlyReturnsMaskedValue() {
        var profile = service.bindWechatPhone(42L, "phone-code");
        assertTrue(profile.mobileBound());
        assertEquals("138****8000", profile.mobile());
        assertEquals("13800138000", user.getMobile());
        assertEquals("USER", profile.role());
        verify(phone, times(1)).exchange("phone-code", credentials);
        verify(identities, never()).save(any());
        verify(manager).commit(any());
    }

    @Test
    void samePhoneIsIdempotentButDifferentPhoneIsNotSilentlyReplaced() {
        user.setMobile("13800138000");
        assertTrue(service.bindWechatPhone(42L, "phone-code").mobileBound());
        verify(users, never()).saveAndFlush(any());
        user.setMobile("13900139000");
        assertEquals(409, assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "phone-code")).getCode());
        assertEquals("13900139000", user.getMobile());
    }

    @Test
    void rejectsPhoneOwnedByAnyOtherAccountIncludingAdmin() {
        for (String role : new String[]{"USER", "RIDER", "ADMIN"}) {
            User other = new User(); other.setId(99L); other.setRole(role);
            when(users.findByMobile("13800138000")).thenReturn(Optional.of(other));
            BizException error = assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "phone-code"));
            assertEquals(409, error.getCode());
            assertFalse(error.getMessage().contains("13800138000"));
            assertNull(user.getMobile());
        }
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void uniqueConstraintRaceIsReportedOnlyAfterTransactionRollback() {
        when(users.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("sensitive SQL payload"));
        BizException error = assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "phone-code"));
        assertEquals(409, error.getCode());
        assertFalse(error.getMessage().contains("sensitive"));
        verify(manager).rollback(any());
    }

    @Test
    void rechecksStatusAndConfigurationAfterExternalCall() {
        when(phone.exchange("phone-code", credentials)).thenAnswer(call -> {
            user.setStatus(0);
            return new WechatPhoneClient.Phone("app-a", "13800138000");
        });
        assertEquals(403, assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "phone-code")).getCode());
        user.setStatus(1);
        doReturn(new WechatPhoneClient.Phone("app-a", "13800138000")).when(phone).exchange("phone-code", credentials);
        when(settings.effectiveLogin()).thenReturn(credentials, new WechatConfigService.LoginCredentials("app-b", "secret-b"));
        assertEquals(409, assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "phone-code")).getCode());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void invalidPhoneAuthorizationDoesNotBecomePlatformSessionExpiry() {
        when(phone.exchange("phone-code", credentials)).thenThrow(BizException.of(400, "请重新授权手机号"));
        assertEquals(400, assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "phone-code")).getCode());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInconsistentExchangeAndMasksLegacyMobileValues() {
        when(phone.exchange("phone-code", credentials)).thenReturn(new WechatPhoneClient.Phone("app-b", "13800138000"));
        assertEquals(502, assertThrows(BizException.class, () -> service.bindWechatPhone(42L, "phone-code")).getCode());
        user.setMobile("legacy-sensitive-contact");
        assertEquals("已绑定", service.me(42L).mobile());
    }
}
