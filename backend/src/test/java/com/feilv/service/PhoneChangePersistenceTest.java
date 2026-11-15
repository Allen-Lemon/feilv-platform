package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.*;
import com.feilv.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 H2 提交/回滚与 t_user 行锁竞争；微信、短信均本地 mock，不访问远程数据库。 */
@DataJpaTest(showSql = false, properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.show-sql=false"})
@Import(AuditService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PhoneChangePersistenceTest {
    @Autowired UserRepository users;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired WechatIdentityRepository identities;
    @Autowired WalletAccountRepository wallets;
    @Autowired OrderRepository orders;
    @Autowired OpLogRepository logs;
    @Autowired AuditService audit;
    @Autowired PlatformTransactionManager manager;
    @MockBean MessageService messages;
    @MockBean WechatPhoneClient phone;
    @MockBean WechatConfigService settings;
    private static final AtomicInteger SEQUENCE = new AtomicInteger(50000);
    private final WechatConfigService.LoginCredentials credentials = new WechatConfigService.LoginCredentials("change-persistence-app", "secret");
    private UserRepository controlledUsers;
    private AuditService controlledAudit;
    private AtomicReference<Instant> now;
    private SmsPhoneBindingService sms;
    private PhoneChangeService changes;
    private DevelopmentPhoneBindingService development;

    @BeforeEach
    void setup() {
        now = new AtomicReference<>(Instant.parse("2026-11-15T02:00:00Z"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        controlledUsers = mock(UserRepository.class, AdditionalAnswers.delegatesTo(users));
        controlledAudit = spy(audit);
        sms = new SmsPhoneBindingService(controlledUsers, messages, controlledAudit, entityManager, manager, true, clock, new SecureRandom());
        var env = new MockEnvironment(); env.setActiveProfiles("local");
        development = new DevelopmentPhoneBindingService(controlledUsers, manager, env, true);
        changes = new PhoneChangeService(controlledUsers, identities, settings, phone, development, controlledAudit, entityManager, manager);
        when(settings.effectiveLogin()).thenReturn(credentials);
    }

    @Test
    void successfulSmsWechatAndDevelopmentChangesPreserveIdentityWalletAndHistoricalOrder() {
        long id = create("RIDER");
        String original = users.findById(id).orElseThrow().getMobile();
        long orderId = seedHistory(id, original);
        String smsMobile = mobile();
        String code = sms.sendChangeCode(id, smsMobile).demoCode();
        assertEquals(mask(smsMobile), sms.changePhone(id, smsMobile, code).mobile());
        String wechatMobile = mobile();
        when(phone.exchange("history", credentials)).thenReturn(new WechatPhoneClient.Phone(credentials.appId(), wechatMobile));
        assertEquals(mask(wechatMobile), changes.changeWechatPhone(id, "history").mobile());
        String devMobile = mobile();
        assertEquals(mask(devMobile), changes.changeDevelopmentPhone(id, devMobile).mobile());
        User current = users.findById(id).orElseThrow();
        assertEquals(id, current.getId()); assertEquals("RIDER", current.getRole()); assertEquals(1, current.getStatus());
        assertEquals("原昵称", current.getNickname()); assertEquals("/original-avatar.png", current.getAvatarUrl());
        assertTrue(identities.existsByUserIdAndAppId(id, credentials.appId()));
        WalletAccount wallet = wallets.findById(id).orElseThrow();
        assertEquals(12345, wallet.getAvailableFen()); assertEquals(456, wallet.getFrozenFen());
        assertEquals(0, wallet.getDebtFen()); assertEquals(12801, wallet.getTotalIncomeFen()); assertEquals(200, wallet.getTotalWithdrawnFen());
        OrderMain order = orders.findById(orderId).orElseThrow();
        assertEquals(id, order.getUserId()); assertEquals(id, order.getRiderId());
        assertEquals(original, order.getFromPhone()); assertEquals(original, order.getToPhone());
        assertEquals(com.feilv.enums.OrderStatus.FINISHED, order.getStatus()); assertEquals(1000, order.getRealFee());
        var entries = logs.findAll().stream().filter(log -> log.getDetail().startsWith("userId=" + id + ",")).toList();
        assertEquals(3, entries.size());
        assertTrue(entries.stream().allMatch(log -> "PHONE_CHANGE".equals(log.getAction()) && !log.getDetail().matches(".*1[3-9][0-9]{9}.*")));
        assertTrue(entries.stream().anyMatch(log -> log.getDetail().contains("channel=SMS")));
        assertTrue(entries.stream().anyMatch(log -> log.getDetail().contains("channel=WECHAT")));
        assertTrue(entries.stream().anyMatch(log -> log.getDetail().contains("channel=DEVELOPMENT")));
    }

    @Test
    void concurrentWechatResponsesCannotOverwriteTheWinningNewMobile() throws Exception {
        long id = create("USER");
        String firstMobile = mobile(), secondMobile = mobile();
        var bothExchanging = new CountDownLatch(2);
        when(phone.exchange(anyString(), eq(credentials))).thenAnswer(call -> {
            bothExchanging.countDown(); await(bothExchanging);
            return new WechatPhoneClient.Phone(credentials.appId(), "first".equals(call.getArgument(0)) ? firstMobile : secondMobile);
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> result(() -> changes.changeWechatPhone(id, "first")));
            var b = pool.submit(() -> result(() -> changes.changeWechatPhone(id, "second")));
            int ra = a.get(10, TimeUnit.SECONDS), rb = b.get(10, TimeUnit.SECONDS);
            assertTrue(ra == 0 && rb == 409 || ra == 409 && rb == 0, ra + "/" + rb);
            assertEquals(ra == 0 ? firstMobile : secondMobile, users.findById(id).orElseThrow().getMobile());
            assertEquals(1, auditCount(id));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void delayedWechatCannotOverwriteSmsThatCommitsDuringExchange() throws Exception {
        long id = create("USER");
        String smsMobile = mobile(), wechatMobile = mobile();
        String code = sms.sendChangeCode(id, smsMobile).demoCode();
        var exchanging = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(phone.exchange("delayed", credentials)).thenAnswer(call -> {
            exchanging.countDown(); await(release); return new WechatPhoneClient.Phone(credentials.appId(), wechatMobile);
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> result(() -> changes.changeWechatPhone(id, "delayed")));
            await(exchanging);
            assertEquals(0, result(() -> sms.changePhone(id, smsMobile, code)));
            release.countDown();
            assertEquals(409, pending.get(10, TimeUnit.SECONDS));
            assertEquals(smsMobile, users.findById(id).orElseThrow().getMobile());
            assertEquals(1, auditCount(id));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void concurrentSmsTargetsHaveOneWinnerAndInvalidateOtherOriginalSnapshotCredential() throws Exception {
        long id = create("RIDER");
        String firstMobile = mobile(), secondMobile = mobile();
        String firstCode = sms.sendChangeCode(id, firstMobile).demoCode();
        now.updateAndGet(value -> value.plusSeconds(60));
        String secondCode = sms.sendChangeCode(id, secondMobile).demoCode();
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { await(start); return result(() -> sms.changePhone(id, firstMobile, firstCode)); });
            var b = pool.submit(() -> { await(start); return result(() -> sms.changePhone(id, secondMobile, secondCode)); });
            start.countDown();
            int ra = a.get(10, TimeUnit.SECONDS), rb = b.get(10, TimeUnit.SECONDS);
            assertTrue(ra == 0 && rb == 4010 || ra == 4010 && rb == 0, ra + "/" + rb);
            assertEquals(ra == 0 ? firstMobile : secondMobile, users.findById(id).orElseThrow().getMobile());
            assertEquals(4010, result(() -> sms.changePhone(id, firstMobile, firstCode)));
            assertEquals(4010, result(() -> sms.changePhone(id, secondMobile, secondCode)));
            assertEquals(1, auditCount(id));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void twoAccountsRacingForNewMobileRollbackLoserAndDoNotRestoreCredential() throws Exception {
        long first = create("USER"), second = create("RIDER");
        String originalA = users.findById(first).orElseThrow().getMobile();
        String originalB = users.findById(second).orElseThrow().getMobile();
        String target = mobile();
        String codeA = sms.sendChangeCode(first, target).demoCode();
        now.updateAndGet(value -> value.plusSeconds(60));
        String codeB = sms.sendChangeCode(second, target).demoCode();
        var bothChecked = new CountDownLatch(2);
        doAnswer(call -> {
            var found = users.findByMobile(target); bothChecked.countDown(); await(bothChecked); return found;
        }).when(controlledUsers).findByMobile(target);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> result(() -> sms.changePhone(first, target, codeA)));
            var b = pool.submit(() -> result(() -> sms.changePhone(second, target, codeB)));
            int ra = a.get(10, TimeUnit.SECONDS), rb = b.get(10, TimeUnit.SECONDS);
            assertTrue(ra == 0 && rb == 409 || ra == 409 && rb == 0, ra + "/" + rb);
            long winner = ra == 0 ? first : second, loser = ra == 0 ? second : first;
            String loserOriginal = ra == 0 ? originalB : originalA;
            assertEquals(target, users.findById(winner).orElseThrow().getMobile());
            assertEquals(loserOriginal, users.findById(loser).orElseThrow().getMobile());
            assertEquals(1, auditCount(winner)); assertEquals(0, auditCount(loser));
            assertTrue(identities.existsByUserIdAndAppId(loser, credentials.appId()));
            // 释放赢家号码后重试也不能复活已消费的败方凭证。
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                User owner = users.findByIdForUpdate(winner).orElseThrow(); owner.setMobile(mobile()); users.saveAndFlush(owner);
            });
            assertEquals(4010, result(() -> sms.changePhone(loser, target, loser == first ? codeA : codeB)));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void auditFailureRollsBackAlreadyFlushedMobileAndLeavesCredentialConsumed() {
        long id = create("USER"); String original = users.findById(id).orElseThrow().getMobile(), target = mobile();
        String code = sms.sendChangeCode(id, target).demoCode();
        doThrow(new IllegalStateException("local audit failure")).when(controlledAudit).record(eq("PHONE_CHANGE"), anyString());
        assertThrows(IllegalStateException.class, () -> sms.changePhone(id, target, code));
        assertEquals(original, users.findById(id).orElseThrow().getMobile()); assertEquals(0, auditCount(id));
        doNothing().when(controlledAudit).record(anyString(), anyString());
        assertEquals(4010, result(() -> sms.changePhone(id, target, code)));
    }

    @Test
    void databaseFreezeDuringExchangeIsRecheckedAndNoMobileOrAuditChanges() throws Exception {
        long id = create("USER"); String original = users.findById(id).orElseThrow().getMobile();
        var exchanging = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(phone.exchange("freeze", credentials)).thenAnswer(call -> {
            exchanging.countDown(); await(release); return new WechatPhoneClient.Phone(credentials.appId(), mobile());
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> result(() -> changes.changeWechatPhone(id, "freeze")));
            await(exchanging);
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                User user = users.findByIdForUpdate(id).orElseThrow(); user.setStatus(0); users.saveAndFlush(user);
            });
            release.countDown(); assertEquals(403, pending.get(10, TimeUnit.SECONDS));
            assertEquals(original, users.findById(id).orElseThrow().getMobile()); assertEquals(0, auditCount(id));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void developmentAndSmsShareUserLineLockAndOldSmsSnapshotCannotOverwriteDevelopment() throws Exception {
        long id = create("USER"); String target = mobile(), devMobile = mobile();
        String code = sms.sendChangeCode(id, target).demoCode();
        var devHasLock = new CountDownLatch(1); var releaseDev = new CountDownLatch(1);
        doAnswer(call -> {
            devHasLock.countDown(); await(releaseDev); return users.saveAndFlush(call.getArgument(0));
        }).when(controlledUsers).saveAndFlush(argThat(user -> user != null && devMobile.equals(user.getMobile())));
        var smsAtLock = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var dev = pool.submit(() -> result(() -> changes.changeDevelopmentPhone(id, devMobile)));
            await(devHasLock);
            doAnswer(call -> { smsAtLock.countDown(); return users.findByIdForUpdate(id); }).when(controlledUsers).findByIdForUpdate(id);
            var pending = pool.submit(() -> result(() -> sms.changePhone(id, target, code)));
            await(smsAtLock);
            assertThrows(TimeoutException.class, () -> pending.get(150, TimeUnit.MILLISECONDS));
            releaseDev.countDown();
            assertEquals(0, dev.get(10, TimeUnit.SECONDS)); assertEquals(4010, pending.get(10, TimeUnit.SECONDS));
            assertEquals(devMobile, users.findById(id).orElseThrow().getMobile()); assertEquals(1, auditCount(id));
        } finally { releaseDev.countDown(); pool.shutdownNow(); }
    }

    @Test
    void preloadedCallerEntityCannotHideConcurrentMobileChangeFromWechatSnapshotCheck() throws Exception {
        long id = create("USER"); String original = users.findById(id).orElseThrow().getMobile();
        String committed = mobile(), delayed = mobile();
        var loaded = new CountDownLatch(1); var changed = new CountDownLatch(1);
        when(phone.exchange("stale-context", credentials)).thenReturn(new WechatPhoneClient.Phone(credentials.appId(), delayed));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var caller = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                User cached = users.findById(id).orElseThrow(); assertEquals(original, cached.getMobile());
                loaded.countDown();
                try { await(changed); } catch (InterruptedException error) { throw new AssertionError(error); }
                assertEquals(original, cached.getMobile(), "证明一级缓存仍是旧号");
                int outcome = result(() -> changes.changeWechatPhone(id, "stale-context"));
                status.setRollbackOnly(); // 内层 REQUIRED 已因预期业务异常标记回滚。
                return outcome;
            }));
            await(loaded);
            assertEquals(0, result(() -> changes.changeDevelopmentPhone(id, committed)));
            changed.countDown(); assertEquals(409, caller.get(10, TimeUnit.SECONDS));
            assertEquals(committed, users.findById(id).orElseThrow().getMobile()); assertEquals(1, auditCount(id));
        } finally { changed.countDown(); pool.shutdownNow(); }
    }

    @Test
    void preloadedCallerEntityCannotUseOldSmsCredentialAfterOtherTransactionChangesPhone() throws Exception {
        long id = create("USER"); String original = users.findById(id).orElseThrow().getMobile();
        String target = mobile(), committed = mobile(); String code = sms.sendChangeCode(id, target).demoCode();
        var loaded = new CountDownLatch(1); var changed = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var caller = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                User cached = users.findById(id).orElseThrow(); loaded.countDown();
                try { await(changed); } catch (InterruptedException error) { throw new AssertionError(error); }
                assertEquals(original, cached.getMobile());
                int outcome = result(() -> sms.changePhone(id, target, code));
                status.setRollbackOnly();
                return outcome;
            }));
            await(loaded); changes.changeDevelopmentPhone(id, committed); changed.countDown();
            assertEquals(4010, caller.get(10, TimeUnit.SECONDS));
            assertEquals(committed, users.findById(id).orElseThrow().getMobile()); assertEquals(1, auditCount(id));
        } finally { changed.countDown(); pool.shutdownNow(); }
    }

    @Test
    void preloadedCallerEntityCannotHideFreezeFromDevelopmentChange() throws Exception {
        long id = create("USER"); String original = users.findById(id).orElseThrow().getMobile(), target = mobile();
        var loaded = new CountDownLatch(1); var frozen = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var caller = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                User cached = users.findById(id).orElseThrow(); loaded.countDown();
                try { await(frozen); } catch (InterruptedException error) { throw new AssertionError(error); }
                assertEquals(1, cached.getStatus());
                int outcome = result(() -> changes.changeDevelopmentPhone(id, target));
                status.setRollbackOnly();
                return outcome;
            }));
            await(loaded);
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                User current = users.findByIdForUpdate(id).orElseThrow(); current.setStatus(0); users.saveAndFlush(current);
            });
            frozen.countDown(); assertEquals(403, caller.get(10, TimeUnit.SECONDS));
            assertEquals(original, users.findById(id).orElseThrow().getMobile()); assertEquals(0, auditCount(id));
        } finally { frozen.countDown(); pool.shutdownNow(); }
    }

    private long create(String role) {
        return new TransactionTemplate(manager).execute(status -> {
            User user = new User(); user.setMobile(mobile()); user.setRole(role); user.setNickname("原昵称"); user.setAvatarUrl("/original-avatar.png");
            users.saveAndFlush(user);
            WechatIdentity identity = new WechatIdentity(); identity.setUserId(user.getId()); identity.setAppId(credentials.appId()); identity.setOpenid(UUID.randomUUID().toString());
            identities.saveAndFlush(identity); return user.getId();
        });
    }

    private long seedHistory(long id, String original) {
        return new TransactionTemplate(manager).execute(status -> {
            WalletAccount wallet = new WalletAccount(); wallet.setUserId(id); wallet.setAvailableFen(12345); wallet.setFrozenFen(456);
            wallet.setTotalIncomeFen(12801); wallet.setTotalWithdrawnFen(200); wallet.setCreatedTime(LocalDateTime.now()); wallet.setUpdatedTime(LocalDateTime.now());
            wallets.saveAndFlush(wallet);
            OrderMain order = new OrderMain(); order.setOrderNo("PHONE-HISTORY-" + UUID.randomUUID()); order.setOrderType("SEND");
            order.setUserId(id); order.setRiderId(id); order.setFromContact("历史寄件人"); order.setFromPhone(original); order.setFromAddress("历史起点");
            order.setToContact("历史收件人"); order.setToPhone(original); order.setToAddress("历史终点"); order.setRealFee(1000); order.setEstimateFee(1000);
            order.setStatus(com.feilv.enums.OrderStatus.FINISHED); return orders.saveAndFlush(order).getId();
        });
    }

    private long auditCount(long userId) {
        return logs.findAll().stream().filter(log -> log.getDetail().startsWith("userId=" + userId + ",")).count();
    }
    private static String mobile() { return String.format(java.util.Locale.ROOT, "138%08d", SEQUENCE.incrementAndGet()); }
    private static String mask(String mobile) { return mobile.substring(0, 3) + "****" + mobile.substring(7); }
    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrency rendezvous timed out");
    }
    private static int result(Runnable action) {
        try { action.run(); return 0; } catch (BizException error) { return error.getCode(); }
    }
}
