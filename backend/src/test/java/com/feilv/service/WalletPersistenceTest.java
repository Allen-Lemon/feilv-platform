package com.feilv.service;

import com.feilv.common.*;
import com.feilv.domain.*;
import com.feilv.dto.WalletDtos.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real H2 rows/locks/independent transactions; no network or real money. */
@DataJpaTest(showSql = false, properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import({WalletAccountingService.class, WalletBalanceService.class, WalletService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletPersistenceTest {
    @Autowired WalletAccountingService accounting;
    @Autowired WalletService service;
    @Autowired UserRepository users;
    @Autowired RiderRepository riders;
    @Autowired OrderRepository orders;
    @Autowired PayRecordRepository payments;
    @Autowired ComplaintDeductionRepository deductions;
    @Autowired WalletAccountRepository accounts;
    @Autowired WalletOrderSettlementRepository settlements;
    @Autowired WalletComplaintReceiptRepository receipts;
    @Autowired WalletWithdrawalRepository withdrawals;
    @SpyBean WalletEntryRepository entries;
    @MockBean AdminMenuAccess access;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    private static final AtomicLong COMPLAINT_IDS = new AtomicLong(10000);

    @AfterEach void clear() { UserContext.clear(); reset(entries); }

    @Test void completedOrderCreditsOnlySuccessfulMockOriginalShareAndIsIdempotent() {
        Fixture f = fixture(800); payment(f.order, "MOCK", "FAILED", 3000); payment(f.order, "WECHAT", "SUCCESS", 5000);
        accounting.settleFinishedOrder(f.order.getId()); accounting.settleFinishedOrder(f.order.getId());
        WalletAccount a = account(f.riderUser); assertEquals(800, a.getAvailableFen()); assertEquals(800, a.getTotalIncomeFen());
        WalletOrderSettlement s = settlements.findByOrderId(f.order.getId()).orElseThrow();
        assertEquals(800, s.getGrossFen()); assertEquals(0, s.getDeductedFen()); assertEquals(800, s.getNetFen());
        assertEquals(f.riderUser.getId(), s.getUserId()); assertEquals(f.rider.getId(), s.getRiderId());
        assertEquals(1, ledger(f.riderUser).size());
        PayRecord original = payments.findByOrderId(f.order.getId()).stream().filter(p -> p.getStatus().equals("SUCCESS") && p.getChannel().equals("MOCK")).findFirst().orElseThrow();
        assertEquals(800, original.getShareRider()); assertEquals(1000, original.getAmount()); assertEquals(200, original.getSharePlatform());
    }

    @Test void skipNonFinishedMissingRiderMissingPaymentAndNonMockWithoutCreatingAccountOrReceipt() {
        Fixture waiting = fixture(800); waiting.order.setStatus(OrderStatus.DELIVERED); orders.saveAndFlush(waiting.order);
        Fixture noRider = fixture(800); noRider.order.setRiderId(null); orders.saveAndFlush(noRider.order);
        Fixture noPay = fixture(-1); payment(noPay.order, "WECHAT", "SUCCESS", 800); payment(noPay.order, "MOCK", "FAILED", 800);
        for (Fixture f : List.of(waiting, noRider, noPay)) {
            accounting.settleFinishedOrder(f.order.getId()); assertTrue(settlements.findByOrderId(f.order.getId()).isEmpty());
            assertFalse(accounts.existsById(f.riderUser.getId()));
        }
    }

    @Test void preSettlementComplaintCompensatesOnceAndSettlementNetsAllExistingDeductions() {
        Fixture f = fixture(800); f.order.setStatus(OrderStatus.DELIVERED); orders.saveAndFlush(f.order);
        ComplaintDeduction d = applyNew(f, 300); apply(d);
        assertEquals(300, account(f.publisher).getAvailableFen()); assertEquals(0, account(f.riderUser).getAvailableFen());
        assertFalse(receipts.findByComplaintId(d.getComplaintId()).orElseThrow().isRiderDebited());
        f.order.setStatus(OrderStatus.FINISHED); orders.saveAndFlush(f.order); accounting.settleFinishedOrder(f.order.getId());
        assertEquals(500, account(f.riderUser).getAvailableFen()); assertEquals(500, account(f.riderUser).getTotalIncomeFen());
        assertEquals(300, settlements.findByOrderId(f.order.getId()).orElseThrow().getDeductedFen());
        assertEquals(1, ledger(f.publisher).size()); assertEquals(1, ledger(f.riderUser).size());
    }

    @Test void legacyDeductionOnlyNetsRiderAndDoesNotBackfillPublisher() {
        Fixture f = fixture(800); ComplaintDeduction legacy = saveDeduction(f, 250);
        accounting.settleFinishedOrder(f.order.getId());
        assertEquals(550, account(f.riderUser).getAvailableFen()); assertFalse(accounts.existsById(f.publisher.getId()));
        assertTrue(receipts.findByComplaintId(legacy.getComplaintId()).isEmpty());
    }

    @Test void fullExistingDeductionStillCreatesZeroNetSettlementAndImmutableZeroLedger() {
        Fixture f = fixture(800); saveDeduction(f, 800); accounting.settleFinishedOrder(f.order.getId());
        assertEquals(0, account(f.riderUser).getAvailableFen()); assertEquals(0, ledger(f.riderUser).get(0).getAmountFen());
        assertEquals(0, settlements.findByOrderId(f.order.getId()).orElseThrow().getNetFen());
    }

    @Test void excessDeductionFailsSettlementWithoutPartialWalletOrSettlement() {
        Fixture f = fixture(800); saveDeduction(f, 801);
        assertEquals(409, assertThrows(BizException.class, () -> accounting.settleFinishedOrder(f.order.getId())).getCode());
        assertFalse(accounts.existsById(f.riderUser.getId())); assertTrue(settlements.findByOrderId(f.order.getId()).isEmpty());
    }

    @Test void postSettlementDeductionPreservesFreezeCreatesDebtAndFutureIncomeRepaysIt() {
        Fixture f = fixture(800); accounting.settleFinishedOrder(f.order.getId());
        as(f.riderUser); WithdrawalVO pending = service.requestWithdrawal(new WithdrawalReq(700L, "debt-test-key"));
        applyNew(f, 600);
        WalletAccount a = account(f.riderUser); assertEquals(0, a.getAvailableFen()); assertEquals(700, a.getFrozenFen()); assertEquals(500, a.getDebtFen());
        as(f.riderUser); assertEquals(409, assertThrows(BizException.class, () -> service.requestWithdrawal(new WithdrawalReq(1L, "blocked-new-key"))).getCode());
        assertEquals(pending.id(), service.requestWithdrawal(new WithdrawalReq(700L, "debt-test-key")).id());
        OrderMain second = order(f.publisher, f.rider, OrderStatus.FINISHED); payment(second, "MOCK", "SUCCESS", 600);
        accounting.settleFinishedOrder(second.getId()); a = account(f.riderUser);
        assertEquals(0, a.getDebtFen()); assertEquals(100, a.getAvailableFen()); assertEquals(700, a.getFrozenFen()); assertEquals(1400, a.getTotalIncomeFen());
    }

    @Test void rejectionUnfreezesToDebtFirstWithoutIncreasingIncomeAndRepeatReviewConflicts() {
        Fixture f = fixture(800); accounting.settleFinishedOrder(f.order.getId()); as(f.riderUser);
        WithdrawalVO pending = service.requestWithdrawal(new WithdrawalReq(700L, "reject-debt-key")); applyNew(f, 600);
        UserContext.set(new UserContext.CurrentUser(9000, "ADMIN"));
        WithdrawalVO rejected = service.reviewWithdrawal(pending.id(), new WithdrawalReviewReq("REJECTED", "MOCK驳回13800138000"));
        assertEquals("REJECTED", rejected.status()); assertEquals(9000L, rejected.reviewedBy()); assertNotNull(rejected.reviewedTime());
        assertFalse(rejected.reason().contains("13800138000"));
        WalletAccount a = account(f.riderUser); assertEquals(200, a.getAvailableFen()); assertEquals(0, a.getDebtFen()); assertEquals(0, a.getFrozenFen());
        assertEquals(800, a.getTotalIncomeFen()); assertEquals(0, a.getTotalWithdrawnFen());
        assertEquals(409, assertThrows(BizException.class, () -> service.reviewWithdrawal(pending.id(), new WithdrawalReviewReq("REJECTED", "重复"))).getCode());
        assertEquals(409, assertThrows(BizException.class, () -> service.reviewWithdrawal(pending.id(), new WithdrawalReviewReq("APPROVED", "改判"))).getCode());
        WalletEntry unfreeze = ledger(f.riderUser).get(0); assertEquals(700, unfreeze.getAmountFen()); assertEquals(200, unfreeze.getAvailableAfterFen()); assertEquals(0, unfreeze.getDebtAfterFen());
    }

    @Test void approvalSpendsExistingFreezeEvenWithDebtAndFrozenUserButDoesNotTouchDebt() {
        Fixture f = fixture(800); accounting.settleFinishedOrder(f.order.getId()); as(f.riderUser);
        WithdrawalVO pending = service.requestWithdrawal(new WithdrawalReq(700L, "approve-debt-key")); applyNew(f, 600);
        f.riderUser.setStatus(0); users.saveAndFlush(f.riderUser);
        UserContext.set(new UserContext.CurrentUser(9000, "ADMIN")); service.reviewWithdrawal(pending.id(), new WithdrawalReviewReq("APPROVED", "仅MOCK"));
        WalletAccount a = account(f.riderUser); assertEquals(700, a.getTotalWithdrawnFen()); assertEquals(0, a.getFrozenFen()); assertEquals(500, a.getDebtFen());
        assertEquals(0, a.getAvailableFen()); assertEquals(800, a.getTotalIncomeFen());
    }

    @Test void frozenUserCanReceiveSettlementCompensationAndDebtButCannotInitiateWalletRequests() {
        Fixture f = fixture(800); f.riderUser.setStatus(0); users.saveAndFlush(f.riderUser);
        f.publisher.setStatus(0); users.saveAndFlush(f.publisher);
        accounting.settleFinishedOrder(f.order.getId()); applyNew(f, 300);
        assertEquals(500, account(f.riderUser).getAvailableFen()); assertEquals(300, account(f.publisher).getAvailableFen());
        as(f.riderUser); assertEquals(403, assertThrows(BizException.class, service::mine).getCode());
        assertEquals(403, assertThrows(BizException.class, () -> service.requestWithdrawal(new WithdrawalReq(1L, "frozen-user-key"))).getCode());
    }

    @Test void strictServiceBoundsNoPhoneInsufficientAndKeyMismatchCannotFreeze() {
        Fixture f = fixture(800); accounting.settleFinishedOrder(f.order.getId()); as(f.riderUser);
        for (long amount : List.of(-1L, 0L, 100000001L, Long.MAX_VALUE)) assertEquals(400, assertThrows(BizException.class,
                () -> service.requestWithdrawal(new WithdrawalReq(amount, "boundary-key"))).getCode());
        assertEquals(409, assertThrows(BizException.class, () -> service.requestWithdrawal(new WithdrawalReq(801L, "too-much-key"))).getCode());
        f.riderUser.setMobile(null); users.saveAndFlush(f.riderUser);
        assertEquals(403, assertThrows(BizException.class, () -> service.requestWithdrawal(new WithdrawalReq(1L, "unbound-key"))).getCode());
        f.riderUser.setMobile("13800138001"); users.saveAndFlush(f.riderUser);
        WithdrawalVO w = service.requestWithdrawal(new WithdrawalReq(1L, "same-request-key"));
        assertEquals(w.id(), service.requestWithdrawal(new WithdrawalReq(1L, "same-request-key")).id());
        assertEquals(409, assertThrows(BizException.class, () -> service.requestWithdrawal(new WithdrawalReq(2L, "same-request-key"))).getCode());
        assertEquals(799, account(f.riderUser).getAvailableFen()); assertEquals(1, account(f.riderUser).getFrozenFen());
    }

    @Test void maximumWithdrawalAndPositiveOneFenBothWork() {
        User user = user("RIDER"); seed(user, 100000001, 0, 0, 100000001); as(user);
        service.requestWithdrawal(new WithdrawalReq(100000000L, "max-value-key")); service.requestWithdrawal(new WithdrawalReq(1L, "min-value-key"));
        assertEquals(0, account(user).getAvailableFen()); assertEquals(100000001, account(user).getFrozenFen());
    }

    @Test void applyingDeductionRequiresSavedEntryAndExistingTransaction() {
        Fixture f = fixture(800); ComplaintDeduction d = saveDeduction(f, 100);
        assertThrows(IllegalTransactionStateException.class, () -> accounting.applyComplaintDeduction(d));
        ComplaintDeduction unsaved = deduction(f, 100);
        assertEquals(409, assertThrows(BizException.class, () -> tx(() -> accounting.applyComplaintDeduction(unsaved))).getCode());
        assertFalse(accounts.existsById(f.publisher.getId()));
    }

    @Test void legacyFinishedOrderWithoutWalletSettlementCannotCreateUnfundedCompensation() {
        Fixture f = fixture(800); long before = deductions.count();
        BizException error = assertThrows(BizException.class, () -> applyNew(f, 100));
        assertEquals(409, error.getCode()); assertTrue(error.getMessage().contains("历史已完成订单"));
        assertEquals(before, deductions.count()); assertFalse(accounts.existsById(f.publisher.getId()));
        assertFalse(accounts.existsById(f.riderUser.getId())); assertTrue(settlements.findByOrderId(f.order.getId()).isEmpty());
    }

    @Test void complaintSaveAndCompensationRollBackTogetherOnOverflow() {
        Fixture f = fixture(800); f.order.setStatus(OrderStatus.DELIVERED); orders.saveAndFlush(f.order);
        seed(f.publisher, Long.MAX_VALUE, 0, 0, Long.MAX_VALUE);
        long before = deductions.count(); long beforeReceipts = receipts.count();
        assertEquals(409, assertThrows(BizException.class, () -> applyNew(f, 1)).getCode());
        assertEquals(before, deductions.count()); assertEquals(beforeReceipts, receipts.count());
        assertEquals(Long.MAX_VALUE, account(f.publisher).getAvailableFen()); assertFalse(accounts.existsById(f.riderUser.getId()));
    }

    @Test void settlementOverflowAndInjectedLedgerFailureRollBackAllWrites() {
        Fixture f = fixture(800); seed(f.riderUser, Long.MAX_VALUE, 0, 0, 0);
        assertEquals(409, assertThrows(BizException.class, () -> accounting.settleFinishedOrder(f.order.getId())).getCode());
        assertTrue(settlements.findByOrderId(f.order.getId()).isEmpty()); assertEquals(Long.MAX_VALUE, account(f.riderUser).getAvailableFen());
        Fixture fresh = fixture(800); doThrow(new IllegalStateException("ledger unavailable")).when(entries).saveAndFlush(any(WalletEntry.class));
        assertThrows(IllegalStateException.class, () -> accounting.settleFinishedOrder(fresh.order.getId()));
        assertFalse(accounts.existsById(fresh.riderUser.getId())); assertTrue(settlements.findByOrderId(fresh.order.getId()).isEmpty());
    }

    @Test void ledgerFailureRollsBackWithdrawalFreezeAndReview() {
        Fixture f = fixture(800); accounting.settleFinishedOrder(f.order.getId()); as(f.riderUser);
        doThrow(new IllegalStateException("ledger unavailable")).when(entries).saveAndFlush(any(WalletEntry.class));
        assertThrows(IllegalStateException.class, () -> service.requestWithdrawal(new WithdrawalReq(500L, "freeze-rollback-key")));
        assertEquals(800, account(f.riderUser).getAvailableFen()); assertEquals(0, account(f.riderUser).getFrozenFen());
        reset(entries); WithdrawalVO w = service.requestWithdrawal(new WithdrawalReq(500L, "freeze-rollback-key"));
        doThrow(new IllegalStateException("ledger unavailable")).when(entries).saveAndFlush(any(WalletEntry.class));
        UserContext.set(new UserContext.CurrentUser(9000, "ADMIN"));
        assertThrows(IllegalStateException.class, () -> service.reviewWithdrawal(w.id(), new WithdrawalReviewReq("APPROVED", "审核")));
        assertEquals("PENDING", withdrawals.findById(w.id()).orElseThrow().getStatus());
        assertEquals(500, account(f.riderUser).getFrozenFen()); assertEquals(0, account(f.riderUser).getTotalWithdrawnFen());
    }

    @Test void ledgerAndSettlementAmountsAreImmutableAndDatabaseBusinessKeysUnique() {
        Fixture f = fixture(800); accounting.settleFinishedOrder(f.order.getId());
        long id = ledger(f.riderUser).get(0).getId();
        tx(() -> { WalletEntry e = em.find(WalletEntry.class, id); e.setAmountFen(1); e.setAvailableAfterFen(1); em.flush(); });
        assertEquals(800, entries.findById(id).orElseThrow().getAmountFen());
        tx(() -> { WalletOrderSettlement s = settlements.findByOrderId(f.order.getId()).orElseThrow(); s.setNetFen(1); em.flush(); });
        assertEquals(800, settlements.findByOrderId(f.order.getId()).orElseThrow().getNetFen());
        WalletEntry duplicate = new WalletEntry(); duplicate.setUserId(f.riderUser.getId()); duplicate.setBusinessKey("ORDER_SETTLE:" + f.order.getId());
        duplicate.setType("ORDER_SETTLEMENT"); duplicate.setRemark("MOCK"); duplicate.setCreatedTime(LocalDateTime.now());
        assertThrows(DataIntegrityViolationException.class, () -> entries.saveAndFlush(duplicate));
    }

    @Test void concurrentSettlementCreatesOneAccountOneSettlementAndOneCredit() throws Exception {
        Fixture f = fixture(800);
        assertEquals(List.of(0, 0), race(() -> code(() -> accounting.settleFinishedOrder(f.order.getId())), () -> code(() -> accounting.settleFinishedOrder(f.order.getId()))));
        assertEquals(800, account(f.riderUser).getAvailableFen()); assertEquals(1, ledger(f.riderUser).size());
    }

    @Test void concurrentDuplicateComplaintCompensatesOnlyOnce() throws Exception {
        Fixture f = fixture(800); accounting.settleFinishedOrder(f.order.getId()); ComplaintDeduction d = saveDeduction(f, 300);
        assertEquals(List.of(0, 0), race(() -> code(() -> apply(d)), () -> code(() -> apply(d))));
        assertEquals(300, account(f.publisher).getAvailableFen()); assertEquals(500, account(f.riderUser).getAvailableFen());
        assertEquals(1, ledger(f.publisher).size()); assertEquals(2, ledger(f.riderUser).size());
    }

    @Test void concurrentRequestsCannotOverdrawAndDuplicateRequestDoesNotFreezeTwice() throws Exception {
        Fixture f = fixture(800); accounting.settleFinishedOrder(f.order.getId());
        var codes = race(() -> withdrawCode(f.riderUser, 600, "parallel-key-a"), () -> withdrawCode(f.riderUser, 600, "parallel-key-b"));
        assertEquals(1, codes.stream().filter(c -> c == 0).count()); assertEquals(1, codes.stream().filter(c -> c == 409).count());
        assertEquals(200, account(f.riderUser).getAvailableFen()); assertEquals(600, account(f.riderUser).getFrozenFen());
        Fixture g = fixture(800); accounting.settleFinishedOrder(g.order.getId());
        assertEquals(List.of(0, 0), race(() -> withdrawCode(g.riderUser, 500, "duplicate-key"), () -> withdrawCode(g.riderUser, 500, "duplicate-key")));
        assertEquals(300, account(g.riderUser).getAvailableFen()); assertEquals(500, account(g.riderUser).getFrozenFen());
    }

    @Test void concurrentApproveRejectChangesFreezeExactlyOnce() throws Exception {
        Fixture f = fixture(800); accounting.settleFinishedOrder(f.order.getId()); as(f.riderUser);
        long id = service.requestWithdrawal(new WithdrawalReq(600L, "review-race-key")).id();
        var codes = race(() -> reviewCode(id, "APPROVED"), () -> reviewCode(id, "REJECTED"));
        assertEquals(1, codes.stream().filter(c -> c == 0).count()); assertEquals(1, codes.stream().filter(c -> c == 409).count());
        WalletAccount a = account(f.riderUser); assertEquals(0, a.getFrozenFen());
        assertEquals(800, a.getAvailableFen() + a.getTotalWithdrawnFen());
    }

    @Test void reciprocalRiderPublisherComplaintsUseSortedUsersAndDoNotDeadlock() throws Exception {
        Fixture f = fixture(800); Rider other = new Rider(); other.setUserId(f.publisher.getId()); other.setName("另一骑手"); other = riders.saveAndFlush(other);
        OrderMain reverse = order(f.riderUser, other, OrderStatus.FINISHED); payment(reverse, "MOCK", "SUCCESS", 800);
        accounting.settleFinishedOrder(f.order.getId()); accounting.settleFinishedOrder(reverse.getId());
        ComplaintDeduction a = saveDeduction(f, 300); Fixture reversed = new Fixture(f.riderUser, f.publisher, other, reverse); ComplaintDeduction b = saveDeduction(reversed, 200);
        assertEquals(List.of(0, 0), race(() -> code(() -> apply(a)), () -> code(() -> apply(b))));
        assertEquals(700, account(f.riderUser).getAvailableFen()); assertEquals(900, account(f.publisher).getAvailableFen());
    }

    @Test void waitingCallerRefreshesPreloadedManagedWalletAfterUserLock() throws Exception {
        Fixture f = fixture(800); accounting.settleFinishedOrder(f.order.getId());
        var read = new CountDownLatch(1); var commit = new CountDownLatch(1); ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> stale = pool.submit(() -> {
                var template = new TransactionTemplate(transactions); template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                return code(() -> template.executeWithoutResult(status -> {
                    accounts.findById(f.riderUser.getId()).orElseThrow(); users.findById(f.riderUser.getId()).orElseThrow(); read.countDown(); await(commit);
                    as(f.riderUser); try { service.requestWithdrawal(new WithdrawalReq(600L, "stale-after-lock")); } finally { UserContext.clear(); }
                }));
            });
            assertTrue(read.await(10, TimeUnit.SECONDS)); as(f.riderUser); service.requestWithdrawal(new WithdrawalReq(600L, "first-committed")); commit.countDown();
            assertEquals(409, stale.get(10, TimeUnit.SECONDS)); assertEquals(200, account(f.riderUser).getAvailableFen()); assertEquals(600, account(f.riderUser).getFrozenFen());
        } finally { commit.countDown(); pool.shutdownNow(); }
    }

    private int withdrawCode(User user, long amount, String key) { as(user); try { return code(() -> service.requestWithdrawal(new WithdrawalReq(amount, key))); } finally { UserContext.clear(); } }
    private int reviewCode(long id, String decision) { UserContext.set(new UserContext.CurrentUser(9000, "ADMIN")); try { return code(() -> service.reviewWithdrawal(id, new WithdrawalReviewReq(decision, "MOCK审核"))); } finally { UserContext.clear(); } }
    private static int code(Runnable run) { try { run.run(); return 0; } catch (BizException e) { return e.getCode(); } }
    private void tx(Runnable run) { new TransactionTemplate(transactions).executeWithoutResult(status -> run.run()); }
    private void apply(ComplaintDeduction d) { tx(() -> accounting.applyComplaintDeduction(d)); }
    private ComplaintDeduction applyNew(Fixture f, int amount) {
        return new TransactionTemplate(transactions).execute(status -> { ComplaintDeduction d = deductions.saveAndFlush(deduction(f, amount)); accounting.applyComplaintDeduction(d); return d; });
    }
    private ComplaintDeduction saveDeduction(Fixture f, int amount) { return deductions.saveAndFlush(deduction(f, amount)); }
    private ComplaintDeduction deduction(Fixture f, int amount) {
        var d = new ComplaintDeduction(); d.setComplaintId(COMPLAINT_IDS.incrementAndGet()); d.setOrderId(f.order.getId()); d.setRiderId(f.rider.getId());
        d.setUserId(f.publisher.getId()); d.setAmountFen(amount); d.setCreatedBy(9000L); d.setCreatedTime(LocalDateTime.now()); return d;
    }
    private List<WalletEntry> ledger(User user) { return entries.findByUserId(user.getId(), org.springframework.data.domain.PageRequest.of(0, 100, org.springframework.data.domain.Sort.by("id").descending())).getContent(); }
    private WalletAccount account(User user) { return accounts.findById(user.getId()).orElseThrow(); }
    private void seed(User user, long available, long frozen, long debt, long income) {
        WalletAccount a = new WalletAccount(); a.setUserId(user.getId()); a.setAvailableFen(available); a.setFrozenFen(frozen); a.setDebtFen(debt); a.setTotalIncomeFen(income);
        a.setCreatedTime(LocalDateTime.now()); a.setUpdatedTime(a.getCreatedTime()); accounts.saveAndFlush(a);
    }
    private void as(User user) { UserContext.set(new UserContext.CurrentUser(user.getId(), user.getRole())); }
    private User user(String role) { User u = new User(); u.setRole(role); u.setNickname("钱包测试"); u.setMobile("1" + String.format("%010d", COMPLAINT_IDS.incrementAndGet())); return users.saveAndFlush(u); }
    private Fixture fixture(int share) {
        User publisher = user("USER"), riderUser = user("RIDER"); Rider r = new Rider(); r.setUserId(riderUser.getId()); r.setName("钱包骑手"); r = riders.saveAndFlush(r);
        OrderMain o = order(publisher, r, OrderStatus.FINISHED); if (share >= 0) payment(o, "MOCK", "SUCCESS", share); return new Fixture(publisher, riderUser, r, o);
    }
    private OrderMain order(User publisher, Rider rider, OrderStatus status) {
        OrderMain o = new OrderMain(); o.setOrderNo("W-" + UUID.randomUUID()); o.setOrderType("SEND"); o.setUserId(publisher.getId()); o.setRiderId(rider.getId()); o.setStatus(status);
        o.setFromContact("寄件"); o.setFromPhone("13800138000"); o.setFromAddress("起点"); o.setToContact("收件"); o.setToPhone("13900139000"); o.setToAddress("终点"); return orders.saveAndFlush(o);
    }
    private void payment(OrderMain order, String channel, String status, int share) {
        PayRecord p = new PayRecord(); p.setPayNo("W-P-" + UUID.randomUUID()); p.setOrderId(order.getId()); p.setChannel(channel); p.setStatus(status); p.setAmount(share + 200); p.setShareRider(share); p.setSharePlatform(200); payments.saveAndFlush(p);
    }
    private static void await(CountDownLatch latch) { try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
    private List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var barrier = new CyclicBarrier(2); ExecutorService pool = Executors.newFixedThreadPool(2);
        try { Future<Integer> a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return first.call(); }); Future<Integer> b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return second.call(); }); return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)); }
        finally { pool.shutdownNow(); }
    }
    private record Fixture(User publisher, User riderUser, Rider rider, OrderMain order) {}
}
