package com.feilv.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses the real projections so narrow reads cannot silently change privacy or financial fields. */
class RiderOrderReadServiceTest {
    private final DispatchService dispatch = mock(DispatchService.class);
    private final OrderRepository repository = mock(OrderRepository.class);
    private final CustomTaskRepository taskRepository = mock(CustomTaskRepository.class);
    private final CustomTaskTagRepository snapshots = mock(CustomTaskTagRepository.class);
    private final RiderRepository riders = mock(RiderRepository.class);
    private final PayRecordRepository payments = mock(PayRecordRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private OrderService orders;
    private RiderOrderReadService reads;
    private OrderMain order;
    private Rider rider;

    @BeforeEach
    void setup() {
        UserContext.set(new UserContext.CurrentUser(42L, "RIDER", "RIDER", "rider-app"));
        orders = new OrderService(repository, mock(OrderStatusLogRepository.class), mock(OrderFeeDetailRepository.class),
                mock(PricingService.class), mock(BanWordService.class), mock(MessageService.class), riders,
                mock(UserProfileService.class), mock(WalletAccountingService.class), taskRepository, payments);
        var tasks = new CustomTaskService(taskRepository, snapshots, mock(TaskTagRepository.class), repository,
                users, orders, mock(BanWordService.class), mock(AuditService.class));
        reads = new RiderOrderReadService(dispatch, orders, tasks);
        rider = new Rider(); rider.setId(7L); rider.setUserId(42L); rider.setName("承运骑手");
        when(dispatch.requireActiveRider(42L)).thenReturn(rider);
        when(riders.findByUserId(42L)).thenReturn(Optional.of(rider));
        order = new OrderMain(); order.setId(99L); order.setUserId(10L); order.setRiderId(7L);
        order.setOrderNo("O-99"); order.setOrderType("BUY"); order.setStatus(OrderStatus.ACCEPTED);
        order.setFromAddress("起点"); order.setToAddress("终点"); order.setGoodsDesc("购买物品");
        order.setEstimateFee(2000); order.setRealFee(1800); order.setGoodsBudgetFen(3500); order.setPickupCode("1234");
        order.setFromContact("不属于OrderVO的联系人"); order.setFromPhone("13800138000");
        when(repository.findById(99L)).thenReturn(Optional.of(order));
    }

    @AfterEach
    void clearContext() { UserContext.clear(); }

    @Test
    void actualCarrierReceivesExistingNineteenFieldOrderProjectionNeverOwnerCapabilities() {
        var detail = reads.detailForRider(42L, 99L);
        assertEquals(19, detail.getClass().getRecordComponents().length);
        assertEquals("BUY", detail.orderType()); assertEquals(2000, detail.estimateFeeFen());
        assertEquals(1800, detail.realFeeFen()); assertEquals(3500, detail.goodsBudgetFen());
        assertEquals("1234", detail.pickupCode()); assertFalse(detail.owner());
        var json = new ObjectMapper().valueToTree(detail);
        assertEquals(19, json.size()); assertFalse(json.has("fromPhone")); assertFalse(json.has("fromContact"));
        // Even a historical self-order never turns the rider-only projection into an owner UI.
        order.setUserId(42L);
        assertFalse(reads.detailForRider(42L, 99L).owner());
    }

    @Test
    void orderOwnerAndNonCarrierCannotUseRiderDetailsOrReadTaskSnapshots() {
        order.setUserId(42L); order.setRiderId(8L);
        assertCode(403, () -> reads.detailForRider(42L, 99L));
        assertCode(403, () -> reads.taskForRider(42L, 99L));
        verifyNoInteractions(taskRepository, snapshots, payments);
        order.setRiderId(null);
        assertCode(403, () -> reads.detailForRider(42L, 99L));
    }

    @Test
    void currentAssignmentIsRecheckedAfterTransfer() {
        assertNotNull(reads.detailForRider(42L, 99L));
        order.setRiderId(8L);
        assertCode(403, () -> reads.detailForRider(42L, 99L));
    }

    @Test
    void sourceRoleCurrentAccountAndLiveRiderChecksApplyToBothRoutes() {
        UserContext.set(new UserContext.CurrentUser(42L, "RIDER", "USER", "user-app"));
        assertCode(403, () -> reads.detailForRider(42L, 99L));
        UserContext.set(new UserContext.CurrentUser(42L, "USER", "RIDER", "rider-app"));
        assertCode(403, () -> reads.taskForRider(42L, 99L));
        UserContext.set(new UserContext.CurrentUser(43L, "RIDER", "RIDER", "rider-app"));
        assertCode(403, () -> reads.detailForRider(42L, 99L));
        UserContext.set(new UserContext.CurrentUser(42L, "RIDER", "RIDER", "rider-app"));
        when(dispatch.requireActiveRider(42L)).thenThrow(BizException.of(403, "骑手已冻结"));
        assertCode(403, () -> reads.detailForRider(42L, 99L));
        assertCode(403, () -> reads.taskForRider(42L, 99L));
        verify(repository, never()).findById(anyLong());
    }

    @Test
    void carrierTaskRetainsPaidShareAndPublishedTagAndContactSnapshots() {
        order.setOrderType("CUSTOM"); order.setGoodsBudgetFen(null);
        CustomTask task = new CustomTask(); task.setId(100L); task.setOrderId(99L); task.setUserId(10L);
        task.setTitle("任务标题快照"); task.setContent("仅承接后查看的内容"); task.setTaskMode("ONSITE");
        task.setPriceFen(2000); task.setContact("联系人快照"); task.setPhone("13900139000");
        task.setLocationAddress("服务地点快照"); task.setServiceTime("服务时间快照");
        task.setPublishSource("USER"); task.setCreatedBy(10L);
        CustomTaskTag tag = new CustomTaskTag(); tag.setId(101L); tag.setTaskId(100L); tag.setTagId(55L); tag.setTagName("历史标签快照");
        when(taskRepository.findByOrderId(99L)).thenReturn(Optional.of(task));
        when(snapshots.findByTaskIdInOrderByIdAsc(List.of(100L))).thenReturn(List.of(tag));
        when(payments.sumSuccessfulMockShareByOrderIds(List.of(99L)))
                .thenReturn(java.util.Collections.singletonList(new Object[]{99L, 1260L}));
        var detail = reads.taskForRider(42L, 99L);
        assertEquals(2000, detail.priceFen()); assertEquals(1260, detail.riderShareFen());
        assertEquals(740, detail.platformShareFen()); assertEquals("历史标签快照", detail.tags().get(0).name());
        assertEquals("联系人快照", detail.contact()); assertEquals("13900139000", detail.phone());
        assertEquals("服务地点快照", detail.locationAddress()); assertEquals("服务时间快照", detail.serviceTime());
        assertEquals("", reads.detailForRider(42L, 99L).pickupCode());
    }

    private static void assertCode(int code, Runnable call) {
        assertEquals(code, assertThrows(BizException.class, call::run).getCode());
    }
}
