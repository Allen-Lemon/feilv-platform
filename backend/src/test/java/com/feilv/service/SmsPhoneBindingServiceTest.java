package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.dto.ManualPhoneBindingDtos.*;
import com.feilv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmsPhoneBindingServiceTest {
    private static final String MOBILE = "13800138000";
    private static final String OTHER_MOBILE = "13900139000";
    private static final String CODE = "042137";
    private final UserRepository users = mock(UserRepository.class);
    private final MessageService messages = mock(MessageService.class);
    private final AuditService audit = mock(AuditService.class);
    private final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SecureRandom random = mock(SecureRandom.class);
    private final Clock clock = mock(Clock.class);
    private final Map<Long, User> accounts = new HashMap<>();
    private Instant now;
    private SmsPhoneBindingService service;
    private User user;

    @BeforeEach
    void setup() {
        now = Instant.parse("2026-11-14T02:00:00Z");
        when(clock.instant()).thenAnswer(call -> now);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(random.nextInt(1_000_000)).thenReturn(42137);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> new SimpleTransactionStatus());
        user = account(42L, "USER");
        account(43L, "RIDER");
        when(users.findByIdForUpdate(anyLong())).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0))));
        when(users.findByMobile(anyString())).thenAnswer(call -> accounts.values().stream()
                .filter(value -> call.getArgument(0).equals(value.getMobile())).findFirst());
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        service = service(true);
    }

    @Test
    void disabledModeDoesNotGenerateSendOrAcceptCodes() {
        service = service(false);
        assertError(503, () -> service.sendCode(42L, MOBILE));
        assertError(503, () -> service.bindPhone(42L, MOBILE, CODE));
        verifyNoInteractions(random, messages);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void springDefaultIsDisabledWithoutAnyPropertyOverride() {
        new ApplicationContextRunner().withBean(UserRepository.class, () -> users)
                .withBean(MessageService.class, () -> messages)
                .withBean(AuditService.class, () -> audit)
                .withBean(jakarta.persistence.EntityManager.class, () -> entityManager)
                .withBean(PlatformTransactionManager.class, () -> manager)
                .withUserConfiguration(SmsPhoneBindingService.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    var error = assertError(503, () -> context.getBean(SmsPhoneBindingService.class).sendCode(42L, MOBILE));
                    assertTrue(error.getMessage().contains("微信授权"));
                });
        verifyNoInteractions(messages);
    }

    @Test
    void mockSendsSixDigitSecureRandomCodeButLogsNoRawPhoneOrCode() {
        var response = service.sendCode(42L, MOBILE);
        assertEquals(60, response.retryAfterSeconds());
        assertTrue(response.mock());
        assertEquals(CODE, response.demoCode());
        verify(random).nextInt(1_000_000);
        verify(messages).sendSms(eq("138****8000"), argThat(content -> !content.contains(CODE) && !content.contains(MOBILE)));
        assertFalse(response.toString().contains(CODE));
        assertFalse(new SmsCodeReq(MOBILE).toString().contains(MOBILE));
        String description = new BindPhoneReq(MOBILE, CODE).toString();
        assertFalse(description.contains(MOBILE));
        assertFalse(description.contains(CODE));
    }

    @Test
    void successfulBindingPreservesProfileAndImmediatelyOpensOrderGateForUserAndRider() {
        for (long id : new long[]{42L, 43L}) {
            User current = accounts.get(id);
            current.setNickname("原昵称");
            current.setAvatarUrl("/api/v1/avatars/12345678-1234-1234-1234-123456789abc.png");
            String mobile = id == 42L ? MOBILE : OTHER_MOBILE;
            String code = service.sendCode(id, mobile).demoCode();
            var profile = service.bindPhone(id, mobile, code);
            assertEquals(id, profile.userId());
            assertEquals(current.getRole(), profile.role());
            assertEquals("原昵称", profile.nickname());
            assertEquals(current.getAvatarUrl(), profile.avatarUrl());
            assertTrue(profile.mobileBound());
            assertEquals(id == 42L ? "138****8000" : "139****9000", profile.mobile());
            assertEquals(mobile, current.getMobile());
            // 下单的既有门禁直接读取同一持久化字段，无需重新登录或刷新 JWT。
            var gate = new UserProfileService(users, null, null, null, manager);
            assertDoesNotThrow(() -> gate.requireBoundMobile(id));
        }
        verify(users).saveAndFlush(user);
    }

    @Test
    void validationRejectsAllMalformedPhonesAndCodesBeforeRepositoryOrSms() {
        for (String mobile : new String[]{null, "", " ", "1380013800", "12800138000", "+8613800138000",
                "13800138000 ", "138001380000", "１３８００１３８０００", "1380013800\n"}) {
            assertError(400, () -> service.sendCode(42L, mobile));
            assertError(400, () -> service.bindPhone(42L, mobile, CODE));
        }
        for (String code : new String[]{null, "", " ", "12345", "1234567", "abcdef", "123 56", "１２３４５６", "12345\n"}) {
            assertError(400, () -> service.bindPhone(42L, MOBILE, code));
        }
        verifyNoInteractions(users, random, messages);
    }

    @Test
    void missingFrozenAndAdminAreRejectedAtBothEndpointsEvenWithExistingCredential() {
        service.sendCode(42L, MOBILE);
        user.setStatus(0);
        assertRejectedBoth(403, 42L, MOBILE);
        user.setStatus(1);
        user.setRole("ADMIN");
        assertRejectedBoth(403, 42L, MOBILE);
        user.setRole("UNKNOWN");
        assertRejectedBoth(403, 42L, MOBILE);
        assertRejectedBoth(403, 99L, MOBILE);
        verify(messages, times(1)).sendSms(anyString(), anyString());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void differentBoundPhoneCannotBeSentOrReplacedAndIsRecheckedAtBinding() {
        user.setMobile(OTHER_MOBILE);
        assertRejectedBoth(409, 42L, MOBILE);
        assertEquals(OTHER_MOBILE, user.getMobile());
        user.setMobile(null);
        service.sendCode(42L, MOBILE);
        user.setMobile(OTHER_MOBILE); // 例如微信授权在用户填写验证码前完成。
        assertError(409, () -> service.bindPhone(42L, MOBILE, CODE));
        assertEquals(OTHER_MOBILE, user.getMobile());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void sameBoundPhoneStillRequiresVerificationThenIsIdempotent() {
        user.setMobile(MOBILE);
        assertError(4010, () -> service.bindPhone(42L, MOBILE, CODE));
        service.sendCode(42L, MOBILE);
        assertError(4011, () -> service.bindPhone(42L, MOBILE, "999999"));
        assertTrue(service.bindPhone(42L, MOBILE, CODE).mobileBound());
        assertError(4010, () -> service.bindPhone(42L, MOBILE, CODE));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void otherOwnersOfAllRolesOrFrozenStatusConflictWithoutTransferOrMerge() {
        User other = accounts.get(43L);
        other.setMobile(MOBILE);
        for (String role : new String[]{"USER", "RIDER", "ADMIN"}) {
            other.setRole(role);
            other.setStatus(0);
            assertRejectedBoth(409, 42L, MOBILE);
            assertNull(user.getMobile());
            assertEquals(MOBILE, other.getMobile());
        }
        verifyNoInteractions(messages, random);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void otherAccountClaimBetweenSendingAndBindingIsRejected() {
        service.sendCode(42L, MOBILE);
        accounts.get(43L).setMobile(MOBILE);
        assertError(409, () -> service.bindPhone(42L, MOBILE, CODE));
        assertNull(user.getMobile());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void credentialsAreBoundToAccountAndPhoneWithoutInvalidatingOwnersCode() {
        service.sendCode(42L, MOBILE);
        assertError(4010, () -> service.bindPhone(43L, MOBILE, CODE));
        assertError(4010, () -> service.bindPhone(42L, OTHER_MOBILE, CODE));
        assertTrue(service.bindPhone(42L, MOBILE, CODE).mobileBound());
        assertNull(accounts.get(43L).getMobile());
    }

    @Test
    void loginAndBindingCodesArePurposeIsolatedInBothDirections() {
        var loginMessages = mock(MessageService.class);
        var login = new UserService(users, mock(com.feilv.repository.RiderRepository.class), loginMessages, null, null, null, null, null,
                com.feilv.controller.TestAuth.tokens(), mock(DemoAuthPolicy.class), mock(WechatConfigService.class));
        String loginCode = login.sendSmsCode(MOBILE);
        assertError(4010, () -> service.bindPhone(42L, MOBILE, loginCode));
        // 换一个号码，旧登录域从未收到该号码的验证码。
        service.sendCode(42L, OTHER_MOBILE);
        assertError(4010, () -> login.login(OTHER_MOBILE, CODE));
        assertTrue(service.bindPhone(42L, OTHER_MOBILE, CODE).mobileBound());
    }

    @Test
    void fifthWrongAttemptInvalidatesCodeAndDoesNotReturnPlatform401() {
        service.sendCode(42L, MOBILE);
        for (int i = 0; i < 5; i++) assertError(4011, () -> service.bindPhone(42L, MOBILE, "999999"));
        assertError(4010, () -> service.bindPhone(42L, MOBILE, CODE));
        assertNull(user.getMobile());
    }

    @Test
    void codeLivesForFiveMinutesAndExpiresAtExactBoundary() {
        service.sendCode(42L, MOBILE);
        now = now.plusSeconds(299);
        assertTrue(service.bindPhone(42L, MOBILE, CODE).mobileBound());
        service.sendCode(42L, MOBILE);
        now = now.plusSeconds(300);
        assertError(4010, () -> service.bindPhone(42L, MOBILE, CODE));
    }

    @Test
    void cooldownAppliesToUserAndPhoneSeparatelyAndAllowsExactSixtySeconds() {
        service.sendCode(42L, MOBILE);
        assertError(4291, () -> service.sendCode(42L, OTHER_MOBILE));
        assertError(4291, () -> service.sendCode(43L, MOBILE));
        now = now.plusSeconds(59);
        assertError(4291, () -> service.sendCode(42L, MOBILE));
        now = now.plusSeconds(1);
        assertTrue(service.sendCode(43L, MOBILE).mock());
        verify(messages, times(2)).sendSms(anyString(), anyString());
    }

    @Test
    void userDailyLimitCannotBeBypassedByRotatingMobileNumbers() {
        for (int i = 0; i < 10; i++) {
            service.sendCode(42L, "1380013800" + i);
            now = now.plusSeconds(60);
        }
        assertError(4292, () -> service.sendCode(42L, OTHER_MOBILE));
        now = now.plusSeconds(86400);
        assertTrue(service.sendCode(42L, OTHER_MOBILE).mock());
    }

    @Test
    void mobileDailyLimitCannotBeBypassedByRotatingAccounts() {
        for (long i = 100; i < 110; i++) {
            account(i, "USER");
            service.sendCode(i, MOBILE);
            now = now.plusSeconds(60);
        }
        assertError(4292, () -> service.sendCode(42L, MOBILE));
        now = now.plusSeconds(86400);
        assertTrue(service.sendCode(42L, MOBILE).mock());
    }

    @Test
    void midnightResetsDailyCountButDoesNotRemoveSixtySecondCooldown() {
        now = Instant.parse("2026-11-14T15:59:30Z");
        service.sendCode(42L, MOBILE);
        now = now.plusSeconds(31);
        assertError(4291, () -> service.sendCode(42L, MOBILE));
        now = now.plusSeconds(29);
        assertTrue(service.sendCode(42L, MOBILE).mock());
    }

    @Test
    void resendReplacesCodeAndResetsErrorBudget() {
        service.sendCode(42L, MOBILE);
        for (int i = 0; i < 4; i++) assertError(4011, () -> service.bindPhone(42L, MOBILE, "999999"));
        now = now.plusSeconds(60);
        when(random.nextInt(1_000_000)).thenReturn(123456);
        assertEquals("123456", service.sendCode(42L, MOBILE).demoCode());
        assertError(4011, () -> service.bindPhone(42L, MOBILE, CODE));
        assertTrue(service.bindPhone(42L, MOBILE, "123456").mobileBound());
    }

    @Test
    void simulatedSendFailureDoesNotCreateCredentialOrConsumeRateBudget() {
        doThrow(new IllegalStateException("simulated failure")).when(messages).sendSms(anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> service.sendCode(42L, MOBILE));
        assertError(4010, () -> service.bindPhone(42L, MOBILE, CODE));
        doNothing().when(messages).sendSms(anyString(), anyString());
        assertTrue(service.sendCode(42L, MOBILE).mock());
    }

    @Test
    void uniqueRaceIsConvertedAfterRollbackWithoutLeakingPayloadOrRestoringCode() {
        service.sendCode(42L, MOBILE);
        clearInvocations(manager);
        when(users.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("sensitive SQL " + MOBILE));
        BizException error = assertError(409, () -> service.bindPhone(42L, MOBILE, CODE));
        assertFalse(error.getMessage().contains(MOBILE));
        assertFalse(error.getMessage().contains("sensitive"));
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
        user.setMobile(null); // 模拟数据库回滚；真实 H2 回归另外验证落库状态。
        assertError(4010, () -> service.bindPhone(42L, MOBILE, CODE));
    }

    @Test
    void concurrentVerificationConsumesExactlyOnceEvenIfRepositoryMockHasNoRowLock() throws Exception {
        service.sendCode(42L, MOBILE);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> bindAfter(start));
            var b = executor.submit(() -> bindAfter(start));
            start.countDown();
            int first = a.get(5, TimeUnit.SECONDS), second = b.get(5, TimeUnit.SECONDS);
            assertTrue(first == 0 && second == 4010 || first == 4010 && second == 0);
            verify(users, times(1)).saveAndFlush(any());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void concurrentSendsShareAnAtomicCooldownForMobile() throws Exception {
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> sendAfter(start, 42L));
            var b = executor.submit(() -> sendAfter(start, 43L));
            start.countDown();
            int first = a.get(5, TimeUnit.SECONDS), second = b.get(5, TimeUnit.SECONDS);
            assertTrue(first == 0 && second == 4291 || first == 4291 && second == 0);
            verify(messages, times(1)).sendSms(anyString(), anyString());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void changeRequiresBoundActiveAccountAndConfiguredSms() {
        assertError(409, () -> service.sendChangeCode(42L, MOBILE));
        assertError(409, () -> service.changePhone(42L, MOBILE, CODE));
        user.setMobile(OTHER_MOBILE);
        service = service(false);
        assertError(503, () -> service.sendChangeCode(42L, MOBILE));
        assertError(503, () -> service.changePhone(42L, MOBILE, CODE));
        service = service(true);
        user.setStatus(0);
        assertError(403, () -> service.sendChangeCode(42L, MOBILE));
        assertError(403, () -> service.changePhone(42L, MOBILE, CODE));
        user.setStatus(1); user.setRole("ADMIN");
        assertError(403, () -> service.sendChangeCode(42L, MOBILE));
        assertError(403, () -> service.changePhone(42L, MOBILE, CODE));
        verifyNoInteractions(messages, random, audit);
    }

    @Test
    void changeCredentialsArePurposeAccountNewMobileAndOriginalSnapshotBound() {
        user.setMobile(OTHER_MOBILE);
        accounts.get(43L).setMobile("13700137000");
        service.sendChangeCode(42L, MOBILE);
        assertError(4010, () -> service.changePhone(43L, MOBILE, CODE));
        assertError(4010, () -> service.changePhone(42L, "13600136000", CODE));
        // 即便原先绑定状态被后台或并行流程改成可首次绑定，也不能挪用换绑凭证。
        user.setMobile(null);
        assertError(4010, () -> service.bindPhone(42L, MOBILE, CODE));
        user.setMobile("13500135000");
        assertError(4010, () -> service.changePhone(42L, MOBILE, CODE));
        assertEquals("13500135000", user.getMobile());
        verifyNoInteractions(audit);
    }

    @Test
    void bindingAndLoginCredentialsCannotAuthorizePhoneChange() {
        service.sendCode(42L, MOBILE);
        user.setMobile(OTHER_MOBILE);
        assertError(4010, () -> service.changePhone(42L, MOBILE, CODE));
        var login = new UserService(users, mock(com.feilv.repository.RiderRepository.class), mock(MessageService.class), null, null, null, null, null,
                com.feilv.controller.TestAuth.tokens(), mock(DemoAuthPolicy.class), mock(WechatConfigService.class));
        String loginCode = login.sendSmsCode(MOBILE);
        assertError(4010, () -> service.changePhone(42L, MOBILE, loginCode));
        now = now.plusSeconds(60);
        String changeCode = service.sendChangeCode(42L, "13600136000").demoCode();
        assertError(4010, () -> login.login("13600136000", changeCode));
        assertEquals("136****6000", service.changePhone(42L, "13600136000", changeCode).mobile());
        verify(audit).record("PHONE_CHANGE", "userId=42,channel=SMS,old=139****9000,new=136****6000");
    }

    @Test
    void firstBindingAndChangeShareUserAndMobileCooldownAndDailyLimits() {
        service.sendCode(42L, MOBILE);
        user.setMobile(OTHER_MOBILE);
        assertError(4291, () -> service.sendChangeCode(42L, MOBILE));
        accounts.get(43L).setMobile("13700137000");
        assertError(4291, () -> service.sendChangeCode(43L, MOBILE));
        now = now.plusSeconds(60);
        // 两个 purpose 交替发送仍然累计到同一个 user/day 配额。
        for (int i = 1; i < 10; i++) {
            service.sendChangeCode(42L, i % 2 == 0 ? OTHER_MOBILE : MOBILE);
            now = now.plusSeconds(60);
        }
        assertError(4292, () -> service.sendCode(42L, OTHER_MOBILE));
        assertError(4292, () -> service.sendChangeCode(42L, MOBILE));
    }

    @Test
    void changeRequiresVerificationEvenForSameMobileAndNeverRestoresConsumedCode() {
        user.setMobile(MOBILE);
        assertError(4010, () -> service.changePhone(42L, MOBILE, CODE));
        service.sendChangeCode(42L, MOBILE);
        assertError(4011, () -> service.changePhone(42L, MOBILE, "999999"));
        assertEquals("138****8000", service.changePhone(42L, MOBILE, CODE).mobile());
        assertError(4010, () -> service.changePhone(42L, MOBILE, CODE));
        verify(users, never()).saveAndFlush(any());
        verify(audit).record("PHONE_CHANGE", "userId=42,channel=SMS,old=138****8000,new=138****8000");
    }

    @Test
    void changeHasFiveErrorBudgetAndFiveMinuteExpiry() {
        user.setMobile(OTHER_MOBILE);
        service.sendChangeCode(42L, MOBILE);
        for (int i = 0; i < 5; i++) assertError(4011, () -> service.changePhone(42L, MOBILE, "999999"));
        assertError(4010, () -> service.changePhone(42L, MOBILE, CODE));
        now = now.plusSeconds(60);
        service.sendChangeCode(42L, MOBILE);
        now = now.plusSeconds(300);
        assertError(4010, () -> service.changePhone(42L, MOBILE, CODE));
        assertEquals(OTHER_MOBILE, user.getMobile());
        verifyNoInteractions(audit);
    }

    @Test
    void changeRejectsOccupiedNumberOfAnyRoleIncludingFrozenAndRechecksAfterSend() {
        user.setMobile(OTHER_MOBILE);
        service.sendChangeCode(42L, MOBILE);
        User other = accounts.get(43L); other.setMobile(MOBILE); other.setStatus(0);
        for (String role : new String[]{"USER", "RIDER", "ADMIN"}) {
            other.setRole(role);
            assertError(409, () -> service.sendChangeCode(42L, MOBILE));
            assertError(409, () -> service.changePhone(42L, MOBILE, CODE));
        }
        assertEquals(OTHER_MOBILE, user.getMobile());
        verifyNoInteractions(audit);
    }

    private SmsPhoneBindingService service(boolean enabled) {
        return new SmsPhoneBindingService(users, messages, audit, entityManager, manager, enabled, clock, random);
    }

    private User account(long id, String role) {
        User value = new User(); value.setId(id); value.setRole(role);
        accounts.put(id, value);
        return value;
    }

    private void assertRejectedBoth(int code, long userId, String mobile) {
        assertError(code, () -> service.sendCode(userId, mobile));
        assertError(code, () -> service.bindPhone(userId, mobile, CODE));
    }

    private static BizException assertError(int expected, Runnable action) {
        var error = assertThrows(BizException.class, action::run);
        assertEquals(expected, error.getCode());
        return error;
    }

    private int bindAfter(CountDownLatch start) throws InterruptedException {
        if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
        try { service.bindPhone(42L, MOBILE, CODE); return 0; }
        catch (BizException e) { return e.getCode(); }
    }

    private int sendAfter(CountDownLatch start, long id) throws InterruptedException {
        if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
        try { service.sendCode(id, MOBILE); return 0; }
        catch (BizException e) { return e.getCode(); }
    }
}
