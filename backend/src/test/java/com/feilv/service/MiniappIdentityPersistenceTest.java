package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual isolated commits, rollbacks and unique constraints; every WeChat call is mocked. */
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:dual_identity;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.show-sql=false",
        "security.jwt-secret=identity-test-key-only-2026-11-17-at-least-32-bytes"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MiniappIdentityLinkService.class, AuthTokenService.class, AuditService.class, DevelopmentPhonePolicy.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MiniappIdentityPersistenceTest {
    @Autowired MiniappIdentityLinkService service;
    @Autowired UserRepository users;
    @Autowired WechatIdentityRepository identities;
    @Autowired RiderRepository riders;
    @Autowired MiniappLoginTicketRepository tickets;
    @Autowired MiniappLinkCodeRepository links;
    @Autowired WalletAccountRepository wallets;
    @Autowired OrderRepository orders;
    @Autowired OpLogRepository logs;
    @Autowired AuthTokenService tokens;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource datasource;
    @Autowired EntityManager entityManager;
    @MockBean WechatConfigService settings;
    @MockBean WechatSessionClient sessions;
    @MockBean WechatPhoneClient phones;
    @SpyBean AuditService audit;
    private static final AtomicInteger SEQUENCE = new AtomicInteger(200000);
    private final WechatConfigService.LoginCredentials userConfig = new WechatConfigService.LoginCredentials("identity-user-app", "user-secret");
    private final WechatConfigService.LoginCredentials riderConfig = new WechatConfigService.LoginCredentials("identity-rider-app", "rider-secret");

    @BeforeEach void setup() throws Exception {
        UserContext.clear();
        try (var connection = datasource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"), "Never run against a business database");
        }
        when(settings.effectiveLogin("USER")).thenReturn(userConfig);
        when(settings.effectiveLogin("RIDER")).thenReturn(riderConfig);
    }
    @AfterEach void clear() { UserContext.clear(); }

    @Test void unmappedLoginIssuesHashOnlyTicketAndDoesNotCreateOrphanAccountOrWallet() {
        long beforeUsers = users.count(), beforeWallets = wallets.count();
        var result = login("RIDER", unique());
        assertEquals("IDENTITY_REQUIRED", result.state()); assertNull(result.login());
        assertTrue(result.loginTicket().matches("[A-Za-z0-9_-]{43}"));
        assertTrue(Instant.parse(result.expiresAt()).isAfter(Instant.now().plusSeconds(170)));
        assertFalse(tickets.findById(result.loginTicket()).isPresent());
        var ticket = tickets.findById(MiniappIdentityLinkService.digest(result.loginTicket())).orElseThrow();
        assertEquals("RIDER", ticket.getClient()); assertEquals(riderConfig.appId(), ticket.getAppId());
        assertEquals(beforeUsers, users.count()); assertEquals(beforeWallets, wallets.count());
        verify(sessions).exchange(anyString(), eq(riderConfig));
        verify(settings, never()).effectiveLogin("TYPO");
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "RIDER"})
    void newAccountRequiresTrueUnusedWechatPhoneConsumesTicketAndRecoversLostResponse(String client) {
        String openid = unique(), mobile = mobile(), code = unique();
        var ticket = login(client, openid);
        when(phones.exchange(code, config(client))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new WechatPhoneClient.Phone(config(client).appId(), mobile);
        });
        var result = service.createAccount(client, ticket.loginTicket(), code, true);
        assertEquals("USER", result.role());
        assertEquals(mobile, users.findById(result.userId()).orElseThrow().getMobile());
        assertEquals(client, tokens.parse(result.token()).aud);
        assertEquals(config(client).appId(), tokens.parse(result.token()).appId);
        assertTrue(stored(ticket).isConsumed());
        assertEquals(401, error(() -> service.createAccount(client, ticket.loginTicket(), unique(), true)));
        var recovered = login(client, openid);
        assertEquals("AUTHENTICATED", recovered.state()); assertEquals(result.userId(), recovered.login().userId());
        assertEquals(1, auditCount("MINIAPP_ACCOUNT_CREATE", result.userId()));
    }

    @Test void usedPhoneCannotSelectAccountOrLeaveAnOrphanAndUnionidIsNotAMergeKey() {
        long existing = account("USER", "USER", unique());
        String originalMobile = users.findById(existing).orElseThrow().getMobile();
        var ticket = login("RIDER", unique());
        long before = users.count(), identityCount = identities.count();
        when(phones.exchange("occupied-phone", riderConfig)).thenReturn(new WechatPhoneClient.Phone(riderConfig.appId(), originalMobile));
        assertEquals(409, error(() -> service.createAccount("RIDER", ticket.loginTicket(), "occupied-phone", true)));
        assertEquals(before, users.count()); assertEquals(identityCount, identities.count());
        assertFalse(stored(ticket).isConsumed());
        assertEquals("USER", users.findById(existing).orElseThrow().getRole());
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "RIDER"})
    void explicitBidirectionalLinkPreservesSubjectWalletOrderAndRiderCapabilities(String source) {
        String target = opposite(source), sourceOpenid = unique(), targetOpenid = unique();
        long id = account("RIDER", source, sourceOpenid);
        long riderId = riders.findByUserId(id).orElseThrow().getId();
        long orderId = history(id, riderId);
        var ticket = login(target, targetOpenid);
        var link = issue(id, source, sourceOpenid);
        assertEquals(target, link.targetClient());
        assertFalse(links.findById(link.linkCode()).isPresent());
        var result = service.link(target, ticket.loginTicket(), link.linkCode(), true);
        assertEquals(id, result.userId()); assertEquals("RIDER", result.role());
        assertEquals(target, tokens.parse(result.token()).aud);
        assertEquals(id, identities.findByAppIdAndOpenid(config(target).appId(), targetOpenid).orElseThrow().getUserId());
        assertEquals(id, identities.findByAppIdAndOpenid(config(source).appId(), sourceOpenid).orElseThrow().getUserId());
        Rider rider = riders.findByUserId(id).orElseThrow();
        assertEquals(riderId, rider.getId()); assertEquals(73, rider.getCreditScore());
        assertTrue(rider.isRideEnabled()); assertFalse(rider.isDrivingEnabled());
        assertEquals("ACTIVE", rider.getStatus());
        assertEquals(5432, wallets.findById(id).orElseThrow().getAvailableFen());
        OrderMain order = orders.findById(orderId).orElseThrow();
        assertEquals(id, order.getUserId()); assertEquals(riderId, order.getRiderId());
        assertEquals(1200, order.getRealFee()); assertTrue(stored(ticket).isConsumed());
        assertTrue(links.findById(MiniappIdentityLinkService.digest(link.linkCode())).orElseThrow().isConsumed());
        assertEquals(1, auditCount("MINIAPP_IDENTITY_LINK", id));
        assertEquals(id, login(target, targetOpenid).login().userId(), "lost response recovers by normal login");
    }

    @Test void bearerAloneCannotIssueLinkAndFreshWxLoginMustMatchCurrentSourceIdentity() {
        String openid = unique(); long id = account("USER", "USER", openid);
        UserContext.set(new UserContext.CurrentUser(id, "USER", "USER", userConfig.appId()));
        assertEquals(400, error(() -> service.issueLinkCode("code", false)));
        when(sessions.exchange("wrong-current-wx", userConfig)).thenReturn(new WechatSessionClient.Session(userConfig.appId(), unique(), null));
        assertEquals(403, error(() -> service.issueLinkCode("wrong-current-wx", true)));
        UserContext.set(new UserContext.CurrentUser(id, "USER"));
        assertEquals(401, error(() -> service.issueLinkCode("code", true)));
        UserContext.set(new UserContext.CurrentUser(id, "USER", "USER", riderConfig.appId()));
        assertEquals(401, error(() -> service.issueLinkCode("code", true)));
        assertEquals(0, auditCount("MINIAPP_LINK_CODE", id));
    }

    @Test void ticketAndLinkCodeReplayAreRejectedAndFailedAttemptsPersistBoundedly() {
        String sourceOpenid = unique(); long id = account("USER", "USER", sourceOpenid);
        var link = issue(id, "USER", sourceOpenid);
        var ticket = login("RIDER", unique());
        service.link("RIDER", ticket.loginTicket(), link.linkCode(), true);
        assertEquals(401, error(() -> service.link("RIDER", ticket.loginTicket(), link.linkCode(), true)));
        var another = login("RIDER", unique());
        assertEquals(400, error(() -> service.link("RIDER", another.loginTicket(), link.linkCode(), true)));
        assertEquals(1, stored(another).getFailures());
        String invalidCode = "X".repeat(43);
        for (int attempt = 1; attempt < 5; attempt++) {
            assertEquals(400, error(() -> service.link("RIDER", another.loginTicket(), invalidCode, true)));
        }
        assertTrue(stored(another).isConsumed()); assertEquals(5, stored(another).getFailures());
        assertEquals(429, error(() -> service.link("RIDER", another.loginTicket(), invalidCode, true)));
    }

    @Test void expiryWrongClientAndBothConfigRotationsInvalidateProofWithoutConsumption() {
        String openid = unique(); long id = account("USER", "USER", openid);
        var link = issue(id, "USER", openid); var ticket = login("RIDER", unique());
        assertEquals(401, error(() -> service.link("USER", ticket.loginTicket(), link.linkCode(), true)));
        when(settings.effectiveLogin("USER")).thenReturn(new WechatConfigService.LoginCredentials(userConfig.appId(), "rotated-source"));
        assertEquals(409, error(() -> service.link("RIDER", ticket.loginTicket(), link.linkCode(), true)));
        when(settings.effectiveLogin("USER")).thenReturn(userConfig);
        when(settings.effectiveLogin("RIDER")).thenReturn(new WechatConfigService.LoginCredentials(riderConfig.appId(), "rotated-target"));
        assertEquals(409, error(() -> service.link("RIDER", ticket.loginTicket(), link.linkCode(), true)));
        when(settings.effectiveLogin("RIDER")).thenReturn(riderConfig);
        tx(() -> { var row = tickets.findForUpdate(MiniappIdentityLinkService.digest(ticket.loginTicket())).orElseThrow(); row.setExpiresAt(Instant.now().minusSeconds(1)); tickets.saveAndFlush(row); });
        assertEquals(401, error(() -> service.link("RIDER", ticket.loginTicket(), link.linkCode(), true)));
        assertFalse(stored(ticket).isConsumed());
        var fresh = login("RIDER", unique());
        tx(() -> { var row = links.findForUpdate(MiniappIdentityLinkService.digest(link.linkCode())).orElseThrow(); row.setExpiresAt(Instant.now().minusSeconds(1)); links.saveAndFlush(row); });
        assertEquals(400, error(() -> service.link("RIDER", fresh.loginTicket(), link.linkCode(), true)));
        assertFalse(stored(fresh).isConsumed());
    }

    @Test void configRotationDuringExternalPhoneCallRejectsWriteAndExternalCallsAreOutsideCallerTransaction() {
        var ticket = login("RIDER", unique()); long beforeUsers = users.count();
        when(phones.exchange("rotate-during-http", riderConfig)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            when(settings.effectiveLogin("RIDER")).thenReturn(new WechatConfigService.LoginCredentials(riderConfig.appId(), "new-secret"));
            return new WechatPhoneClient.Phone(riderConfig.appId(), mobile());
        });
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertEquals(409, error(() -> service.createAccount("RIDER", ticket.loginTicket(), "rotate-during-http", true)));
        });
        assertEquals(beforeUsers, users.count()); assertFalse(stored(ticket).isConsumed());
    }

    @Test void adminsFrozenUsersFrozenRidersAndMissingRiderProfilesCannotBeLinked() {
        for (String condition : new String[]{"ADMIN", "USER_FROZEN", "RIDER_FROZEN", "RIDER_MISSING"}) {
            String openid = unique(); long id = account("RIDER", "USER", openid);
            var code = issue(id, "USER", openid); var ticket = login("RIDER", unique());
            tx(() -> {
                User user = users.findByIdForUpdate(id).orElseThrow();
                if ("ADMIN".equals(condition)) user.setRole("ADMIN");
                if ("USER_FROZEN".equals(condition)) user.setStatus(0);
                users.saveAndFlush(user);
                Rider rider = riders.findByUserId(id).orElseThrow();
                if ("RIDER_FROZEN".equals(condition)) { rider.setStatus("FROZEN"); riders.saveAndFlush(rider); }
                if ("RIDER_MISSING".equals(condition)) riders.delete(rider);
            });
            assertEquals("RIDER_MISSING".equals(condition) ? 409 : 403,
                    error(() -> service.link("RIDER", ticket.loginTicket(), code.linkCode(), true)), condition);
            assertFalse(stored(ticket).isConsumed());
            assertEquals(0, auditCount("MINIAPP_IDENTITY_LINK", id));
        }
    }

    @Test void existingIdentityIsNeverMovedEvenIfTicketWasIssuedBeforeAnotherAccountClaimsIt() {
        String sourceOpenid = unique(), targetOpenid = unique(); long first = account("USER", "USER", sourceOpenid);
        var ticket = login("RIDER", targetOpenid); var link = issue(first, "USER", sourceOpenid);
        long other = account("USER", "RIDER", targetOpenid);
        assertEquals(409, error(() -> service.link("RIDER", ticket.loginTicket(), link.linkCode(), true)));
        assertEquals(other, identities.findByAppIdAndOpenid(riderConfig.appId(), targetOpenid).orElseThrow().getUserId());
        assertEquals(other, login("RIDER", targetOpenid).login().userId());
        assertFalse(stored(ticket).isConsumed()); assertEquals(0, auditCount("MINIAPP_IDENTITY_LINK", first));
    }

    @Test void auditFailureRollsBackIdentityConsumptionAndNewAccountInSameTransaction() {
        var ticket = login("USER", unique()); String mobile = mobile(); long beforeUsers = users.count();
        when(phones.exchange("audit-phone", userConfig)).thenReturn(new WechatPhoneClient.Phone(userConfig.appId(), mobile));
        doThrow(new IllegalStateException("test audit unavailable")).when(audit).record(eq("MINIAPP_ACCOUNT_CREATE"), anyString());
        assertThrows(IllegalStateException.class, () -> service.createAccount("USER", ticket.loginTicket(), "audit-phone", true));
        assertEquals(beforeUsers, users.count()); assertTrue(users.findByMobile(mobile).isEmpty()); assertFalse(stored(ticket).isConsumed());
        doCallRealMethod().when(audit).record(eq("MINIAPP_ACCOUNT_CREATE"), anyString());
        String source = unique(); long id = account("USER", "USER", source);
        var code = issue(id, "USER", source); var targetTicket = login("RIDER", unique());
        long beforeIdentities = identities.count();
        doThrow(new IllegalStateException("test audit unavailable")).when(audit).record(eq("MINIAPP_IDENTITY_LINK"), anyString());
        assertThrows(IllegalStateException.class, () -> service.link("RIDER", targetTicket.loginTicket(), code.linkCode(), true));
        assertEquals(beforeIdentities, identities.count()); assertFalse(stored(targetTicket).isConsumed());
        assertFalse(links.findById(MiniappIdentityLinkService.digest(code.linkCode())).orElseThrow().isConsumed());
    }

    @Test void concurrentNewAccountsForOnePhoneRollbackTheLoserWithoutOrphanIdentity() throws Exception {
        var first = login("USER", unique()); var second = login("RIDER", unique());
        String mobile = mobile(); long beforeUsers = users.count(), beforeIdentities = identities.count();
        CountDownLatch both = new CountDownLatch(2);
        when(phones.exchange(eq("race-phone-user"), eq(userConfig))).thenAnswer(call -> { rendezvous(both); return new WechatPhoneClient.Phone(userConfig.appId(), mobile); });
        when(phones.exchange(eq("race-phone-rider"), eq(riderConfig))).thenAnswer(call -> { rendezvous(both); return new WechatPhoneClient.Phone(riderConfig.appId(), mobile); });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> error(() -> service.createAccount("USER", first.loginTicket(), "race-phone-user", true)));
            var b = pool.submit(() -> error(() -> service.createAccount("RIDER", second.loginTicket(), "race-phone-rider", true)));
            int ra = a.get(10, TimeUnit.SECONDS), rb = b.get(10, TimeUnit.SECONDS);
            assertTrue(ra == 0 && rb == 409 || ra == 409 && rb == 0, ra + "/" + rb);
            assertEquals(beforeUsers + 1, users.count()); assertEquals(beforeIdentities + 1, identities.count());
            assertNotEquals(stored(first).isConsumed(), stored(second).isConsumed());
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentConsumptionOfSameTicketCreatesOnlyOneSubject() throws Exception {
        var ticket = login("USER", unique()); long beforeUsers = users.count();
        CountDownLatch both = new CountDownLatch(2);
        when(phones.exchange(startsWith("same-ticket-"), eq(userConfig))).thenAnswer(call -> {
            rendezvous(both); return new WechatPhoneClient.Phone(userConfig.appId(), mobile());
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> error(() -> service.createAccount("USER", ticket.loginTicket(), "same-ticket-a", true)));
            var b = pool.submit(() -> error(() -> service.createAccount("USER", ticket.loginTicket(), "same-ticket-b", true)));
            int ra = a.get(10, TimeUnit.SECONDS), rb = b.get(10, TimeUnit.SECONDS);
            assertTrue(ra == 0 && rb == 401 || ra == 401 && rb == 0, ra + "/" + rb);
            assertEquals(beforeUsers + 1, users.count()); assertTrue(stored(ticket).isConsumed());
        } finally { pool.shutdownNow(); }
    }

    @Test void cleanupDeletesAtMostOneHundredExpiredRowsPerTable() {
        String prefix = unique();
        tx(() -> {
            for (int i = 0; i < 105; i++) {
                MiniappLoginTicket ticket = new MiniappLoginTicket(); ticket.setTokenHash(MiniappIdentityLinkService.digest(prefix + i));
                ticket.setClient("USER"); ticket.setAppId(userConfig.appId()); ticket.setOpenid(prefix + i);
                ticket.setConfigFingerprint("0".repeat(64)); ticket.setExpiresAt(Instant.now().minusSeconds(200000)); tickets.save(ticket);
            }
            tickets.flush();
        });
        // Fresh instance gives the bounded cleanup a fresh timer, without changing the application clock.
        var isolated = new MiniappIdentityLinkService(settings, sessions, phones, identities, users, riders, tickets, links, tokens, audit, manager, entityManager);
        String code = unique(); when(sessions.exchange(code, userConfig)).thenReturn(new WechatSessionClient.Session(userConfig.appId(), unique(), null));
        long before = tickets.count(); isolated.login("USER", code);
        assertEquals(before - 100 + 1, tickets.count());
    }

    @Test void developmentCreatesOnlyNewUserWithRealTicketAndSanitizedAuditWithoutPhoneUpstream() {
        var ticket = login("RIDER", unique()); String mobile = mobile(); long before = users.count();
        var manual = development(new MockEnvironment(), "local");
        var result = manual.createDevelopmentAccount("RIDER", ticket.loginTicket(), mobile, true);
        assertEquals(before + 1, users.count()); assertEquals("USER", result.role());
        assertEquals(mobile, users.findById(result.userId()).orElseThrow().getMobile());
        assertEquals("RIDER", tokens.parse(result.token()).aud);
        assertEquals(riderConfig.appId(), tokens.parse(result.token()).appId);
        assertTrue(stored(ticket).isConsumed());
        var entry = logs.findAll().stream().filter(log -> "MINIAPP_ACCOUNT_CREATE".equals(log.getAction())
                && log.getDetail().startsWith("userId=" + result.userId() + ",")).findFirst().orElseThrow();
        assertTrue(entry.getDetail().contains("channel=DEVELOPMENT"));
        assertFalse(entry.getDetail().contains(mobile)); assertFalse(entry.getDetail().contains(ticket.loginTicket()));
        assertEquals(401, error(() -> manual.createDevelopmentAccount("RIDER", ticket.loginTicket(), mobile(), true)));
        verifyNoInteractions(phones);
    }

    @Test void manualProofStillRequiresUnexpiredCurrentConfigUnusedRiderTicketAndDevelopmentGate() {
        var environment = new MockEnvironment(); var manual = development(environment, "development");
        var ticket = login("RIDER", unique()); long before = users.count();
        assertEquals(403, error(() -> manual.createDevelopmentAccount("USER", ticket.loginTicket(), mobile(), true)));
        assertEquals(401, error(() -> manual.createDevelopmentAccount("RIDER", "A".repeat(43), mobile(), true)));
        assertEquals(400, error(() -> manual.createDevelopmentAccount("RIDER", ticket.loginTicket(), "+8613800138000", true)));
        for (String blocked : new String[]{"prod", "production", "docker"}) {
            environment.setActiveProfiles("local", blocked);
            assertEquals(403, error(() -> manual.createDevelopmentAccount("RIDER", ticket.loginTicket(), mobile(), true)));
        }
        environment.setActiveProfiles("development");
        when(settings.effectiveLogin("RIDER")).thenReturn(new WechatConfigService.LoginCredentials(riderConfig.appId(), "rotated"));
        assertEquals(409, error(() -> manual.createDevelopmentAccount("RIDER", ticket.loginTicket(), mobile(), true)));
        when(settings.effectiveLogin("RIDER")).thenReturn(riderConfig);
        tx(() -> { var row = tickets.findForUpdate(MiniappIdentityLinkService.digest(ticket.loginTicket())).orElseThrow();
            row.setExpiresAt(Instant.now().minusSeconds(1)); tickets.saveAndFlush(row); });
        assertEquals(401, error(() -> manual.createDevelopmentAccount("RIDER", ticket.loginTicket(), mobile(), true)));
        assertFalse(stored(ticket).isConsumed()); assertEquals(before, users.count()); verifyNoInteractions(phones);
    }

    @Test void manualConflictNeverTakesOverAndAuditFailureRollsBackWholeAccount() {
        long existing = account("USER", "USER", unique()); String occupied = users.findById(existing).orElseThrow().getMobile();
        var manual = development(new MockEnvironment(), "local"); var ticket = login("RIDER", unique());
        long beforeUsers = users.count(), beforeIdentities = identities.count();
        assertEquals(409, error(() -> manual.createDevelopmentAccount("RIDER", ticket.loginTicket(), occupied, true)));
        assertFalse(stored(ticket).isConsumed()); assertEquals(beforeUsers, users.count()); assertEquals(beforeIdentities, identities.count());
        String fresh = mobile();
        doThrow(new IllegalStateException("test audit unavailable")).when(audit).record(eq("MINIAPP_ACCOUNT_CREATE"), anyString());
        assertThrows(IllegalStateException.class, () -> manual.createDevelopmentAccount("RIDER", ticket.loginTicket(), fresh, true));
        assertTrue(users.findByMobile(fresh).isEmpty()); assertFalse(stored(ticket).isConsumed());
        assertEquals(beforeUsers, users.count()); assertEquals(beforeIdentities, identities.count()); verifyNoInteractions(phones);
    }

    @Test void concurrentManualPhoneAndTicketClaimsHaveOnlyOneAtomicWinner() throws Exception {
        var manual = development(new MockEnvironment(), "local");
        for (boolean sameTicket : new boolean[]{false, true}) {
            var first = login("RIDER", unique()); var second = sameTicket ? first : login("RIDER", unique());
            String mobile = mobile(); long before = users.count(), beforeIdentities = identities.count();
            var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(() -> { start.await(); return error(() -> manual.createDevelopmentAccount("RIDER", first.loginTicket(), mobile, true)); });
                var b = pool.submit(() -> { start.await(); return error(() -> manual.createDevelopmentAccount("RIDER", second.loginTicket(), mobile, true)); });
                start.countDown(); int ra = a.get(10, TimeUnit.SECONDS), rb = b.get(10, TimeUnit.SECONDS);
                int loser = sameTicket ? 401 : 409;
                assertTrue(ra == 0 && rb == loser || rb == 0 && ra == loser, ra + "/" + rb);
                assertEquals(before + 1, users.count()); assertEquals(beforeIdentities + 1, identities.count());
                if (!sameTicket) assertNotEquals(stored(first).isConsumed(), stored(second).isConsumed());
                else assertTrue(stored(first).isConsumed());
            } finally { pool.shutdownNow(); }
        }
        verifyNoInteractions(phones);
    }

    @Test void manualGateIsRecheckedAfterTicketLockBeforeAnyAccountMutation() {
        var environment = new MockEnvironment(); var ticket = login("RIDER", unique());
        var controlledTickets = mock(MiniappLoginTicketRepository.class, org.mockito.AdditionalAnswers.delegatesTo(tickets));
        doAnswer(call -> { environment.setActiveProfiles("local", "docker"); return tickets.findForUpdate(call.getArgument(0)); })
                .when(controlledTickets).findForUpdate(anyString());
        environment.setActiveProfiles("local");
        var manual = new MiniappIdentityLinkService(settings, sessions, phones, identities, users, riders, controlledTickets,
                links, tokens, audit, manager, entityManager, new DevelopmentPhonePolicy(environment, true));
        long before = users.count();
        assertEquals(403, error(() -> manual.createDevelopmentAccount("RIDER", ticket.loginTicket(), mobile(), true)));
        assertEquals(before, users.count()); assertFalse(stored(ticket).isConsumed()); verifyNoInteractions(phones);
    }

    private MiniappIdentityLinkService development(MockEnvironment environment, String... profiles) {
        environment.setActiveProfiles(profiles);
        return new MiniappIdentityLinkService(settings, sessions, phones, identities, users, riders, tickets, links,
                tokens, audit, manager, entityManager, new DevelopmentPhonePolicy(environment, true));
    }

    private MiniappIdentityLinkService.LoginState login(String client, String openid) {
        String code = unique();
        when(sessions.exchange(code, config(client))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "WeChat exchange must not hold a write transaction");
            return new WechatSessionClient.Session(config(client).appId(), openid, "shared-unionid-never-merge");
        });
        return service.login(client, code);
    }
    private MiniappIdentityLinkService.LinkCode issue(long id, String source, String openid) {
        String code = unique(); String role = users.findById(id).orElseThrow().getRole();
        UserContext.set(new UserContext.CurrentUser(id, role, source, config(source).appId()));
        when(sessions.exchange(code, config(source))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new WechatSessionClient.Session(config(source).appId(), openid, null);
        });
        try { return service.issueLinkCode(code, true); } finally { UserContext.clear(); }
    }
    private long account(String role, String client, String openid) {
        return new TransactionTemplate(manager).execute(status -> {
            User user = new User(); user.setRole(role); user.setMobile(mobile()); user.setNickname("原账户"); users.saveAndFlush(user);
            WechatIdentity identity = new WechatIdentity(); identity.setAppId(config(client).appId()); identity.setOpenid(openid);
            identity.setUnionid("shared-unionid-never-merge"); identity.setUserId(user.getId()); identities.saveAndFlush(identity);
            if ("RIDER".equals(role)) {
                Rider rider = new Rider(); rider.setUserId(user.getId()); rider.setName("原骑手"); rider.setCreditScore(73);
                rider.setRideEnabled(true); rider.setDrivingEnabled(false); riders.saveAndFlush(rider);
            }
            return user.getId();
        });
    }
    private long history(long id, long riderId) {
        return new TransactionTemplate(manager).execute(status -> {
            WalletAccount wallet = new WalletAccount(); wallet.setUserId(id); wallet.setAvailableFen(5432); wallet.setTotalIncomeFen(5432);
            wallet.setCreatedTime(LocalDateTime.now()); wallet.setUpdatedTime(LocalDateTime.now()); wallets.saveAndFlush(wallet);
            OrderMain order = new OrderMain(); order.setOrderNo("IDENTITY-" + unique()); order.setOrderType("SEND"); order.setUserId(id); order.setRiderId(riderId);
            order.setFromContact("历史寄件人"); order.setFromPhone("13800000001"); order.setFromAddress("历史起点");
            order.setToContact("历史收件人"); order.setToPhone("13800000002"); order.setToAddress("历史终点");
            order.setRealFee(1200); order.setEstimateFee(1200); order.setStatus(com.feilv.enums.OrderStatus.FINISHED);
            return orders.saveAndFlush(order).getId();
        });
    }
    private MiniappLoginTicket stored(MiniappIdentityLinkService.LoginState state) { return tickets.findById(MiniappIdentityLinkService.digest(state.loginTicket())).orElseThrow(); }
    private long auditCount(String action, long userId) { return logs.findAll().stream().filter(log -> action.equals(log.getAction()) && log.getDetail().startsWith("userId=" + userId + ",")).count(); }
    private WechatConfigService.LoginCredentials config(String client) { return "USER".equals(client) ? userConfig : riderConfig; }
    private static String opposite(String client) { return "USER".equals(client) ? "RIDER" : "USER"; }
    private void tx(Runnable action) { new TransactionTemplate(manager).executeWithoutResult(status -> action.run()); }
    private static int error(Runnable action) { try { action.run(); return 0; } catch (BizException error) { return error.getCode(); } }
    private static String unique() { return UUID.randomUUID().toString(); }
    private static String mobile() { return String.format(java.util.Locale.ROOT, "138%08d", SEQUENCE.incrementAndGet()); }
    private static void rendezvous(CountDownLatch both) throws InterruptedException { both.countDown(); if (!both.await(5, TimeUnit.SECONDS)) throw new AssertionError("external call rendezvous timed out"); }
}
