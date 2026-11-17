package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.domain.WechatIdentity;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WechatLoginServiceTest {
    private final WechatSessionClient client = mock(WechatSessionClient.class);
    private final WechatIdentityRepository identities = mock(WechatIdentityRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private WechatLoginService service;
    private User user;

    @BeforeEach
    void setup() {
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(client.exchange("valid-code")).thenReturn(new WechatSessionClient.Session("app", "verified-openid", null));
        user = new User(); user.setId(42L); user.setNickname("微信用户"); user.setStatus(1); user.setRole("USER");
        when(users.findById(42L)).thenReturn(Optional.of(user));
        service = new WechatLoginService(client, identities, users, transactions, com.feilv.controller.TestAuth.tokens());
    }

    @Test
    void agreementRequiredBeforeContactingWechat() {
        assertThrows(BizException.class, () -> service.login("valid-code", false));
        verifyNoInteractions(client, identities, users);
    }

    @Test
    void newLoginCreatesOnlyOrdinaryUserAndServerVerifiedIdentity() {
        when(identities.findByAppIdAndOpenid("app", "verified-openid")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User created = call.getArgument(0);
            assertNull(created.getMobile());
            assertEquals("USER", created.getRole());
            created.setId(42L);
            return created;
        });
        var result = service.login("valid-code", true);
        assertEquals(42L, result.userId());
        assertEquals("USER", com.feilv.controller.TestAuth.tokens().parse(result.token()).role);
        verify(identities).saveAndFlush(argThat(identity -> "verified-openid".equals(identity.getOpenid()) && identity.getUserId() == 42L));
    }

    @Test
    void repeatedLoginReusesUser() {
        WechatIdentity identity = new WechatIdentity(); identity.setUserId(42L);
        when(identities.findByAppIdAndOpenid("app", "verified-openid")).thenReturn(Optional.of(identity));
        assertEquals(42L, service.login("valid-code", true).userId());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void existingWechatRiderKeepsAssignedRole() {
        WechatIdentity identity = new WechatIdentity(); identity.setUserId(42L);
        when(identities.findByAppIdAndOpenid("app", "verified-openid")).thenReturn(Optional.of(identity));
        user.setRole("RIDER");
        var result = service.login("valid-code", true);
        assertEquals("RIDER", result.role());
        var claims = com.feilv.controller.TestAuth.tokens().parse(result.token());
        assertEquals("RIDER", claims.role);
        assertEquals("USER", claims.aud, "legacy WeChat login remains the consumer application even for a rider account");
        assertEquals("app", claims.appId);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void disabledAndAdminAccountsCannotUseWechatLogin() {
        WechatIdentity identity = new WechatIdentity(); identity.setUserId(42L);
        when(identities.findByAppIdAndOpenid("app", "verified-openid")).thenReturn(Optional.of(identity));
        user.setStatus(0);
        assertThrows(BizException.class, () -> service.login("valid-code", true));
        user.setStatus(1); user.setRole("ADMIN");
        assertThrows(BizException.class, () -> service.login("valid-code", true));
    }

    @Test
    void concurrentFirstLoginRetriesUsingWinningIdentity() {
        WechatIdentity identity = new WechatIdentity(); identity.setUserId(42L);
        when(identities.findByAppIdAndOpenid("app", "verified-openid"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(identity));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User created = call.getArgument(0); created.setId(42L); return created;
        });
        when(identities.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        assertEquals(42L, service.login("valid-code", true).userId());
        verify(transactions).rollback(any());
        verify(transactions).commit(any());
    }

    @Test
    void missingWechatConfigurationFailsClosed() {
        var unconfigured = new WechatSessionClient("", "", new com.fasterxml.jackson.databind.ObjectMapper());
        BizException error = assertThrows(BizException.class, () -> unconfigured.exchange("any"));
        assertEquals(503, error.getCode());
    }
}
