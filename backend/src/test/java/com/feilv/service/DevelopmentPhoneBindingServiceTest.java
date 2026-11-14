package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevelopmentPhoneBindingServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    private DevelopmentPhoneBindingService service(boolean enabled, String... profiles) {
        var environment = new MockEnvironment(); environment.setActiveProfiles(profiles);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> new SimpleTransactionStatus());
        return new DevelopmentPhoneBindingService(users, manager, environment, enabled);
    }

    @Test void bypassRequiresExplicitFlagAndDevelopmentProfileAndExcludesProductionProfiles() {
        assertFalse(service(true).enabled()); assertFalse(service(false, "local").enabled());
        assertTrue(service(true, "local").enabled()); assertTrue(service(true, "development").enabled());
        for (String blocked : new String[]{"prod", "production", "docker"}) {
            var binding = service(true, "local", blocked);
            assertFalse(binding.enabled());
            assertEquals(403, assertThrows(BizException.class, () -> binding.bind(42L, "13800138000")).getCode());
        }
        verify(users, never()).saveAndFlush(any());
    }

    @Test void environmentVariableMapsToFlagButStillRequiresDevelopmentProfile() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.SystemEnvironmentPropertySource("testEnvironment", java.util.Map.of("SMS_BINDING_DEV_BYPASS_ENABLED", "true"))))
                .withBean(UserRepository.class, () -> users)
                .withBean(PlatformTransactionManager.class, () -> manager)
                .withPropertyValues("feilv.sms.binding-dev-bypass-enabled=${SMS_BINDING_DEV_BYPASS_ENABLED:false}")
                .withUserConfiguration(DevelopmentPhoneBindingService.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var binding = context.getBean(DevelopmentPhoneBindingService.class);
                    assertEquals(DevelopmentPhoneBindingService.BypassStatus.DEVELOPMENT_PROFILE_REQUIRED, binding.status());
                    context.getEnvironment().setActiveProfiles("development");
                    assertTrue(binding.enabled());
                    context.getEnvironment().setActiveProfiles("docker", "development");
                    assertFalse(binding.enabled());
                    assertTrue(binding.modeMessage().contains("受保护"));
                });
    }

    @Test void developmentBindingUsesCurrentAccountAndReturnsMaskedProfileWithoutSms() {
        User user = new User(); user.setId(42L); user.setNickname("原昵称"); user.setAvatarUrl("/api/v1/avatars/a.png");
        when(users.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        var binding = service(true, "local");
        var view = binding.bind(42L, "13800138000");
        assertTrue(view.mobileBound()); assertEquals("138****8000", view.mobile()); assertEquals("原昵称", view.nickname());
        assertEquals("13800138000", user.getMobile()); verify(users).saveAndFlush(user);
        assertTrue(binding.bind(42L, "13800138000").mobileBound());
        assertEquals(409, assertThrows(BizException.class, () -> binding.bind(42L, "13900139000")).getCode());
    }

    @Test void validationConflictAndAccountChecksStillApplyWithoutVerification() {
        var binding = service(true, "local");
        assertEquals(400, assertThrows(BizException.class, () -> binding.bind(42L, "bad")).getCode());
        assertEquals(403, assertThrows(BizException.class, () -> binding.bind(42L, "13800138000")).getCode());
        User user = new User(); user.setId(42L); when(users.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        user.setStatus(0); assertEquals(403, assertThrows(BizException.class, () -> binding.bind(42L, "13800138000")).getCode());
        user.setStatus(1); user.setRole("ADMIN"); assertEquals(403, assertThrows(BizException.class, () -> binding.bind(42L, "13800138000")).getCode());
        user.setRole("USER"); User other = new User(); other.setId(43L);
        when(users.findByMobile("13800138000")).thenReturn(Optional.of(other));
        assertEquals(409, assertThrows(BizException.class, () -> binding.bind(42L, "13800138000")).getCode());
        assertNull(user.getMobile());
    }

    @Test void uniqueConstraintCompetitionBecomesSafeConflictAfterRollback() {
        User user = new User(); user.setId(42L); when(users.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        when(users.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("sensitive-database-value"));
        var binding = service(true, "development");
        var error = assertThrows(BizException.class, () -> binding.bind(42L, "13800138000"));
        assertEquals(409, error.getCode()); assertFalse(error.getMessage().contains("sensitive"));
        verify(manager).rollback(any());
    }
}
