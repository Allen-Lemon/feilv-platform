package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.Rider;
import com.feilv.domain.User;
import com.feilv.domain.WechatIdentity;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.dto.MiniappEnrollmentDtos.EnrollmentReq;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real locks and rollback, explicitly isolated from every default/local business datasource. */
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:miniapp-enrollment;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MiniappEnrollmentService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MiniappEnrollmentPersistenceTest {
    @MockBean WechatConfigService settings;
    @MockBean WechatPhoneClient phones;
    @MockBean AuthTokenService tokens;
    @Autowired MiniappEnrollmentService enrollment;
    @Autowired UserRepository users;
    @Autowired RiderRepository riders;
    @Autowired WechatIdentityRepository identities;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;
    private final WechatConfigService.LoginCredentials credentials = new WechatConfigService.LoginCredentials("enrollment-app", "secret");

    @AfterEach
    void clearContext() { UserContext.clear(); }

    private long account(String mobile) throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:miniapp-enrollment"));
        }
        when(settings.effectiveLogin("RIDER")).thenReturn(credentials);
        when(tokens.login(any(User.class), eq("RIDER"), eq("enrollment-app"))).thenAnswer(call -> {
            User user = call.getArgument(0);
            return new LoginVO("test-rider-token", user.getId(), user.getRole(), user.getNickname());
        });
        return new TransactionTemplate(manager).execute(status -> {
            User user = new User(); user.setMobile(mobile); user.setNickname("同一平台主体");
            users.saveAndFlush(user);
            WechatIdentity identity = new WechatIdentity(); identity.setUserId(user.getId());
            identity.setAppId("enrollment-app"); identity.setOpenid("openid-" + user.getId());
            identities.saveAndFlush(identity);
            return user.getId();
        });
    }

    @Test
    void concurrentEnrollmentCreatesOneRiderAndLostResponseRetryDoesNotConsumeCode() throws Exception {
        long id = account("13800138101");
        var rendezvous = new CountDownLatch(2);
        when(phones.exchange(anyString(), eq(credentials))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            rendezvous.countDown();
            assertTrue(rendezvous.await(5, TimeUnit.SECONDS), "Both initial checks must release user locks before WeChat");
            return new WechatPhoneClient.Phone("enrollment-app", "13800138101");
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> enroll(id, "first-code"));
            var second = executor.submit(() -> enroll(id, "second-code"));
            assertEquals(id, first.get(15, TimeUnit.SECONDS).userId());
            assertEquals(id, second.get(15, TimeUnit.SECONDS).userId());
        } finally { executor.shutdownNow(); }
        Rider original = riders.findByUserId(id).orElseThrow();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Rider stored = riders.findByUserIdForUpdate(id).orElseThrow();
            stored.setRideEnabled(true); stored.setDrivingEnabled(true); stored.setCreditScore(67); stored.setOnlineStatus(2);
            riders.saveAndFlush(stored);
        });
        assertEquals("RIDER", enroll(id, "lost-response-code").role());
        Rider after = riders.findByUserId(id).orElseThrow();
        assertEquals(original.getId(), after.getId()); assertEquals(original.getName(), after.getName());
        assertEquals(67, after.getCreditScore()); assertEquals(2, after.getOnlineStatus());
        assertTrue(after.isRideEnabled()); assertTrue(after.isDrivingEnabled());
        assertEquals("RIDER", users.findById(id).orElseThrow().getRole());
        assertEquals("13800138101", users.findById(id).orElseThrow().getMobile());
        assertTrue(identities.existsByUserIdAndAppId(id, "enrollment-app"));
        verify(phones, times(2)).exchange(anyString(), eq(credentials));
    }

    @Test
    void tokenFailureRollsBackRoleAndRiderTogether() throws Exception {
        long id = account("13800138102");
        when(phones.exchange("rollback-code", credentials)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new WechatPhoneClient.Phone("enrollment-app", "13800138102");
        });
        doThrow(BizException.of(503, "签名服务暂不可用"))
                .when(tokens).login(any(User.class), eq("RIDER"), eq("enrollment-app"));
        assertEquals(503, assertThrows(BizException.class, () -> enroll(id, "rollback-code")).getCode());
        assertTrue(riders.findByUserId(id).isEmpty());
        assertEquals("USER", users.findById(id).orElseThrow().getRole());
    }

    @Test
    void frozenArchiveNeverReactivatesOrExchangesAgain() throws Exception {
        long id = account("13800138103");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Rider rider = new Rider(); rider.setUserId(id); rider.setName("冻结档案"); rider.setStatus("FROZEN");
            rider.setRideEnabled(true); rider.setCreditScore(51); riders.saveAndFlush(rider);
        });
        assertEquals(403, assertThrows(BizException.class, () -> enroll(id, "unused-code")).getCode());
        Rider after = riders.findByUserId(id).orElseThrow();
        assertEquals("FROZEN", after.getStatus()); assertEquals(51, after.getCreditScore()); assertTrue(after.isRideEnabled());
        assertEquals("USER", users.findById(id).orElseThrow().getRole());
        verifyNoInteractions(phones);
    }

    private LoginVO enroll(long userId, String code) {
        UserContext.set(new UserContext.CurrentUser(userId, "USER", "RIDER", "enrollment-app"));
        try { return enrollment.enroll(new EnrollmentReq("入驻姓名", "贵D12345", code, true)); }
        finally { UserContext.clear(); }
    }
}
