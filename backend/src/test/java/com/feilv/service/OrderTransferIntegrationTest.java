package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.domain.OrderTransfer.Status;
import com.feilv.dto.OrderTransferDtos.*;
import com.feilv.dto.ComplaintDtos.ComplaintReviewReq;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:order-transfer-integration;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"})
class OrderTransferIntegrationTest {
    @Autowired OrderTransferService service;
    @Autowired DispatchService dispatch;
    @Autowired OrderService orderService;
    @Autowired ComplaintService complaints;
    @Autowired OrderTransferRepository transfers;
    @Autowired OrderRepository orders;
    @Autowired RiderRepository riders;
    @Autowired UserRepository users;
    @Autowired PayRecordRepository payments;
    @Autowired CustomTaskRepository tasks;
    @Autowired OrderStatusLogRepository logs;
    @Autowired OpLogRepository audits;
    @Autowired ComplaintRepository complaintRecords;
    @Autowired ComplaintDeductionRepository deductions;
    @Autowired WalletOrderSettlementRepository settlements;
    @Autowired WalletComplaintReceiptRepository receipts;
    @Autowired WalletAccountRepository wallets;
    @Autowired WalletEntryRepository entries;
    @Autowired PlatformTransactionManager transactions;
    private static final AtomicLong sequence = new AtomicLong(4000);
    private record Fixture(User publisher, User fromUser, Rider from, User toUser, Rider to, OrderMain order) {}

    private User user(String role) {
        User user = new User(); user.setRole(role); user.setNickname("转派用户");
        user.setMobile("138" + String.format("%08d", sequence.incrementAndGet())); return users.saveAndFlush(user);
    }
    private Rider rider(User user) {
        Rider rider = new Rider(); rider.setUserId(user.getId()); rider.setName("骑手" + sequence.incrementAndGet());
        rider.setOnlineStatus(1); return riders.saveAndFlush(rider);
    }
    private Fixture fixture(boolean serviceTask) {
        User publisher = user("USER"), fromUser = user("RIDER"), toUser = user("RIDER");
        Rider from = rider(fromUser), to = rider(toUser);
        OrderMain order = new OrderMain(); order.setOrderNo("TRANSFER-" + sequence.incrementAndGet()); order.setOrderType(serviceTask ? "CUSTOM" : "SEND");
        order.setUserId(publisher.getId()); order.setRiderId(from.getId()); order.setStatus(OrderStatus.ACCEPTED); order.setPickupCode(serviceTask ? "" : "1234");
        order.setAcceptedTime(LocalDateTime.now().minusMinutes(3).withNano(0)); order.setEstimateFee(1000); order.setRealFee(1000); order.setGoodsDesc("测试物品");
        order.setFromContact("寄件人"); order.setFromPhone("13800138000"); order.setFromAddress("私有取件地址");
        order.setToContact("收件人"); order.setToPhone("13900139000"); order.setToAddress("私有配送地址"); order = orders.saveAndFlush(order);
        PayRecord pay = new PayRecord(); pay.setPayNo("TRANSFER-PAY-" + sequence.incrementAndGet()); pay.setOrderId(order.getId());
        pay.setStatus("SUCCESS"); pay.setChannel("MOCK"); pay.setAmount(1000); pay.setShareRider(700); pay.setSharePlatform(300); payments.saveAndFlush(pay);
        if (serviceTask) {
            CustomTask task = new CustomTask(); task.setOrderId(order.getId()); task.setUserId(publisher.getId()); task.setTitle("上门任务"); task.setContent("私有详细说明");
            task.setTaskMode("ONSITE"); task.setPublishSource("MINIAPP"); task.setCreatedBy(publisher.getId()); tasks.saveAndFlush(task);
        }
        return new Fixture(publisher, fromUser, from, toUser, to, order);
    }
    private TransferVO request(Fixture f) { return service.create(f.fromUser.getId(), f.order.getId(), new TransferReq(f.to.getId(), "时间冲突")); }
    private TransferVO accept(Fixture f, long id) { return service.respond(f.toUser.getId(), id, new RespondReq("ACCEPTED", "确认接手")); }
    private void mutateOrder(Fixture f, Consumer<OrderMain> change) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { var order = orders.findById(f.order.getId()).orElseThrow(); change.accept(order); orders.saveAndFlush(order); });
    }
    private void error(int code, Runnable action) { assertEquals(code, assertThrows(BizException.class, action::run).getCode()); }
    private long acceptedLogs(Fixture f) { return logs.findAll().stream().filter(l -> l.getOrderId().equals(f.order.getId()) && "ACCEPTED".equals(l.getFromStatus()) && "ACCEPTED".equals(l.getToStatus())).count(); }
    private long acceptedAudits(long id) { return audits.findAll().stream().filter(l -> "ORDER_TRANSFER_ACCEPTED".equals(l.getAction()) && l.getDetail().startsWith("transferId=" + id + ",")).count(); }

    @Test void requestDoesNotChangeOwnerAndOnlyConfirmationRotatesDeliveryCodeWithOriginalAcceptanceAndOneLog() {
        Fixture f = fixture(false); f.to.setOnlineStatus(0); riders.saveAndFlush(f.to);
        TransferVO pending = request(f); assertEquals("PENDING", pending.status()); assertEquals("DELIVERY", pending.taskMode());
        assertEquals(f.from.getId(), orders.findById(f.order.getId()).orElseThrow().getRiderId());
        assertEquals("1234", orders.findById(f.order.getId()).orElseThrow().getPickupCode());
        error(409, () -> request(f)); error(400, () -> accept(f, pending.id()));
        assertEquals(Status.PENDING, transfers.findById(pending.id()).orElseThrow().getStatus());
        f.to.setOnlineStatus(2); riders.saveAndFlush(f.to);
        assertEquals("ACCEPTED", accept(f, pending.id()).status()); assertEquals("ACCEPTED", accept(f, pending.id()).status());
        OrderMain order = orders.findById(f.order.getId()).orElseThrow();
        assertEquals(f.to.getId(), order.getRiderId()); assertNotEquals(f.toUser.getId(), f.to.getId());
        assertNotEquals("1234", order.getPickupCode()); assertEquals(f.order.getAcceptedTime(), order.getAcceptedTime()); assertEquals(OrderStatus.ACCEPTED, order.getStatus());
        assertEquals(1, acceptedLogs(f)); assertEquals(1, acceptedAudits(pending.id()));
        assertTrue(wallets.findById(f.fromUser.getId()).isEmpty()); assertTrue(wallets.findById(f.toUser.getId()).isEmpty()); assertTrue(settlements.findByOrderId(order.getId()).isEmpty());
        var log = logs.findAll().stream().filter(l -> l.getOrderId().equals(order.getId())).findFirst().orElseThrow();
        assertEquals("RIDER", log.getOperatorType()); assertEquals(f.toUser.getId(), log.getOperatorId());
        assertEquals(order.getPickupCode(), orderService.detailForUser(f.publisher.getId(), order.getId()).pickupCode());
        assertEquals(order.getPickupCode(), orderService.detailForUser(f.toUser.getId(), order.getId()).pickupCode());
        error(403, () -> orderService.detailForUser(f.fromUser.getId(), order.getId()));
        error(403, () -> dispatch.pickup(f.fromUser.getId(), order.getId(), "1234"));
        error(4092, () -> dispatch.pickup(f.toUser.getId(), order.getId(), "1234"));
        dispatch.pickup(f.toUser.getId(), order.getId(), order.getPickupCode());
    }

    @Test void rejectionAndCancellationKeepOriginalResponsibleAndTerminalActionsAreConflicts() {
        Fixture f = fixture(false); TransferVO first = request(f);
        error(403, () -> service.respond(f.fromUser.getId(), first.id(), new RespondReq("REJECTED", null)));
        error(403, () -> service.cancel(f.toUser.getId(), first.id(), new CancelReq(null)));
        assertEquals("REJECTED", service.respond(f.toUser.getId(), first.id(), new RespondReq("REJECTED", "没时间")).status());
        error(409, () -> service.respond(f.toUser.getId(), first.id(), new RespondReq("REJECTED", null)));
        error(409, () -> accept(f, first.id())); error(409, () -> service.cancel(f.fromUser.getId(), first.id(), null));
        TransferVO second = request(f); assertEquals("CANCELED", service.cancel(f.fromUser.getId(), second.id(), new CancelReq("自行继续")).status());
        error(409, () -> service.cancel(f.fromUser.getId(), second.id(), null)); error(409, () -> accept(f, second.id()));
        assertEquals(f.from.getId(), orders.findById(f.order.getId()).orElseThrow().getRiderId()); assertEquals(0, acceptedLogs(f));
        dispatch.pickup(f.fromUser.getId(), f.order.getId(), "1234"); assertEquals(OrderStatus.PICKING, orders.findById(f.order.getId()).orElseThrow().getStatus());
    }

    @Test void selfPublisherNonOwnerOrdinaryFrozenAndUnboundCannotSendOrReceive() {
        Fixture f = fixture(false);
        error(400, () -> service.create(f.fromUser.getId(), f.order.getId(), new TransferReq(f.from.getId(), "自己")));
        f.publisher.setRole("RIDER"); users.saveAndFlush(f.publisher); Rider publisher = rider(f.publisher);
        error(403, () -> service.create(f.fromUser.getId(), f.order.getId(), new TransferReq(publisher.getId(), "发布者")));
        error(403, () -> service.create(f.toUser.getId(), f.order.getId(), new TransferReq(f.from.getId(), "不是owner")));
        User ordinary = user("USER"); Rider ordinaryProfile = rider(ordinary);
        error(403, () -> service.create(f.fromUser.getId(), f.order.getId(), new TransferReq(ordinaryProfile.getId(), "普通用户")));
        mutateOrder(f, o -> o.setRiderId(ordinaryProfile.getId()));
        error(403, () -> service.create(ordinary.getId(), f.order.getId(), new TransferReq(f.to.getId(), "普通用户")));
        mutateOrder(f, o -> o.setRiderId(f.from.getId()));
        f.from.setStatus("FROZEN"); riders.saveAndFlush(f.from); error(403, () -> request(f));
        f.from.setStatus("ACTIVE"); riders.saveAndFlush(f.from); f.to.setStatus("FROZEN"); riders.saveAndFlush(f.to); error(403, () -> request(f));
        f.to.setStatus("ACTIVE"); riders.saveAndFlush(f.to); f.fromUser.setStatus(0); users.saveAndFlush(f.fromUser); error(403, () -> request(f));
        f.fromUser.setStatus(1); users.saveAndFlush(f.fromUser); f.toUser.setStatus(0); users.saveAndFlush(f.toUser); error(403, () -> request(f));
        f.toUser.setStatus(1); f.toUser.setMobile(null); users.saveAndFlush(f.toUser); error(4091, () -> request(f));
        f.toUser.setMobile("139" + String.format("%08d", sequence.incrementAndGet())); users.saveAndFlush(f.toUser);
        f.fromUser.setMobile(null); users.saveAndFlush(f.fromUser); error(4091, () -> request(f));
        assertTrue(transfers.findByOrderIdOrderByIdDesc(f.order.getId()).isEmpty());
    }

    @Test void receiverAndSenderEligibilityAreRecheckedAtAcceptance() {
        Fixture f = fixture(true); TransferVO t = request(f);
        f.to.setStatus("FROZEN"); riders.saveAndFlush(f.to); error(403, () -> accept(f, t.id()));
        f.to.setStatus("ACTIVE"); riders.saveAndFlush(f.to); f.toUser.setStatus(0); users.saveAndFlush(f.toUser); error(403, () -> accept(f, t.id()));
        f.toUser.setStatus(1); f.toUser.setRole("USER"); users.saveAndFlush(f.toUser); error(403, () -> accept(f, t.id()));
        f.toUser.setRole("RIDER"); f.toUser.setMobile(null); users.saveAndFlush(f.toUser); error(4091, () -> accept(f, t.id()));
        f.toUser.setMobile("139" + String.format("%08d", sequence.incrementAndGet())); users.saveAndFlush(f.toUser);
        f.from.setStatus("FROZEN"); riders.saveAndFlush(f.from); error(403, () -> accept(f, t.id()));
        assertEquals(Status.PENDING, transfers.findById(t.id()).orElseThrow().getStatus());
        f.from.setStatus("ACTIVE"); riders.saveAndFlush(f.from); accept(f, t.id());
        assertEquals("", orders.findById(f.order.getId()).orElseThrow().getPickupCode());
    }

    @Test void invalidInputAndAllNonAcceptedStatesRejectWithoutNewTransfer() {
        Fixture f = fixture(false);
        error(400, () -> service.create(f.fromUser.getId(), f.order.getId(), new TransferReq(0L, "无效")));
        error(400, () -> service.create(f.fromUser.getId(), f.order.getId(), new TransferReq(f.to.getId(), "  ")));
        error(400, () -> service.create(f.fromUser.getId(), f.order.getId(), new TransferReq(f.to.getId(), "长".repeat(256))));
        for (OrderStatus status : OrderStatus.values()) {
            if (status == OrderStatus.ACCEPTED) continue;
            mutateOrder(f, o -> o.setStatus(status)); error(409, () -> request(f));
        }
        mutateOrder(f, o -> { o.setStatus(OrderStatus.ACCEPTED); o.setFinishTime(LocalDateTime.now()); }); error(409, () -> request(f));
        mutateOrder(f, o -> { o.setFinishTime(null); o.setDeliveredTime(LocalDateTime.now()); }); error(409, () -> request(f));
        assertTrue(transfers.findByOrderIdOrderByIdDesc(f.order.getId()).isEmpty());
    }

    @Test void unsuccessfulOrNonMockPaymentCannotTransfer() {
        for (String change : List.of("missing", "PENDING", "WECHAT")) {
            Fixture f = fixture(false); var payment = payments.findByOrderId(f.order.getId()).get(0);
            if ("missing".equals(change)) payments.delete(payment);
            else { if ("PENDING".equals(change)) payment.setStatus(change); else payment.setChannel(change); payments.saveAndFlush(payment); }
            error(409, () -> request(f));
        }
    }

    @Test void anyComplaintIncludingRejectedOrDonePreventsRequestAndAcceptanceAndQueryExpiresPending() {
        for (String status : List.of("OPEN", "PROCESSING", "REJECTED", "APPROVED", "DONE")) {
            Fixture f = fixture(true); TransferVO t = request(f);
            Complaint complaint = new Complaint(); complaint.setOrderId(f.order.getId()); complaint.setUserId(f.publisher.getId()); complaint.setType("OTHER");
            complaint.setDescription("历史问题"); complaint.setStatus(status); complaintRecords.saveAndFlush(complaint);
            error(409, () -> accept(f, t.id()));
            assertEquals(Status.EXPIRED, transfers.findById(t.id()).orElseThrow().getStatus());
            error(409, () -> request(f)); assertEquals(f.from.getId(), orders.findById(f.order.getId()).orElseThrow().getRiderId());
        }
        Fixture f = fixture(true); TransferVO t = request(f);
        complaints.create(f.publisher.getId(), new com.feilv.dto.ApiDtos.ComplaintReq(f.order.getId(), "OTHER", "转派等待中投诉"));
        assertEquals(0, service.myList(f.toUser.getId(), 0, "INCOMING", "PENDING").getTotalElements());
        assertEquals("EXPIRED", service.forOrder(f.publisher.getId(), f.order.getId()).get(0).status());
        assertEquals(Status.EXPIRED, transfers.findById(t.id()).orElseThrow().getStatus());
    }

    @Test void existingSettlementDeductionCompensationReceiptAndWalletEntryPreventResponsibilityDrift() {
        Fixture settled = fixture(false); WalletOrderSettlement settlement = new WalletOrderSettlement(); settlement.setOrderId(settled.order.getId()); settlement.setRiderId(settled.from.getId());
        settlement.setUserId(settled.fromUser.getId()); settlement.setGrossFen(700); settlement.setNetFen(700); settlement.setCreatedTime(LocalDateTime.now()); settlements.saveAndFlush(settlement);
        error(409, () -> request(settled));
        Fixture deducted = fixture(false); ComplaintDeduction deduction = new ComplaintDeduction(); deduction.setComplaintId(999999L); deduction.setOrderId(deducted.order.getId()); deduction.setRiderId(deducted.from.getId());
        deduction.setUserId(deducted.publisher.getId()); deduction.setAmountFen(1); deduction.setCreatedBy(deducted.publisher.getId()); deduction.setCreatedTime(LocalDateTime.now()); deductions.saveAndFlush(deduction);
        error(409, () -> request(deducted));
        Fixture compensated = fixture(false); WalletComplaintReceipt receipt = new WalletComplaintReceipt(); receipt.setComplaintId(999999L); receipt.setDeductionId(999999L); receipt.setOrderId(compensated.order.getId());
        receipt.setRiderId(compensated.from.getId()); receipt.setRiderUserId(compensated.fromUser.getId()); receipt.setUserId(compensated.publisher.getId()); receipt.setAmountFen(1); receipt.setCreatedBy(compensated.publisher.getId()); receipt.setCreatedTime(LocalDateTime.now()); receipts.saveAndFlush(receipt);
        error(409, () -> request(compensated));
        Fixture ledger = fixture(false); WalletEntry entry = new WalletEntry(); entry.setUserId(ledger.publisher.getId()); entry.setBusinessKey("TRANSFER-GUARD-" + ledger.order.getId());
        entry.setType("COMPLAINT_COMPENSATION"); entry.setAmountFen(1); entry.setOrderId(ledger.order.getId()); entry.setRemark("旧账本"); entry.setCreatedTime(LocalDateTime.now()); entries.saveAndFlush(entry);
        error(409, () -> request(ledger));
    }

    @Test void onlyActualPickupOrServiceStartExpiresPendingAndOldOwnerMayStillStartWhilePending() {
        Fixture delivery = fixture(false); TransferVO d = request(delivery);
        error(4092, () -> dispatch.pickup(delivery.fromUser.getId(), delivery.order.getId(), "wrong"));
        assertEquals(Status.PENDING, transfers.findById(d.id()).orElseThrow().getStatus());
        dispatch.pickup(delivery.fromUser.getId(), delivery.order.getId(), "1234"); dispatch.pickup(delivery.fromUser.getId(), delivery.order.getId(), "1234");
        assertEquals(Status.EXPIRED, transfers.findById(d.id()).orElseThrow().getStatus()); error(409, () -> accept(delivery, d.id()));
        assertEquals(delivery.from.getId(), orders.findById(delivery.order.getId()).orElseThrow().getRiderId());
        Fixture task = fixture(true); TransferVO t = request(task);
        error(4090, () -> dispatch.pickup(task.fromUser.getId(), task.order.getId(), ""));
        assertEquals(Status.PENDING, transfers.findById(t.id()).orElseThrow().getStatus());
        dispatch.serviceStart(task.fromUser.getId(), task.order.getId()); dispatch.serviceStart(task.fromUser.getId(), task.order.getId());
        assertEquals(Status.EXPIRED, transfers.findById(t.id()).orElseThrow().getStatus()); error(409, () -> accept(task, t.id()));
    }

    @Test void cancellationAndOwnerChangeAndDirectStartedStateExpireOnQueriesOrResponseWithoutRollback() {
        Fixture canceled = fixture(false); TransferVO c = request(canceled);
        orderService.cancel(canceled.publisher.getId(), canceled.order.getId(), "不再需要");
        assertEquals("EXPIRED", service.forOrder(canceled.publisher.getId(), canceled.order.getId()).get(0).status());
        assertEquals(Status.EXPIRED, transfers.findById(c.id()).orElseThrow().getStatus());
        Fixture started = fixture(true); TransferVO s = request(started); mutateOrder(started, o -> o.setStatus(OrderStatus.PICKING));
        error(409, () -> accept(started, s.id())); assertEquals(Status.EXPIRED, transfers.findById(s.id()).orElseThrow().getStatus());
        Fixture changed = fixture(true); TransferVO t = request(changed); mutateOrder(changed, o -> o.setRiderId(changed.to.getId()));
        assertEquals(0, service.adminList(0, changed.order.getId(), "PENDING").getTotalElements());
        assertEquals(Status.EXPIRED, transfers.findById(t.id()).orElseThrow().getStatus());
    }

    @Test void serviceTransferChainsRetainHistoryAndOnlyFinalRiderGetsIncomeAndSubsequentComplaintResponsibility() {
        Fixture f = fixture(true); TransferVO first = request(f); accept(f, first.id());
        User lastUser = user("RIDER"); Rider last = rider(lastUser);
        TransferVO second = service.create(f.toUser.getId(), f.order.getId(), new TransferReq(last.getId(), "再次协调"));
        service.respond(lastUser.getId(), second.id(), new RespondReq("ACCEPTED", null));
        accept(f, first.id()); // 较早ACCEPTED重试不能抢回订单或再写日志。
        assertEquals(last.getId(), orders.findById(f.order.getId()).orElseThrow().getRiderId()); assertEquals(2, acceptedLogs(f));
        assertEquals(2, service.forOrder(f.fromUser.getId(), f.order.getId()).size());
        assertEquals(2, service.forOrder(f.toUser.getId(), f.order.getId()).size());
        User stranger = user("RIDER"); rider(stranger); error(403, () -> service.forOrder(stranger.getId(), f.order.getId()));
        error(403, () -> orderService.detailForUser(f.fromUser.getId(), f.order.getId())); error(403, () -> orderService.detailForUser(f.toUser.getId(), f.order.getId()));
        error(403, () -> dispatch.serviceStart(f.fromUser.getId(), f.order.getId())); error(403, () -> dispatch.serviceStart(f.toUser.getId(), f.order.getId()));
        dispatch.serviceStart(lastUser.getId(), f.order.getId()); dispatch.serviceComplete(lastUser.getId(), f.order.getId());
        orderService.confirm(f.publisher.getId(), f.order.getId()); orderService.confirm(f.publisher.getId(), f.order.getId());
        assertEquals(700, wallets.findById(lastUser.getId()).orElseThrow().getAvailableFen());
        assertTrue(wallets.findById(f.fromUser.getId()).isEmpty()); assertTrue(wallets.findById(f.toUser.getId()).isEmpty());
        assertEquals(last.getId(), settlements.findByOrderId(f.order.getId()).orElseThrow().getRiderId());
        long complaint = complaints.create(f.publisher.getId(), new com.feilv.dto.ApiDtos.ComplaintReq(f.order.getId(), "OTHER", "服务质量"));
        try { UserContext.set(new UserContext.CurrentUser(f.publisher.getId(), "ADMIN")); complaints.review(complaint, new ComplaintReviewReq("APPROVED", "最终骑手全额负责", 200)); }
        finally { UserContext.clear(); }
        assertEquals(last.getId(), deductions.findByOrderIdIn(List.of(f.order.getId())).get(0).getRiderId());
        assertEquals(500, wallets.findById(lastUser.getId()).orElseThrow().getAvailableFen()); assertEquals(200, wallets.findById(f.publisher.getId()).orElseThrow().getAvailableFen());
        assertEquals(700, payments.findByOrderId(f.order.getId()).get(0).getShareRider());
        assertTrue(wallets.findById(f.fromUser.getId()).isEmpty()); assertTrue(wallets.findById(f.toUser.getId()).isEmpty());
    }

    private List<Integer> race(Callable<Integer> a, Callable<Integer> b) throws Exception {
        var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> left = () -> { start.await(5, TimeUnit.SECONDS); return a.call(); };
            Callable<Integer> right = () -> { start.await(5, TimeUnit.SECONDS); return b.call(); };
            var first = executor.submit(left); var second = executor.submit(right); start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    private int result(Runnable action) { try { action.run(); return 0; } catch (BizException e) { return e.getCode(); } }

    @Test void concurrentRequestsCreateOnlyOnePendingAndConcurrentAcceptsWriteOnlyOneLog() throws Exception {
        Fixture f = fixture(true);
        var sent = race(() -> result(() -> request(f)), () -> result(() -> request(f)));
        assertEquals(1, sent.stream().filter(c -> c == 0).count()); assertEquals(1, sent.stream().filter(c -> c == 409).count());
        var pending = new TransactionTemplate(transactions).execute(tx -> transfers.findByOrderIdAndStatus(f.order.getId(), Status.PENDING)); assertEquals(1, pending.size());
        var accepted = race(() -> result(() -> accept(f, pending.get(0).getId())), () -> result(() -> accept(f, pending.get(0).getId())));
        assertEquals(List.of(0, 0), accepted); assertEquals(1, acceptedLogs(f)); assertEquals(1, acceptedAudits(pending.get(0).getId()));
    }

    @Test void concurrentAcceptanceVersusServiceStartHasOnlyOneWriterAndNeverTransfersStartedService() throws Exception {
        for (int i = 0; i < 8; i++) {
            Fixture f = fixture(true); TransferVO t = request(f);
            var results = race(() -> result(() -> accept(f, t.id())), () -> result(() -> dispatch.serviceStart(f.fromUser.getId(), f.order.getId())));
            assertEquals(1, results.stream().filter(c -> c == 0).count(), results.toString());
            var order = orders.findById(f.order.getId()).orElseThrow(); var transfer = transfers.findById(t.id()).orElseThrow();
            if (transfer.getStatus() == Status.ACCEPTED) {
                assertEquals(List.of(0, 403), results); assertEquals(f.to.getId(), order.getRiderId()); assertEquals(OrderStatus.ACCEPTED, order.getStatus()); assertEquals(1, acceptedLogs(f));
            } else {
                assertEquals(List.of(409, 0), results); assertEquals(Status.EXPIRED, transfer.getStatus());
                assertEquals(f.from.getId(), order.getRiderId()); assertEquals(OrderStatus.PICKING, order.getStatus()); assertEquals(0, acceptedLogs(f));
            }
        }
    }

    @Test void concurrentAcceptanceVersusDeliveryPickupAlsoSerializesCodeAndOwner() throws Exception {
        Fixture f = fixture(false); TransferVO t = request(f);
        var results = race(() -> result(() -> accept(f, t.id())), () -> result(() -> dispatch.pickup(f.fromUser.getId(), f.order.getId(), "1234")));
        assertEquals(1, results.stream().filter(c -> c == 0).count());
        var order = orders.findById(f.order.getId()).orElseThrow(); var transfer = transfers.findById(t.id()).orElseThrow();
        if (transfer.getStatus() == Status.ACCEPTED) {
            assertEquals(List.of(0, 403), results); assertNotEquals("1234", order.getPickupCode()); assertEquals(OrderStatus.ACCEPTED, order.getStatus());
        } else {
            assertEquals(List.of(409, 0), results); assertEquals(Status.EXPIRED, transfer.getStatus()); assertEquals("1234", order.getPickupCode()); assertEquals(OrderStatus.PICKING, order.getStatus());
        }
    }
}
