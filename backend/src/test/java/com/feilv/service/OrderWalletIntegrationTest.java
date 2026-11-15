package com.feilv.service;

import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.dto.ComplaintDtos.ComplaintReviewReq;
import com.feilv.dto.WalletDtos.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import com.feilv.task.OrderTimeoutTask;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:wallet-order-integration;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class OrderWalletIntegrationTest {
    @Autowired OrderService service;
    @Autowired PayService pay;
    @Autowired ComplaintService complaints;
    @Autowired WalletService wallets;
    @SpyBean WalletAccountingService accounting;
    @Autowired WalletAccountRepository accounts;
    @Autowired WalletOrderSettlementRepository settlements;
    @Autowired WalletEntryRepository entries;
    @Autowired OrderRepository orders;
    @Autowired PayRecordRepository payments;
    @Autowired UserRepository users;
    @Autowired RiderRepository riders;
    @Autowired ComplaintRepository complaintRecords;
    @Autowired ComplaintDeductionRepository deductions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrderTimeoutTask timeout;
    @Autowired MockMvc mvc;
    @MockBean AdminMenuAccess access;
    private static final AtomicLong sequence = new AtomicLong(500);
    private record Fixture(User publisher, User riderUser, Rider rider, OrderMain order) {}

    private Fixture fixture(OrderStatus status, boolean paid) {
        return new TransactionTemplate(transactions).execute(tx -> {
            long seq = sequence.incrementAndGet();
            User publisher = new User(); publisher.setMobile("138" + String.format("%08d", seq)); publisher.setNickname("发布者"); publisher = users.saveAndFlush(publisher);
            User riderUser = new User(); riderUser.setRole("RIDER"); riderUser.setMobile("139" + String.format("%08d", seq)); riderUser = users.saveAndFlush(riderUser);
            Rider rider = new Rider(); rider.setUserId(riderUser.getId()); rider.setName("承接者"); rider = riders.saveAndFlush(rider);
            OrderMain order = new OrderMain(); order.setOrderNo("WALLET-ORDER-" + seq); order.setOrderType("CUSTOM"); order.setUserId(publisher.getId()); order.setRiderId(rider.getId());
            order.setFromContact("取件人"); order.setFromPhone("13800138000"); order.setFromAddress("取件地址");
            order.setToContact("收件人"); order.setToPhone("13900139000"); order.setToAddress("送达地址"); order.setEstimateFee(1000); order.setRealFee(1000);
            order.setStatus(status); order.setDeliveredTime(LocalDateTime.now()); order = orders.saveAndFlush(order);
            if (paid) { PayRecord payment = new PayRecord(); payment.setPayNo("WALLET-PAY-" + seq); payment.setOrderId(order.getId()); payment.setChannel("MOCK"); payment.setStatus("SUCCESS"); payment.setAmount(1000); payment.setShareRider(700); payment.setSharePlatform(300); payments.saveAndFlush(payment); }
            return new Fixture(publisher, riderUser, rider, order);
        });
    }

    @Test void userReceiptCreditsOnlyRiderAndRepeatedReceiptDoesNotDuplicatePayment() {
        Fixture f = fixture(OrderStatus.DELIVERED, true);
        service.confirm(f.publisher.getId(), f.order.getId()); service.confirm(f.publisher.getId(), f.order.getId());
        assertEquals(OrderStatus.FINISHED, orders.findById(f.order.getId()).orElseThrow().getStatus());
        assertEquals(700, accounts.findById(f.riderUser.getId()).orElseThrow().getAvailableFen());
        assertTrue(accounts.findById(f.publisher.getId()).isEmpty());
        assertEquals(1, settlements.findAll().stream().filter(s -> s.getOrderId().equals(f.order.getId())).count());
        assertEquals(700, payments.findByOrderId(f.order.getId()).get(0).getShareRider());
    }

    @Test void adminForcedAndTimeoutCompletionUseSameWalletHookAndLegacyFinishedIsNotBackfilled() throws Exception {
        User admin = new User(); admin.setRole("ADMIN"); admin.setNickname("后台完成"); admin = users.saveAndFlush(admin);
        Fixture forced = fixture(OrderStatus.DELIVERED, true);
        mvc.perform(post("/api/v1/admin/orders/" + forced.order.getId() + "/complete")
                        .header("Authorization", "Bearer " + JwtUtil.create(admin.getId(), "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"后台核实完成\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(700, accounts.findById(forced.riderUser.getId()).orElseThrow().getAvailableFen());
        Fixture automatic = fixture(OrderStatus.DELIVERED, true);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { var order = orders.findById(automatic.order.getId()).orElseThrow(); order.setDeliveredTime(LocalDateTime.now().minusMinutes(20)); orders.saveAndFlush(order); });
        timeout.scan(); assertEquals(700, accounts.findById(automatic.riderUser.getId()).orElseThrow().getAvailableFen());
        Fixture historic = fixture(OrderStatus.FINISHED, true);
        service.confirm(historic.publisher.getId(), historic.order.getId());
        assertTrue(accounts.findById(historic.riderUser.getId()).isEmpty());
    }

    @Test void concurrentReceiptsHaveOneSettlementAndIncome() throws Exception {
        Fixture f = fixture(OrderStatus.DELIVERED, true); var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> receipt = () -> { start.await(5, TimeUnit.SECONDS); service.confirm(f.publisher.getId(), f.order.getId()); return null; };
            var a = executor.submit(receipt); var b = executor.submit(receipt); start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            assertEquals(700, accounts.findById(f.riderUser.getId()).orElseThrow().getAvailableFen());
            assertEquals(1, settlements.findAll().stream().filter(s -> s.getOrderId().equals(f.order.getId())).count());
        } finally { executor.shutdownNow(); }
    }

    @Test void walletFailureRollsBackStatusAndNoIncomeEscapes() {
        Fixture f = fixture(OrderStatus.DELIVERED, true);
        doThrow(new IllegalStateException("wallet-write-failed")).when(accounting).settleFinishedOrder(f.order.getId());
        try {
            assertThrows(IllegalStateException.class, () -> service.confirm(f.publisher.getId(), f.order.getId()));
            assertEquals(OrderStatus.DELIVERED, orders.findById(f.order.getId()).orElseThrow().getStatus());
            assertTrue(accounts.findById(f.riderUser.getId()).isEmpty());
        } finally { reset(accounting); }
    }

    @Test void approvedComplaintAfterIncomeCreatesDebtWithoutTouchingFreezeAndRejectedWithdrawalPaysDebtFirst() {
        Fixture f = fixture(OrderStatus.DELIVERED, true); service.confirm(f.publisher.getId(), f.order.getId());
        Long withdrawal;
        try {
            UserContext.set(new UserContext.CurrentUser(f.riderUser.getId(), "RIDER"));
            withdrawal = wallets.requestWithdrawal(new WithdrawalReq(600L, "wallet-integration-" + f.order.getId())).id();
            var complaint = new com.feilv.dto.ApiDtos.ComplaintReq(f.order.getId(), "OTHER", "任务服务问题");
            long id = complaints.create(f.publisher.getId(), complaint);
            UserContext.set(new UserContext.CurrentUser(f.publisher.getId(), "ADMIN"));
            complaints.review(id, new ComplaintReviewReq("APPROVED", "核实赔付", 300));
            var riderWallet = accounts.findById(f.riderUser.getId()).orElseThrow();
            assertEquals(0, riderWallet.getAvailableFen()); assertEquals(600, riderWallet.getFrozenFen()); assertEquals(200, riderWallet.getDebtFen());
            assertEquals(300, accounts.findById(f.publisher.getId()).orElseThrow().getAvailableFen());
            wallets.reviewWithdrawal(withdrawal, new WithdrawalReviewReq("REJECTED", "驳回测试提现"));
            riderWallet = accounts.findById(f.riderUser.getId()).orElseThrow();
            assertEquals(400, riderWallet.getAvailableFen()); assertEquals(0, riderWallet.getFrozenFen()); assertEquals(0, riderWallet.getDebtFen());
            assertEquals(700, payments.findByOrderId(f.order.getId()).get(0).getShareRider());
        } finally { UserContext.clear(); }
    }

    @Test void complaintBeforeReceiptCreditsPublisherAndNetIncomeOnlyOnce() {
        Fixture f = fixture(OrderStatus.DELIVERED, true);
        try {
            long id = complaints.create(f.publisher.getId(), new com.feilv.dto.ApiDtos.ComplaintReq(f.order.getId(), "OTHER", "先投诉后确认"));
            UserContext.set(new UserContext.CurrentUser(f.publisher.getId(), "ADMIN"));
            complaints.review(id, new ComplaintReviewReq("APPROVED", "提前赔付", 200));
            assertEquals(200, accounts.findById(f.publisher.getId()).orElseThrow().getAvailableFen());
            service.confirm(f.publisher.getId(), f.order.getId());
            assertEquals(500, accounts.findById(f.riderUser.getId()).orElseThrow().getAvailableFen());
            assertEquals(200, accounts.findById(f.publisher.getId()).orElseThrow().getAvailableFen());
        } finally { UserContext.clear(); }
    }

    @Test void newlyApprovedComplaintOnHistoricalFinishedOrderCannotCreateUnfundedCompensation() {
        Fixture f = fixture(OrderStatus.FINISHED, true);
        long id = complaints.create(f.publisher.getId(), new com.feilv.dto.ApiDtos.ComplaintReq(f.order.getId(), "OTHER", "历史单需人工核对"));
        try {
            UserContext.set(new UserContext.CurrentUser(f.publisher.getId(), "ADMIN"));
            var error = assertThrows(com.feilv.common.BizException.class, () -> complaints.review(id, new ComplaintReviewReq("APPROVED", "历史单赔付", 200)));
            assertEquals(409, error.getCode());
            assertEquals("OPEN", complaintRecords.findById(id).orElseThrow().getStatus());
            assertTrue(deductions.findByOrderIdIn(List.of(f.order.getId())).isEmpty());
            assertTrue(accounts.findById(f.publisher.getId()).isEmpty());
        } finally { UserContext.clear(); }
    }

    @Test void concurrentMockPaymentsDoNotCreateDuplicateFundedIncome() throws Exception {
        Fixture f = fixture(OrderStatus.WAIT_PAY, false); var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> payment = () -> { start.await(5, TimeUnit.SECONDS); pay.pay(f.publisher.getId(), f.order.getId()); return null; };
            var a = executor.submit(payment); var b = executor.submit(payment); start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            assertEquals(1, payments.findByOrderId(f.order.getId()).size());
            assertEquals(OrderStatus.WAIT_GRAB, orders.findById(f.order.getId()).orElseThrow().getStatus());
        } finally { executor.shutdownNow(); }
    }
}
