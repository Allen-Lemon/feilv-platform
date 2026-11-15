package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.domain.WechatIdentity;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.mockito.AdditionalAnswers;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅 H2：验证真实提交/回滚、唯一索引及手动/微信共用用户行锁，不调用微信或远程数据库。 */
@DataJpaTest(showSql = false, properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.show-sql=false"})
@Import(UserProfileService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SmsPhoneBindingPersistenceTest {
    @Autowired UserRepository users;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired WechatIdentityRepository identities;
    @Autowired PlatformTransactionManager manager;
    @Autowired UserProfileService profiles;
    @Autowired org.springframework.core.env.Environment environment;
    @MockBean MessageService messages;
    @MockBean AuditService audit;
    @MockBean WechatPhoneClient phone;
    @MockBean WechatConfigService settings;
    private SmsPhoneBindingService binding;
    private UserRepository controlledUsers;
    private AtomicReference<Instant> now;
    private final WechatConfigService.LoginCredentials credentials =
            new WechatConfigService.LoginCredentials("sms-binding-app", "mock-secret");

    @BeforeEach
    void setup() {
        now = new AtomicReference<>(Instant.parse("2026-11-14T02:00:00Z"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        // 默认仍委托真实 Spring Data 代理；仅在竞争用例中插入可控同步点。
        controlledUsers = mock(UserRepository.class, AdditionalAnswers.delegatesTo(users));
        binding = new SmsPhoneBindingService(controlledUsers, messages, audit, entityManager, manager, true, clock, new SecureRandom());
        when(settings.effectiveLogin()).thenReturn(credentials);
    }

    @Test
    void bindingCommitsImmediatelyAndExistingProfileOrderGateSeesItWithoutNewLogin() {
        long id = create("sms-persist", "RIDER");
        String mobile = "13800138101";
        String code = binding.sendCode(id, mobile).demoCode();
        var profile = binding.bindPhone(id, mobile, code);
        assertEquals("138****8101", profile.mobile());
        assertEquals(mobile, users.findById(id).orElseThrow().getMobile());
        assertEquals("RIDER", profiles.me(id).role());
        assertTrue(profiles.me(id).mobileBound());
        assertEquals(profile.avatarUrl(), profiles.me(id).avatarUrl());
        new TransactionTemplate(manager).executeWithoutResult(status -> profiles.requireBoundMobile(id));
        now.updateAndGet(value -> value.plusSeconds(60));
        String sameCode = binding.sendCode(id, mobile).demoCode();
        assertTrue(binding.bindPhone(id, mobile, sameCode).mobileBound());
        assertTrue(identities.existsByUserIdAndAppId(id, credentials.appId()));
    }

    @Test
    void concurrentManualVerificationsHaveExactlyOneConsumer() throws Exception {
        long id = create("sms-once", "USER");
        String mobile = "13800138102";
        String code = binding.sendCode(id, mobile).demoCode();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> { await(start); return result(() -> binding.bindPhone(id, mobile, code)); });
            var b = executor.submit(() -> { await(start); return result(() -> binding.bindPhone(id, mobile, code)); });
            start.countDown();
            int first = a.get(10, TimeUnit.SECONDS), second = b.get(10, TimeUnit.SECONDS);
            assertTrue(first == 0 && second == 4010 || first == 4010 && second == 0);
            assertEquals(mobile, users.findById(id).orElseThrow().getMobile());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void uniqueIndexRaceAcrossDifferentAccountsRollsBackLoserWithoutMerging() throws Exception {
        assertTrue(org.slf4j.LoggerFactory.getLogger("org.hibernate.engine.jdbc.spi.SqlExceptionHelper").isErrorEnabled(),
                "必须保留数据库错误日志用于排障");
        var context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var encoder = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(environment.getRequiredProperty("logging.pattern.console"));
        encoder.start();
        try {
            var event = new ch.qos.logback.classic.spi.LoggingEvent();
            event.setLevel(ch.qos.logback.classic.Level.ERROR); event.setLoggerName("org.hibernate.engine.jdbc.spi.SqlExceptionHelper");
            event.setMessage("Unique constraint violation for 13800138103");
            event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(new RuntimeException("conflict for 13900139103")));
            String output = new String(encoder.encode(event), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(output.contains("Unique constraint violation")); assertTrue(output.contains("RuntimeException"));
            assertTrue(output.contains("[PHONE_REDACTED]")); assertFalse(output.contains("13800138103")); assertFalse(output.contains("13900139103"));
        } finally { encoder.stop(); }
        long first = create("sms-owner-a", "USER"), second = create("sms-owner-b", "RIDER");
        String mobile = "13800138103";
        String firstCode = binding.sendCode(first, mobile).demoCode();
        now.updateAndGet(value -> value.plusSeconds(60));
        String secondCode = binding.sendCode(second, mobile).demoCode();
        // 让两个真实事务都先查到“号码未占用”，强制触发唯一约束竞争而非预检查。
        var bothChecked = new CountDownLatch(2);
        doAnswer(call -> {
            var found = users.findByMobile(mobile);
            bothChecked.countDown();
            await(bothChecked);
            return found;
        }).when(controlledUsers).findByMobile(mobile);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> result(() -> binding.bindPhone(first, mobile, firstCode)));
            var b = executor.submit(() -> result(() -> binding.bindPhone(second, mobile, secondCode)));
            int resultA = a.get(10, TimeUnit.SECONDS), resultB = b.get(10, TimeUnit.SECONDS);
            assertTrue(resultA == 0 && resultB == 409 || resultA == 409 && resultB == 0);
            long winner = resultA == 0 ? first : second, loser = winner == first ? second : first;
            assertEquals(mobile, users.findById(winner).orElseThrow().getMobile());
            assertNull(users.findById(loser).orElseThrow().getMobile());
            assertTrue(identities.existsByUserIdAndAppId(first, credentials.appId()));
            assertTrue(identities.existsByUserIdAndAppId(second, credentials.appId()));
            assertEquals("USER", users.findById(first).orElseThrow().getRole());
            assertEquals("RIDER", users.findById(second).orElseThrow().getRole());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void manualAndWechatCannotOverwriteEachOtherOnSameUser() throws Exception {
        long id = create("sms-wechat-race", "USER");
        String manualMobile = "13800138104", wechatMobile = "13900139104";
        String code = binding.sendCode(id, manualMobile).demoCode();
        var exchanging = new CountDownLatch(1);
        var releaseWechat = new CountDownLatch(1);
        when(phone.exchange("wechat-race", credentials)).thenAnswer(call -> {
            exchanging.countDown();
            await(releaseWechat);
            return new WechatPhoneClient.Phone(credentials.appId(), wechatMobile);
        });
        var manualHasLock = new CountDownLatch(1);
        var releaseManual = new CountDownLatch(1);
        doAnswer(call -> {
            manualHasLock.countDown();
            await(releaseManual);
            return users.saveAndFlush(call.getArgument(0));
        }).when(controlledUsers).saveAndFlush(argThat(value -> value != null && manualMobile.equals(value.getMobile())));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var wechat = executor.submit(() -> result(() -> profiles.bindWechatPhone(id, "wechat-race")));
            await(exchanging);
            var manual = executor.submit(() -> result(() -> binding.bindPhone(id, manualMobile, code)));
            await(manualHasLock);
            releaseWechat.countDown();
            releaseManual.countDown();
            assertEquals(0, manual.get(10, TimeUnit.SECONDS));
            assertEquals(409, wechat.get(10, TimeUnit.SECONDS));
            assertEquals(manualMobile, users.findById(id).orElseThrow().getMobile());
            assertTrue(identities.existsByUserIdAndAppId(id, credentials.appId()));
        } finally {
            releaseManual.countDown(); releaseWechat.countDown(); executor.shutdownNow();
        }
    }

    @Test
    void wechatCommitAfterSendingPreventsManualOverwrite() {
        long id = create("sms-wechat-first", "USER");
        String manualMobile = "13800138105", wechatMobile = "13900139105";
        String code = binding.sendCode(id, manualMobile).demoCode();
        when(phone.exchange("wechat-first", credentials)).thenReturn(new WechatPhoneClient.Phone(credentials.appId(), wechatMobile));
        assertTrue(profiles.bindWechatPhone(id, "wechat-first").mobileBound());
        assertEquals(409, result(() -> binding.bindPhone(id, manualMobile, code)));
        assertEquals(wechatMobile, users.findById(id).orElseThrow().getMobile());
    }

    @Test
    void databaseFreezeBetweenSendAndBindIsRechecked() {
        long id = create("sms-frozen", "USER");
        String mobile = "13800138106";
        String code = binding.sendCode(id, mobile).demoCode();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            User account = users.findByIdForUpdate(id).orElseThrow();
            account.setStatus(0);
            users.saveAndFlush(account);
        });
        assertEquals(403, result(() -> binding.bindPhone(id, mobile, code)));
        assertNull(users.findById(id).orElseThrow().getMobile());
    }

    private long create(String marker, String role) {
        return new TransactionTemplate(manager).execute(status -> {
            User user = new User(); user.setRole(role); user.setNickname("原昵称");
            user.setAvatarUrl("/api/v1/avatars/12345678-1234-1234-1234-123456789abc.png");
            users.saveAndFlush(user);
            WechatIdentity identity = new WechatIdentity(); identity.setUserId(user.getId());
            identity.setAppId(credentials.appId()); identity.setOpenid(marker);
            identities.saveAndFlush(identity);
            return user.getId();
        });
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrency rendezvous timed out");
    }

    private static int result(Runnable call) {
        try { call.run(); return 0; }
        catch (BizException e) { return e.getCode(); }
    }
}
