package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.dto.ApiDtos.GrabVO;
import com.feilv.dto.OrderTransferDtos.RespondReq;
import com.feilv.dto.OrderTransferDtos.TransferReq;
import com.feilv.dto.RiderCapabilityDtos.CapabilityReq;
import com.feilv.enums.Enums.CreditReason;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** 能力判定、事务与真实H2行锁专项；无默认/local后端或外部数据库依赖。 */
@SpringBootTest(properties = {com.feilv.controller.TestAuth.TEST_SECRET_PROPERTY,
        "spring.datasource.url=jdbc:h2:mem:rider-capability-integration;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"})
class RiderCapabilityIntegrationTest {
    @Autowired RiderCapabilityService capabilities;
    @Autowired DispatchService dispatch;
    @Autowired OrderTransferService transferService;
    @Autowired OrderService orderService;
    @Autowired ReviewRiskService risk;
    @Autowired UserRepository users;
    @Autowired RiderRepository riders;
    @Autowired OrderRepository orders;
    @Autowired CustomTaskRepository tasks;
    @Autowired PayRecordRepository payments;
    @Autowired OrderTransferRepository transfers;
    @Autowired OrderStatusLogRepository logs;
    @Autowired OpLogRepository audits;
    @Autowired WalletAccountRepository wallets;
    @Autowired WalletEntryRepository entries;
    @Autowired WalletOrderSettlementRepository settlements;
    @Autowired CreditLogRepository creditLogs;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminUserRoleRepository userRoles;
    @Autowired AdminMenuRepository menus;
    @Autowired AdminRoleMenuRepository roleMenus;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean AuditService audit;
    @MockBean MessageService messages;

    private static final AtomicLong sequence = new AtomicLong(91000);
    private record RiderUser(User user, Rider rider) {}
    private record Fixture(User publisher, OrderMain order) {}
    private User manager;

    @BeforeEach void prepare() {
        transfers.deleteAll(); tasks.deleteAll(); payments.deleteAll(); orders.deleteAll();
        manager = user("ADMIN"); AdminAccount account = new AdminAccount(); account.setUserId(manager.getId());
        account.setUsername("cap-service-" + manager.getId()); account.setPasswordHash("not-a-real-password"); accounts.saveAndFlush(account);
        AdminRole role = new AdminRole(); role.setCode("CAP_SVC_" + manager.getId()); role.setName("骑手能力测试"); roles.saveAndFlush(role);
        AdminUserRole link = new AdminUserRole(); link.setUserId(manager.getId()); link.setRoleId(role.getId()); userRoles.saveAndFlush(link);
        AdminMenu menu = menus.findAll().stream().filter(m -> "riders".equals(m.getCode())).findFirst().orElseGet(() -> {
            AdminMenu created = new AdminMenu(); created.setCode("riders"); created.setName("骑手管理"); return menus.saveAndFlush(created);
        });
        AdminRoleMenu grant = new AdminRoleMenu(); grant.setRoleId(role.getId()); grant.setMenuId(menu.getId()); roleMenus.saveAndFlush(grant);
    }

    private User user(String role) {
        User user = new User(); user.setRole(role); user.setNickname("能力集成用户");
        user.setMobile("135" + String.format("%08d", sequence.incrementAndGet())); return users.saveAndFlush(user);
    }
    private RiderUser rider() {
        User user = user("RIDER"); Rider rider = new Rider(); rider.setUserId(user.getId()); rider.setName("能力集成骑手");
        rider.setOnlineStatus(1); rider.setVehicleType("CAR"); return new RiderUser(user, riders.saveAndFlush(rider));
    }
    private Fixture fixture(String mode, RiderUser assigned) {
        User publisher = user("USER"); OrderMain order = new OrderMain();
        order.setOrderNo("CAP-SVC-" + sequence.incrementAndGet());
        order.setOrderType(Set.of("SEND", "BUY", "ERRAND").contains(mode) ? mode : "CUSTOM");
        order.setUserId(publisher.getId()); order.setEstimateFee(1000); order.setRealFee(1000); order.setGoodsDesc("历史配送或服务");
        order.setFromContact("联系人"); order.setFromPhone("13800138000"); order.setFromAddress("起始地点");
        order.setToContact(""); order.setToPhone(""); order.setToAddress("");
        boolean delivery = Set.of("SEND", "BUY", "ERRAND", "DELIVERY", "MISSING_METADATA").contains(mode);
        order.setPickupCode(delivery ? "1234" : ""); order.setStatus(assigned == null ? OrderStatus.WAIT_GRAB : OrderStatus.ACCEPTED);
        if (assigned != null) { order.setRiderId(assigned.rider().getId()); order.setAcceptedTime(LocalDateTime.now().minusMinutes(2).withNano(0)); }
        orders.saveAndFlush(order);
        if ("CUSTOM".equals(order.getOrderType()) && !"MISSING_METADATA".equals(mode)) {
            CustomTask task = new CustomTask(); task.setOrderId(order.getId()); task.setUserId(publisher.getId()); task.setTaskMode(mode);
            task.setTitle(mode + "服务"); task.setContent("需求详情"); task.setContact("联系人"); task.setPhone("13800138000");
            task.setLocationAddress("起始地点"); task.setServiceTime("今天18:30"); task.setPublishSource("MINIAPP"); task.setCreatedBy(publisher.getId()); tasks.saveAndFlush(task);
        }
        PayRecord payment = new PayRecord(); payment.setOrderId(order.getId()); payment.setPayNo("CAP-SVC-PAY-" + sequence.incrementAndGet());
        payment.setChannel("MOCK"); payment.setStatus("SUCCESS"); payment.setAmount(1000); payment.setShareRider(700); payment.setSharePlatform(300); payments.saveAndFlush(payment);
        return new Fixture(publisher, order);
    }
    private void update(Rider rider, String mode, boolean enabled, boolean expected) {
        asAdmin(() -> capabilities.update(rider.getId(), mode, new CapabilityReq(enabled, expected, "能力事务测试")));
    }
    private void asAdmin(Runnable action) {
        try { UserContext.set(new UserContext.CurrentUser(manager.getId(), "ADMIN")); action.run(); }
        finally { UserContext.clear(); }
    }
    private TransactionTemplate tx() {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); return template;
    }
    private void error(int code, Runnable action) { assertEquals(code, assertThrows(BizException.class, action::run).getCode()); }
    private int result(Runnable action) { try { action.run(); return 0; } catch (BizException e) { return e.getCode(); } }
    private List<OpLog> capabilityAudits(Rider rider) {
        return audits.findAll().stream().filter(a -> "RIDER_CAPABILITY_UPDATE".equals(a.getAction()) && a.getDetail().startsWith("riderId=" + rider.getId() + ",")).toList();
    }
    private long statusLogs(OrderMain order, String from, String to) {
        return logs.findAll().stream().filter(l -> order.getId().equals(l.getOrderId()) && from.equals(l.getFromStatus()) && to.equals(l.getToStatus())).count();
    }
    private void assertDisabled(Rider rider) {
        Rider current = riders.findById(rider.getId()).orElseThrow(); assertFalse(current.isRideEnabled()); assertFalse(current.isDrivingEnabled());
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "等待事务同步信号超时"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static <T> T get(Future<T> future) {
        try { return future.get(15, TimeUnit.SECONDS); }
        catch (Exception e) { throw new AssertionError("后台事务未按预期结束", e); }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void allFourCombinationsFilterHallAndEnforceDirectGrabAndInitialStart(boolean ride, boolean driving) {
        RiderUser rider = rider();
        if (ride) update(rider.rider(), "RIDE", true, false);
        if (driving) update(rider.rider(), "DRIVING", true, false);
        List<Fixture> pool = new ArrayList<>();
        for (String mode : List.of("SEND", "ONSITE", "PART_TIME", "ONLINE", "RIDE", "DRIVING")) pool.add(fixture(mode, null));
        Set<Long> expected = new HashSet<>();
        for (Fixture fixture : pool) {
            String mode = orderService.serviceTaskMode(fixture.order());
            if ((!"RIDE".equals(mode) || ride) && (!"DRIVING".equals(mode) || driving)) expected.add(fixture.order().getId());
        }
        assertEquals(expected, new HashSet<>(dispatch.waitGrabList(rider.user().getId()).stream().map(GrabVO::orderId).toList()));
        for (Fixture fixture : pool) {
            boolean allowed = expected.contains(fixture.order().getId());
            if (allowed) {
                dispatch.grab(rider.user().getId(), fixture.order().getId());
                assertEquals(rider.rider().getId(), orders.findById(fixture.order().getId()).orElseThrow().getRiderId());
            } else {
                error(403, () -> dispatch.grab(rider.user().getId(), fixture.order().getId()));
                OrderMain untouched = orders.findById(fixture.order().getId()).orElseThrow(); assertEquals(OrderStatus.WAIT_GRAB, untouched.getStatus()); assertNull(untouched.getRiderId());
                assertEquals(0, statusLogs(fixture.order(), "WAIT_GRAB", "ACCEPTED"));
            }
        }
        for (String mode : List.of("RIDE", "DRIVING")) {
            Fixture assigned = fixture(mode, rider); boolean allowed = "RIDE".equals(mode) ? ride : driving;
            if (allowed) dispatch.serviceStart(rider.user().getId(), assigned.order().getId());
            else error(403, () -> dispatch.serviceStart(rider.user().getId(), assigned.order().getId()));
            assertEquals(allowed ? OrderStatus.PICKING : OrderStatus.ACCEPTED, orders.findById(assigned.order().getId()).orElseThrow().getStatus());
        }
    }

    @Test void legacyHallSignatureHasTransactionAndUsesCurrentRiderPermissions() {
        RiderUser rider = rider(); Fixture delivery = fixture("SEND", null), ride = fixture("RIDE", null);
        try {
            UserContext.set(new UserContext.CurrentUser(rider.user().getId(), "RIDER"));
            assertEquals(List.of(delivery.order().getId()), dispatch.waitGrabList().stream().map(GrabVO::orderId).toList());
            update(rider.rider(), "RIDE", true, false);
            UserContext.set(new UserContext.CurrentUser(rider.user().getId(), "RIDER"));
            assertEquals(List.of(ride.order().getId(), delivery.order().getId()), dispatch.waitGrabList().stream().map(GrabVO::orderId).toList());
        } finally { UserContext.clear(); }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void moreThanTwentyNewerUnauthorizedOrdersCannotDisplaceFullEligiblePage(boolean ride, boolean driving) {
        RiderUser rider = rider();
        if (ride) update(rider.rider(), "RIDE", true, false);
        if (driving) update(rider.rider(), "DRIVING", true, false);
        List<Long> eligible = new ArrayList<>();
        for (int i = 0; i < 24; i++) eligible.add(fixture(ride ? "RIDE" : driving ? "DRIVING" : "ONSITE", null).order().getId());
        for (int i = 0; i < 27; i++) fixture(ride ? "DRIVING" : driving ? "RIDE" : i % 2 == 0 ? "RIDE" : "DRIVING", null);
        List<GrabVO> page = dispatch.waitGrabList(rider.user().getId());
        assertEquals(20, page.size()); List<Long> newestEligible = new ArrayList<>(eligible.subList(4, 24)); java.util.Collections.reverse(newestEligible);
        assertEquals(newestEligible, page.stream().map(GrabVO::orderId).toList(), "必须在SQL分页前筛除无授权服务，而非先取20条再过滤");
        assertTrue(page.stream().allMatch(o -> o.rewardFen() == 700));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RIDE", "DRIVING"})
    void targetNeedsMatchingCapabilityAtCreateAndAcceptAndRevokedOriginalRiderCanTransferOut(String mode) {
        RiderUser source = rider(), target = rider(); Fixture fixture = fixture(mode, source);
        String otherMode = "RIDE".equals(mode) ? "DRIVING" : "RIDE";
        update(source.rider(), mode, true, false); update(source.rider(), mode, false, true);
        update(target.rider(), otherMode, true, false); // 另一能力不能替代本能力。
        TransferReq request = new TransferReq(target.rider().getId(), "原骑手撤权后转出");
        error(403, () -> transferService.create(source.user().getId(), fixture.order().getId(), request));
        assertTrue(transfers.findByOrderIdOrderByIdDesc(fixture.order().getId()).isEmpty());
        update(target.rider(), mode, true, false);
        var pending = transferService.create(source.user().getId(), fixture.order().getId(), request);
        update(target.rider(), mode, false, true);
        error(403, () -> transferService.respond(target.user().getId(), pending.id(), new RespondReq("ACCEPTED", null)));
        assertEquals(OrderTransfer.Status.PENDING, transfers.findById(pending.id()).orElseThrow().getStatus());
        assertEquals(source.rider().getId(), orders.findById(fixture.order().getId()).orElseThrow().getRiderId());
        assertEquals(0, statusLogs(fixture.order(), "ACCEPTED", "ACCEPTED"));
        assertEquals("REJECTED", transferService.respond(target.user().getId(), pending.id(), new RespondReq("REJECTED", "已被撤权")).status());
        update(target.rider(), mode, true, false);
        var next = transferService.create(source.user().getId(), fixture.order().getId(), request);
        assertEquals("ACCEPTED", transferService.respond(target.user().getId(), next.id(), new RespondReq("ACCEPTED", null)).status());
        update(target.rider(), mode, false, true);
        assertEquals("ACCEPTED", transferService.respond(target.user().getId(), next.id(), new RespondReq("ACCEPTED", null)).status());
        assertEquals(target.rider().getId(), orders.findById(fixture.order().getId()).orElseThrow().getRiderId());
        assertEquals(1, statusLogs(fixture.order(), "ACCEPTED", "ACCEPTED"));
        error(403, () -> dispatch.serviceStart(target.user().getId(), fixture.order().getId()));
        assertTrue(settlements.findByOrderId(fixture.order().getId()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RIDE", "DRIVING"})
    void revocationAfterGrabPreventsStartAndCompletionUntilTransferredToAuthorizedRider(String mode) {
        RiderUser source = rider(), target = rider(); Fixture fixture = fixture(mode, null);
        update(source.rider(), mode, true, false); dispatch.grab(source.user().getId(), fixture.order().getId());
        update(source.rider(), mode, false, true);
        error(403, () -> dispatch.serviceStart(source.user().getId(), fixture.order().getId()));
        error(4090, () -> dispatch.serviceComplete(source.user().getId(), fixture.order().getId()));
        assertEquals(0, statusLogs(fixture.order(), "ACCEPTED", "PICKING"));
        assertTrue(settlements.findByOrderId(fixture.order().getId()).isEmpty());
        update(target.rider(), mode, true, false);
        var transfer = transferService.create(source.user().getId(), fixture.order().getId(), new TransferReq(target.rider().getId(), "平台撤权后安排他人"));
        transferService.respond(target.user().getId(), transfer.id(), new RespondReq("ACCEPTED", null));
        error(403, () -> dispatch.serviceStart(source.user().getId(), fixture.order().getId()));
        dispatch.serviceStart(target.user().getId(), fixture.order().getId()); dispatch.serviceComplete(target.user().getId(), fixture.order().getId());
        orderService.confirm(fixture.publisher().getId(), fixture.order().getId());
        assertEquals(700, wallets.findById(target.user().getId()).orElseThrow().getAvailableFen());
        assertTrue(wallets.findById(source.user().getId()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RIDE", "DRIVING"})
    void startedServiceRetainsCompletionAndWalletIdempotencyAfterRevocation(String mode) {
        RiderUser rider = rider(); Fixture fixture = fixture(mode, null); update(rider.rider(), mode, true, false);
        dispatch.grab(rider.user().getId(), fixture.order().getId()); dispatch.serviceStart(rider.user().getId(), fixture.order().getId());
        update(rider.rider(), mode, false, true);
        for (int i = 0; i < 3; i++) {
            dispatch.serviceStart(rider.user().getId(), fixture.order().getId());
            dispatch.serviceComplete(rider.user().getId(), fixture.order().getId());
            orderService.confirm(fixture.publisher().getId(), fixture.order().getId());
        }
        assertEquals(OrderStatus.FINISHED, orders.findById(fixture.order().getId()).orElseThrow().getStatus());
        assertEquals(1, statusLogs(fixture.order(), "WAIT_GRAB", "ACCEPTED")); assertEquals(1, statusLogs(fixture.order(), "ACCEPTED", "PICKING"));
        assertEquals(1, statusLogs(fixture.order(), "PICKING", "DELIVERING")); assertEquals(1, statusLogs(fixture.order(), "DELIVERING", "DELIVERED"));
        assertEquals(1, statusLogs(fixture.order(), "DELIVERED", "FINISHED"));
        assertEquals(700, wallets.findById(rider.user().getId()).orElseThrow().getAvailableFen());
        assertEquals(1, settlements.findAll().stream().filter(s -> fixture.order().getId().equals(s.getOrderId())).count());
        assertEquals(rider.rider().getId(), settlements.findByOrderId(fixture.order().getId()).orElseThrow().getRiderId());
        assertEquals(1, entries.findAll().stream().filter(e -> fixture.order().getId().equals(e.getOrderId()) && rider.user().getId().equals(e.getUserId())).count());
        assertEquals(700, payments.findByOrderId(fixture.order().getId()).get(0).getShareRider()); assertDisabled(rider.rider());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEND", "BUY", "ERRAND", "DELIVERY", "MISSING_METADATA", "ONSITE", "PART_TIME", "ONLINE"})
    void ordinaryRiderAndHistoricalModesRemainUsableWithoutNewCapabilities(String mode) {
        RiderUser rider = rider(); Fixture fixture = fixture(mode, null); assertDisabled(rider.rider());
        assertTrue(dispatch.waitGrabList(rider.user().getId()).stream().anyMatch(o -> o.orderId() == fixture.order().getId()));
        String code = dispatch.grab(rider.user().getId(), fixture.order().getId());
        if (orderService.isServiceTask(fixture.order())) {
            assertEquals("", code); dispatch.serviceStart(rider.user().getId(), fixture.order().getId()); dispatch.serviceComplete(rider.user().getId(), fixture.order().getId());
        } else {
            assertEquals("1234", code); dispatch.pickup(rider.user().getId(), fixture.order().getId(), code);
            dispatch.depart(rider.user().getId(), fixture.order().getId()); dispatch.deliver(rider.user().getId(), fixture.order().getId());
        }
        orderService.confirm(fixture.publisher().getId(), fixture.order().getId());
        assertEquals(OrderStatus.FINISHED, orders.findById(fixture.order().getId()).orElseThrow().getStatus()); assertDisabled(rider.rider());
    }

    @Test void capabilityServiceCannotBypassMenuPermissionWhenInvokedWithoutHttpController() {
        Rider rider = rider().rider(); User noMenuAdmin = user("ADMIN");
        CapabilityReq request = new CapabilityReq(true, false, "直接服务调用");
        error(401, () -> capabilities.update(rider.getId(), "RIDE", request));
        try {
            UserContext.set(new UserContext.CurrentUser(noMenuAdmin.getId(), "ADMIN"));
            error(403, () -> capabilities.update(rider.getId(), "RIDE", request));
        } finally { UserContext.clear(); }
        assertDisabled(rider); assertTrue(capabilityAudits(rider).isEmpty());
    }

    @Test void auditFailureRollsBackCapabilityAndSuccessfulTransactionCommitsBoth() {
        Rider rider = rider().rider();
        try {
            doThrow(new IllegalStateException("模拟审计失败")).when(audit).record(eq("RIDER_CAPABILITY_UPDATE"), anyString());
            assertThrows(IllegalStateException.class, () -> update(rider, "RIDE", true, false));
        } finally { reset(audit); }
        assertDisabled(rider); assertTrue(capabilityAudits(rider).isEmpty());
        update(rider, "RIDE", true, false);
        assertTrue(riders.findById(rider.getId()).orElseThrow().isRideEnabled()); assertEquals(1, capabilityAudits(rider).size());
        assertEquals(manager.getId(), capabilityAudits(rider).get(0).getOperatorId());
    }

    @Test void concurrentCasUpdatesHaveExactlyOneWinnerAndOneAudit() throws Exception {
        Rider rider = rider().rider(); ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch begin = new CountDownLatch(1);
        try {
            Callable<Integer> update = () -> { await(begin); return result(() -> update(rider, "RIDE", true, false)); };
            Future<Integer> first = executor.submit(update), second = executor.submit(update); begin.countDown();
            List<Integer> results = List.of(get(first), get(second));
            assertEquals(1, results.stream().filter(code -> code == 0).count()); assertEquals(1, results.stream().filter(code -> code == 409).count());
            assertEquals(1, capabilityAudits(rider).size()); assertTrue(riders.findById(rider.getId()).orElseThrow().isRideEnabled());
            assertFalse(riders.findById(rider.getId()).orElseThrow().isDrivingEnabled());
        } finally { executor.shutdownNow(); }
    }

    @Test void cachedRiderCannotDefeatCasAfterAnotherTransactionChangesCapability() {
        Rider rider = rider().rider(); ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            error(409, () -> tx().executeWithoutResult(status -> {
                Rider cached = riders.findById(rider.getId()).orElseThrow(); assertFalse(cached.isRideEnabled());
                get(executor.submit(() -> update(rider, "RIDE", true, false)));
                // 当前持久化上下文仍缓存false；锁后必须refresh，不能用旧缓存通过CAS。
                asAdmin(() -> capabilities.update(rider.getId(), "RIDE", new CapabilityReq(false, false, "陈旧预期不可覆盖")));
            }));
            assertTrue(riders.findById(rider.getId()).orElseThrow().isRideEnabled()); assertEquals(1, capabilityAudits(rider).size());
        } finally { executor.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"online", "credit", "both"})
    void legacyOnlineAndCreditSavesFromStaleManagedRiderCannotRestoreRevokedCapabilities(String operation) {
        RiderUser rider = rider(); update(rider.rider(), "RIDE", true, false); update(rider.rider(), "DRIVING", true, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            tx().executeWithoutResult(status -> {
                Rider cached = riders.findById(rider.rider().getId()).orElseThrow(); assertTrue(cached.isRideEnabled()); assertTrue(cached.isDrivingEnabled());
                get(executor.submit(() -> { update(rider.rider(), "RIDE", false, true); update(rider.rider(), "DRIVING", false, true); }));
                assertTrue(cached.isRideEnabled(), "用例须保留旧实体快照以覆盖DynamicUpdate保护");
                if (!"credit".equals(operation)) dispatch.online(rider.user().getId(), 2);
                if (!"online".equals(operation)) risk.applyCredit(rider.rider().getId(), CreditReason.FIVE_STAR, null);
                riders.flush();
            });
            assertDisabled(rider.rider()); Rider actual = riders.findById(rider.rider().getId()).orElseThrow();
            assertEquals("credit".equals(operation) ? 1 : 2, actual.getOnlineStatus()); assertEquals("online".equals(operation) ? 100 : 101, actual.getCreditScore());
            assertEquals("online".equals(operation) ? 0 : 1, creditLogs.findByRiderIdOrderByIdDesc(rider.rider().getId()).size());
            assertEquals(4, capabilityAudits(rider.rider()).size());
            Fixture ride = fixture("RIDE", null), driving = fixture("DRIVING", null);
            error(403, () -> dispatch.grab(rider.user().getId(), ride.order().getId())); error(403, () -> dispatch.grab(rider.user().getId(), driving.order().getId()));
        } finally { executor.shutdownNow(); }
    }

    private void dispatchOperation(String operation, RiderUser rider, Fixture fixture) {
        if ("grab".equals(operation)) dispatch.grab(rider.user().getId(), fixture.order().getId());
        else dispatch.serviceStart(rider.user().getId(), fixture.order().getId());
    }

    /** 首事务实际完成写入后仍不提交，让第二事务必须等待同一骑手行锁；无Thread.sleep调度猜测。 */
    private void assertRevocationSerializes(String mode, String operation, boolean revokeFirst) throws Exception {
        RiderUser rider = rider(); update(rider.rider(), mode, true, false);
        Fixture fixture = fixture(mode, "grab".equals(operation) ? null : rider);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstWrote = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> tx().execute(status -> {
                if (revokeFirst) update(rider.rider(), mode, false, true); else dispatchOperation(operation, rider, fixture);
                firstWrote.countDown(); await(releaseFirst); return 0;
            }));
            await(firstWrote);
            Future<Integer> second = executor.submit(() -> {
                secondEntered.countDown();
                return result(() -> { if (revokeFirst) dispatchOperation(operation, rider, fixture); else update(rider.rider(), mode, false, true); });
            });
            await(secondEntered);
            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS), "第二事务不得在首事务持有骑手能力行锁时越过检查");
            releaseFirst.countDown(); assertEquals(0, get(first)); assertEquals(revokeFirst ? 403 : 0, get(second));
            assertDisabled(rider.rider()); assertEquals(2, capabilityAudits(rider.rider()).size());
            OrderMain actual = orders.findById(fixture.order().getId()).orElseThrow();
            if (revokeFirst) {
                assertEquals("grab".equals(operation) ? OrderStatus.WAIT_GRAB : OrderStatus.ACCEPTED, actual.getStatus());
                assertEquals("grab".equals(operation) ? null : rider.rider().getId(), actual.getRiderId());
                assertEquals(0, statusLogs(fixture.order(), "ACCEPTED", "PICKING"));
                if ("grab".equals(operation)) assertEquals(0, statusLogs(fixture.order(), "WAIT_GRAB", "ACCEPTED"));
            } else if ("grab".equals(operation)) {
                assertEquals(OrderStatus.ACCEPTED, actual.getStatus()); assertEquals(rider.rider().getId(), actual.getRiderId());
                assertEquals(1, statusLogs(fixture.order(), "WAIT_GRAB", "ACCEPTED"));
                error(403, () -> dispatch.serviceStart(rider.user().getId(), fixture.order().getId()));
            } else {
                assertEquals(OrderStatus.PICKING, actual.getStatus()); assertEquals(1, statusLogs(fixture.order(), "ACCEPTED", "PICKING"));
                dispatch.serviceStart(rider.user().getId(), fixture.order().getId()); dispatch.serviceComplete(rider.user().getId(), fixture.order().getId());
                dispatch.serviceComplete(rider.user().getId(), fixture.order().getId());
                assertEquals(OrderStatus.DELIVERED, orders.findById(fixture.order().getId()).orElseThrow().getStatus());
                assertEquals(1, statusLogs(fixture.order(), "PICKING", "DELIVERING")); assertEquals(1, statusLogs(fixture.order(), "DELIVERING", "DELIVERED"));
            }
        } finally { releaseFirst.countDown(); executor.shutdownNow(); }
    }

    @ParameterizedTest
    @CsvSource({"RIDE,grab", "DRIVING,grab", "RIDE,start", "DRIVING,start"})
    void revocationCommittedFirstMakesWaitingGrabOrFirstStartFailAfterRefresh(String mode, String operation) throws Exception {
        assertRevocationSerializes(mode, operation, true);
    }

    @ParameterizedTest
    @CsvSource({"RIDE,grab", "DRIVING,grab", "RIDE,start", "DRIVING,start"})
    void grabOrStartCommittedFirstBlocksRevocationAndPreservesInFlightCompletion(String mode, String operation) throws Exception {
        assertRevocationSerializes(mode, operation, false);
    }
}
