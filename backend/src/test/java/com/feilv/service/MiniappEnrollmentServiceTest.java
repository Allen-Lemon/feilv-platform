package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.Rider;
import com.feilv.domain.User;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.dto.MiniappEnrollmentDtos.EnrollmentReq;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MiniappEnrollmentServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final RiderRepository riders = mock(RiderRepository.class);
    private final WechatIdentityRepository identities = mock(WechatIdentityRepository.class);
    private final WechatConfigService settings = mock(WechatConfigService.class);
    private final WechatPhoneClient phones = mock(WechatPhoneClient.class);
    private final AuthTokenService tokens = mock(AuthTokenService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final WechatConfigService.LoginCredentials credentials = new WechatConfigService.LoginCredentials("rider-app", "secret");
    private final EnrollmentReq request = new EnrollmentReq("  骑手姓名  ", "  贵D12345  ", "phone-code", true);
    private final AtomicBoolean writeTransaction = new AtomicBoolean();
    private final MockEnvironment environment = new MockEnvironment();
    private final DevelopmentPhonePolicy development = new DevelopmentPhonePolicy(environment, true);
    private final EnrollmentReq manual = new EnrollmentReq("姓名", "贵D12345", null, true, "13800138000");
    private User user;
    private MiniappEnrollmentService service;

    @BeforeEach
    void setup() {
        user = new User(); user.setId(42L); user.setMobile("13800138000"); user.setNickname("原昵称");
        UserContext.set(new UserContext.CurrentUser(42L, "USER", "RIDER", "rider-app"));
        when(users.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        when(riders.findByUserIdForUpdate(42L)).thenReturn(Optional.empty());
        when(settings.effectiveLogin("RIDER")).thenReturn(credentials);
        when(identities.existsByUserIdAndAppId(42L, "rider-app")).thenReturn(true);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> {
            assertFalse(writeTransaction.getAndSet(true));
            return new SimpleTransactionStatus();
        });
        doAnswer(call -> { writeTransaction.set(false); return null; }).when(manager).commit(any());
        doAnswer(call -> { writeTransaction.set(false); return null; }).when(manager).rollback(any());
        when(phones.exchange("phone-code", credentials)).thenAnswer(call -> {
            assertFalse(writeTransaction.get(), "WeChat exchange must be outside write transactions");
            return new WechatPhoneClient.Phone("rider-app", "13800138000");
        });
        when(tokens.login(user, "RIDER", "rider-app")).thenAnswer(call ->
                new LoginVO("new-rider-token", user.getId(), user.getRole(), user.getNickname()));
        environment.setActiveProfiles("local");
        service = new MiniappEnrollmentService(users, riders, identities, settings, phones, tokens, entityManager, manager, development);
    }

    @AfterEach
    void clearContext() { UserContext.clear(); }

    @Test
    void enrollsOnlyCurrentAccountAndPromotesWithoutGrantingCapabilities() {
        var result = service.enroll(request);
        assertEquals(42L, result.userId()); assertEquals("RIDER", result.role());
        assertEquals("new-rider-token", result.token()); assertEquals("原昵称", result.nickname());
        var captured = ArgumentCaptor.forClass(Rider.class);
        verify(riders).saveAndFlush(captured.capture());
        Rider created = captured.getValue();
        assertEquals(42L, created.getUserId()); assertEquals("骑手姓名", created.getName());
        assertEquals("贵D12345", created.getPlateNo()); assertEquals("ACTIVE", created.getStatus());
        assertFalse(created.isRideEnabled()); assertFalse(created.isDrivingEnabled());
        assertEquals(100, created.getCreditScore()); assertEquals(0, created.getOnlineStatus());
        assertEquals("13800138000", user.getMobile());
        verify(users, never()).findByMobile(anyString());
        verify(settings, never()).effectiveLogin();
        var order = inOrder(users, riders, phones);
        order.verify(users).findByIdForUpdate(42L);
        order.verify(riders).findByUserIdForUpdate(42L);
        order.verify(phones).exchange("phone-code", credentials);
        order.verify(users).findByIdForUpdate(42L);
        order.verify(riders).findByUserIdForUpdate(42L);
    }

    @Test
    void activeRetryReturnsTokenBeforeReusingCodeAndPreservesAllRiderFields() {
        Rider rider = existing("ACTIVE");
        rider.setRideEnabled(true); rider.setDrivingEnabled(true); rider.setCreditScore(72); rider.setOnlineStatus(2);
        user.setRole("RIDER");
        when(riders.findByUserIdForUpdate(42L)).thenReturn(Optional.of(rider));
        assertEquals("RIDER", service.enroll(request).role());
        verifyNoInteractions(phones);
        verify(riders, never()).saveAndFlush(any()); verify(users, never()).saveAndFlush(any());
        assertEquals("历史姓名", rider.getName()); assertEquals("历史车牌", rider.getPlateNo());
        assertTrue(rider.isRideEnabled()); assertTrue(rider.isDrivingEnabled());
        assertEquals(72, rider.getCreditScore()); assertEquals(2, rider.getOnlineStatus());
    }

    @Test
    void frozenRiderOrAdminFrozenUserCannotExchangeOrBecomeActive() {
        when(riders.findByUserIdForUpdate(42L)).thenReturn(Optional.of(existing("FROZEN")));
        assertCode(403, () -> service.enroll(request));
        when(riders.findByUserIdForUpdate(42L)).thenReturn(Optional.empty());
        user.setStatus(0); assertCode(403, () -> service.enroll(request));
        user.setStatus(1); user.setRole("ADMIN"); assertCode(403, () -> service.enroll(request));
        verifyNoInteractions(phones, tokens); verify(riders, never()).saveAndFlush(any());
    }

    @Test
    void cannotSelectOtherAccountOrUseUserSourceOrUnsignedServiceContext() {
        UserContext.set(new UserContext.CurrentUser(42L, "USER", "USER", "user-app"));
        assertCode(403, () -> service.enroll(request));
        UserContext.set(new UserContext.CurrentUser(42L, "USER"));
        assertCode(403, () -> service.enroll(request));
        UserContext.clear(); assertCode(401, () -> service.enroll(request));
        verifyNoInteractions(users, riders, phones);
    }

    @Test
    void requiresIdentityMatchingAppIdMobileAndConsent() {
        assertCode(400, () -> service.enroll(new EnrollmentReq("名字", "", "phone-code", false)));
        when(identities.existsByUserIdAndAppId(42L, "rider-app")).thenReturn(false);
        assertCode(403, () -> service.enroll(request));
        when(identities.existsByUserIdAndAppId(42L, "rider-app")).thenReturn(true);
        UserContext.set(new UserContext.CurrentUser(42L, "USER", "RIDER", "old-app"));
        assertCode(401, () -> service.enroll(request));
        verifyNoInteractions(phones);
    }

    @Test
    void verifiedPhoneMustMatchAccountAndCannotCreateOrSelectByMobile() {
        when(phones.exchange("phone-code", credentials)).thenReturn(new WechatPhoneClient.Phone("rider-app", "13900139000"));
        assertCode(409, () -> service.enroll(request));
        assertEquals("USER", user.getRole());
        verify(users, never()).findByMobile(anyString()); verify(riders, never()).saveAndFlush(any());
    }

    @Test
    void malformedOrWrongAppPhoneAndExchangeFailureNeverCreateRider() {
        for (var phone : new WechatPhoneClient.Phone[]{null,
                new WechatPhoneClient.Phone("wrong-app", "13800138000"),
                new WechatPhoneClient.Phone("rider-app", "+8613800138000")}) {
            when(phones.exchange("phone-code", credentials)).thenReturn(phone);
            assertCode(502, () -> service.enroll(request));
        }
        when(phones.exchange("phone-code", credentials)).thenThrow(BizException.of(400, "授权已失效"));
        assertCode(400, () -> service.enroll(request));
        verify(riders, never()).saveAndFlush(any()); assertEquals("USER", user.getRole());
    }

    @Test
    void mobileFreezeAndConfigurationChangesDuringExchangeAreRecheckedUnderLock() {
        doAnswer(call -> { user.setMobile("13900139000"); return new WechatPhoneClient.Phone("rider-app", "13800138000"); })
                .when(phones).exchange("phone-code", credentials);
        assertCode(409, () -> service.enroll(request));
        user.setMobile("13800138000");
        doAnswer(call -> { user.setStatus(0); return new WechatPhoneClient.Phone("rider-app", "13800138000"); })
                .when(phones).exchange("phone-code", credentials);
        assertCode(403, () -> service.enroll(request));
        user.setStatus(1);
        doReturn(new WechatPhoneClient.Phone("rider-app", "13800138000")).when(phones).exchange("phone-code", credentials);
        when(settings.effectiveLogin("RIDER")).thenReturn(credentials, credentials,
                new WechatConfigService.LoginCredentials("rider-app", "rotated-secret"));
        assertCode(409, () -> service.enroll(request));
        verify(riders, never()).saveAndFlush(any()); assertEquals("USER", user.getRole());
    }

    @Test
    void plateLengthMatchesSixteenCharacterSchemaLimit() {
        assertCode(400, () -> service.enroll(new EnrollmentReq("姓名", "A".repeat(17), "phone-code", true)));
        verifyNoInteractions(phones, users, riders);
        assertEquals("RIDER", service.enroll(new EnrollmentReq("姓名", "A".repeat(16), "phone-code", true)).role());
        var captured = ArgumentCaptor.forClass(Rider.class);
        verify(riders).saveAndFlush(captured.capture());
        assertEquals("A".repeat(16), captured.getValue().getPlateNo());
    }

    @Test
    void riderRoleWithoutArchiveFailsClosedBeforePhoneExchange() {
        user.setRole("RIDER");
        assertCode(409, () -> service.enroll(request));
        verifyNoInteractions(phones, tokens);
        verify(riders, never()).saveAndFlush(any());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void manualEnrollmentUsesBoundCurrentAccountWithoutUpstreamOrCapabilityGrants() {
        assertEquals(42L, service.enroll(manual).userId());
        var captured = ArgumentCaptor.forClass(Rider.class);
        verify(riders).saveAndFlush(captured.capture());
        assertFalse(captured.getValue().isRideEnabled()); assertFalse(captured.getValue().isDrivingEnabled());
        assertEquals("RIDER", user.getRole()); assertEquals("13800138000", user.getMobile());
        verifyNoInteractions(phones); verify(users, never()).findByMobile(anyString());
    }

    @Test
    void manualEnrollmentRequiresCurrentIdentityBoundMobileAndAvailableArchive() {
        when(identities.existsByUserIdAndAppId(42L, "rider-app")).thenReturn(false);
        assertCode(403, () -> service.enroll(manual));
        when(identities.existsByUserIdAndAppId(42L, "rider-app")).thenReturn(true);
        UserContext.set(new UserContext.CurrentUser(42L, "USER", "RIDER", "old-app"));
        assertCode(401, () -> service.enroll(manual));
        UserContext.set(new UserContext.CurrentUser(42L, "USER", "RIDER", "rider-app"));
        user.setMobile("13900139000"); assertCode(409, () -> service.enroll(manual));
        user.setMobile(null); assertCode(409, () -> service.enroll(manual));
        user.setMobile("13800138000");
        when(riders.findByUserIdForUpdate(42L)).thenReturn(Optional.of(existing("FROZEN")));
        assertCode(403, () -> service.enroll(manual));
        when(riders.findByUserIdForUpdate(42L)).thenReturn(Optional.empty()); user.setRole("RIDER");
        assertCode(409, () -> service.enroll(manual));
        verifyNoInteractions(phones, tokens); verify(riders, never()).saveAndFlush(any());
    }

    @Test
    void manualActiveRetryRetainsGrantsButProtectedProfileStillDeniesBoundRider() {
        Rider rider = existing("ACTIVE"); rider.setRideEnabled(true); rider.setDrivingEnabled(true);
        user.setRole("RIDER"); when(riders.findByUserIdForUpdate(42L)).thenReturn(Optional.of(rider));
        assertEquals("RIDER", service.enroll(manual).role());
        assertTrue(rider.isRideEnabled()); assertTrue(rider.isDrivingEnabled());
        verify(riders, never()).saveAndFlush(any());
        for (String profile : new String[]{"prod", "production", "docker"}) {
            environment.setActiveProfiles("local", profile);
            assertCode(403, () -> service.enroll(manual));
        }
        verifyNoInteractions(phones);
    }

    @Test
    void manualGateIsRecheckedInsideBothWriteLocks() {
        doAnswer(call -> { environment.setActiveProfiles("local", "docker"); return Optional.of(user); })
                .when(users).findByIdForUpdate(42L);
        assertCode(403, () -> service.enroll(manual));
        environment.setActiveProfiles("local");
        doAnswer(call -> Optional.of(user)).doAnswer(call -> {
            environment.setActiveProfiles("local", "production"); return Optional.of(user);
        }).when(users).findByIdForUpdate(42L);
        assertCode(403, () -> service.enroll(manual));
        verifyNoInteractions(phones, tokens); verify(riders, never()).saveAndFlush(any());
    }

    private Rider existing(String status) {
        Rider rider = new Rider(); rider.setId(7L); rider.setUserId(42L); rider.setStatus(status);
        rider.setName("历史姓名"); rider.setPlateNo("历史车牌"); return rider;
    }

    private static void assertCode(int code, Runnable call) {
        var error = assertThrows(BizException.class, call::run);
        assertEquals(code, error.getCode()); assertFalse(error.getMessage().contains("13800138000"));
    }
}
