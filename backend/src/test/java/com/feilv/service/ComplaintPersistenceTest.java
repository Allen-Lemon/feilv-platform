package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.dto.ApiDtos.ComplaintReq;
import com.feilv.dto.ComplaintDtos.ComplaintReviewReq;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 独立真实事务/H2行锁；并发不同投诉和同一投诉，及故障注入验证全部回滚。 */
@DataJpaTest(showSql = false, properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import({ComplaintService.class, AuditService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ComplaintPersistenceTest {
    @Autowired ComplaintService service;
    @Autowired ComplaintRepository complaints;
    @Autowired ComplaintDeductionRepository deductions;
    @Autowired ComplaintActionRepository actions;
    @Autowired OrderRepository orders;
    @Autowired PayRecordRepository payments;
    @Autowired RiderRepository riders;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean AuditService audit;

    @AfterEach void clearContext() { UserContext.clear(); }

    @Test
    void orderLockPreventsConcurrentDifferentComplaintsOverdrawingSameOrder() throws Exception {
        OrderMain order = order();
        // 模拟迁移前已存在的多张活动工单；新建入口本身不会允许这种状态。
        long first = complaint(order), second = complaint(order);
        List<Integer> codes = race(() -> approve(first, 600), () -> approve(second, 600));
        assertEquals(1, codes.stream().filter(c -> c == 0).count());
        assertEquals(1, codes.stream().filter(c -> c == 4090).count());
        List<ComplaintDeduction> ledger = deductions.findByOrderIdIn(List.of(order.getId()));
        assertEquals(1, ledger.size()); assertEquals(600, ledger.get(0).getAmountFen());
        assertEquals("MOCK", ledger.get(0).getChannel()); assertEquals(order.getUserId(), ledger.get(0).getUserId());
        assertEquals(99L, ledger.get(0).getCreatedBy()); assertNotNull(ledger.get(0).getCreatedTime());
        assertEquals(800, payments.findByOrderId(order.getId()).get(0).getShareRider());
    }

    @Test
    void concurrentDuplicateReviewCannotDoubleBook() throws Exception {
        OrderMain order = order(); long id = complaint(order);
        List<Integer> codes = race(() -> approve(id, 400), () -> approve(id, 400));
        assertEquals(1, codes.stream().filter(c -> c == 0).count()); assertEquals(1, codes.stream().filter(c -> c == 4090).count());
        assertEquals(400, deductions.findByOrderIdIn(List.of(order.getId())).stream().mapToInt(ComplaintDeduction::getAmountFen).sum());
        assertEquals(2, actions.findByComplaintIdOrderByIdDesc(id).size());
    }

    @Test
    void concurrentCreationAllowsOnlyOneActiveTicket() throws Exception {
        OrderMain order = order();
        Callable<Integer> create = () -> {
            try { service.create(order.getUserId(), new ComplaintReq(order.getId(), "OTHER", "配送问题")); return 0; }
            catch (BizException e) { return e.getCode(); }
        };
        List<Integer> codes = race(create, create);
        assertEquals(1, codes.stream().filter(c -> c == 0).count()); assertEquals(1, codes.stream().filter(c -> c == 4090).count());
        assertEquals(1, complaints.findByOrderIdOrderByIdDesc(order.getId()).size());
    }

    @Test
    void waitingReviewerSeesPreviouslyCommittedDeductionNotOldSnapshot() throws Exception {
        OrderMain order = order(); long first = complaint(order), second = complaint(order);
        var reviewed = new CountDownLatch(1); var release = new CountDownLatch(1); var secondStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holding = pool.submit(() -> {
                UserContext.set(new UserContext.CurrentUser(99, "ADMIN"));
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        service.review(first, new ComplaintReviewReq("APPROVED", "先扣600分", 600));
                        reviewed.countDown(); await(release);
                    });
                } finally { UserContext.clear(); }
            });
            assertTrue(reviewed.await(10, TimeUnit.SECONDS));
            Future<Integer> waiting = pool.submit(() -> { secondStarted.countDown(); return approve(second, 600); });
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiting.get(200, TimeUnit.MILLISECONDS));
            release.countDown(); holding.get(10, TimeUnit.SECONDS);
            assertEquals(4090, waiting.get(10, TimeUnit.SECONDS));
            assertEquals(600, deductions.findByOrderIdIn(List.of(order.getId())).stream().mapToInt(ComplaintDeduction::getAmountFen).sum());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void auditFailureRollsBackApprovalActionsAndInsertedLedgerTogether() {
        OrderMain order = order(); long id = complaint(order);
        doThrow(new IllegalStateException("模拟审计写入失败")).when(audit).record(anyString(), anyString());
        UserContext.set(new UserContext.CurrentUser(99, "ADMIN"));
        assertThrows(IllegalStateException.class, () -> service.review(id, new ComplaintReviewReq("APPROVED", "成立", 300)));
        Complaint unchanged = complaints.findById(id).orElseThrow();
        assertEquals("OPEN", unchanged.getStatus()); assertEquals(0, unchanged.getCompensationFen()); assertNull(unchanged.getHandledBy());
        assertTrue(deductions.findByOrderIdIn(List.of(order.getId())).isEmpty());
        assertTrue(actions.findByComplaintIdOrderByIdDesc(id).isEmpty());
        assertEquals(800, payments.findByOrderId(order.getId()).get(0).getShareRider());
    }

    @Test
    void complaintUniqueConstraintIsDatabaseEnforced() {
        OrderMain order = order(); long id = complaint(order);
        assertEquals(0, approve(id, 100));
        var duplicate = new ComplaintDeduction(); duplicate.setComplaintId(id); duplicate.setOrderId(order.getId());
        duplicate.setRiderId(order.getRiderId()); duplicate.setUserId(order.getUserId()); duplicate.setAmountFen(1);
        duplicate.setCreatedBy(99L); duplicate.setCreatedTime(LocalDateTime.now());
        assertThrows(DataIntegrityViolationException.class, () -> deductions.saveAndFlush(duplicate));
        assertEquals(1, deductions.findByOrderIdIn(List.of(order.getId())).size());
    }

    private List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var barrier = new CyclicBarrier(2); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return first.call(); });
            Future<Integer> b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private int approve(long id, int amount) {
        UserContext.set(new UserContext.CurrentUser(99, "ADMIN"));
        try { service.review(id, new ComplaintReviewReq("APPROVED", "审核成立", amount)); return 0; }
        catch (BizException e) { return e.getCode(); }
        finally { UserContext.clear(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("等待提交超时"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private long complaint(OrderMain order) {
        var complaint = new Complaint(); complaint.setOrderId(order.getId()); complaint.setUserId(order.getUserId());
        complaint.setType("OTHER"); complaint.setDescription("并发审批测试"); return complaints.saveAndFlush(complaint).getId();
    }
    private OrderMain order() {
        var rider = new Rider(); rider.setUserId(Math.abs(java.util.UUID.randomUUID().getMostSignificantBits())); rider.setName("并发骑手"); rider = riders.saveAndFlush(rider);
        var order = new OrderMain(); order.setOrderNo("C-CONCURRENCY-" + java.util.UUID.randomUUID()); order.setUserId(42L);
        order.setOrderType("SEND"); order.setStatus(OrderStatus.FINISHED); order.setRiderId(rider.getId());
        order.setFromContact("寄件人"); order.setFromPhone("13800138000"); order.setFromAddress("起点");
        order.setToContact("收件人"); order.setToPhone("13900139000"); order.setToAddress("终点");
        order.setEstimateFee(1000); order.setRealFee(1000); order = orders.saveAndFlush(order);
        var pay = new PayRecord(); pay.setPayNo("C-CPAY-" + java.util.UUID.randomUUID()); pay.setOrderId(order.getId());
        pay.setChannel("MOCK"); pay.setAmount(1000); pay.setShareRider(800); pay.setSharePlatform(200); payments.saveAndFlush(pay);
        return order;
    }
}
