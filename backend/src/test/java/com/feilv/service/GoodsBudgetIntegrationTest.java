package com.feilv.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.BizException;
import com.feilv.domain.OrderMain;
import com.feilv.domain.Rider;
import com.feilv.domain.User;
import com.feilv.dto.AdminOrderVO;
import com.feilv.dto.ApiDtos.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import com.feilv.util.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:goods-budget;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
@Transactional
class GoodsBudgetIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager entityManager;
    @Autowired OrderService service;
    @Autowired UserRepository users;
    @Autowired RiderRepository riders;
    @Autowired OrderRepository orders;
    @Autowired OrderFeeDetailRepository fees;
    @Autowired OrderStatusLogRepository logs;
    @Autowired PayRecordRepository payments;
    @Autowired WalletAccountRepository walletAccounts;
    @Autowired WalletOrderSettlementRepository settlements;
    @MockBean PricingService pricing;

    @BeforeEach void fixedDeliveryFee() {
        when(pricing.quote(anyInt(), anyInt())).thenReturn(new QuoteVO(500, List.of(new FeeItem("BASE", "配送费", 500))));
    }

    @Test void strictBuyJsonRejectsMissingNullCoercionAndOutOfRangeWithoutWrites() throws Exception {
        User publisher = user("USER", "13800138601");
        var body = requestBody("BUY");
        assertEquals(400, postBody("/api/v1/orders", publisher, body).path("code").asInt());
        String base = json.writeValueAsString(body);
        for (String value : List.of("null", "0", "-1", "100000001", "2147483648", "999999999999999999999",
                "1.0", "1.5", "1e2", "\"1\"", "true", "false", "[]", "{}")) {
            String raw = base.substring(0, base.length() - 1) + ",\"goodsBudgetFen\":" + value + "}";
            mvc.perform(post("/api/v1/orders").header("Authorization", token(publisher))
                            .contentType(MediaType.APPLICATION_JSON).content(raw))
                    .andExpect(jsonPath("$.code").value(400));
        }
        assertEquals(0, orders.count()); assertEquals(0, fees.count()); assertEquals(0, logs.count()); assertEquals(0, payments.count());
        verifyNoInteractions(pricing);
    }

    @Test void serviceRejectsInvalidBudgetForBuyAndAnyNonNullBudgetForEveryOtherType() {
        User publisher = user("USER", "13800138602");
        for (Integer budget : new Integer[]{null, 0, -1, 100000001, Integer.MAX_VALUE}) {
            assertEquals(400, assertThrows(BizException.class, () -> service.create(publisher.getId(), request("BUY", budget))).getCode());
        }
        for (String type : List.of("SEND", "ERRAND")) {
            assertEquals(400, assertThrows(BizException.class, () -> service.create(publisher.getId(), request(type, 100))).getCode());
        }
        assertEquals(400, assertThrows(BizException.class, () -> service.createCustom(publisher.getId(), request("CUSTOM", 100), 1000)).getCode());
        OrderCreateReq legacyBuy = new OrderCreateReq("BUY", "甲", "13800138000", "起点", null, null,
                "乙", "13900139000", "终点", null, null, 1000, 1, "商品", "");
        assertNull(legacyBuy.goodsBudgetFen());
        assertEquals(400, assertThrows(BizException.class, () -> service.create(publisher.getId(), legacyBuy)).getCode());
        User unbound = user("USER", null);
        assertEquals(4091, assertThrows(BizException.class, () -> service.create(unbound.getId(), request("BUY", 100))).getCode());
        assertEquals(0, orders.count()); verifyNoInteractions(pricing);
    }

    @Test void buyBoundariesPersistAndBudgetNeverChangesDeliveryQuoteOrFeeDetails() throws Exception {
        User publisher = user("USER", "13800138603");
        var quote = postBody("/api/v1/orders/quote", publisher, Map.of("orderType", "BUY", "distanceM", 1000, "weightKg", 1));
        assertEquals(0, quote.path("code").asInt()); assertEquals(500, quote.path("data").path("totalFen").asInt());
        for (int budget : List.of(1, 100000000)) {
            var body = requestBody("BUY"); body.put("goodsBudgetFen", budget);
            var response = postBody("/api/v1/orders", publisher, body);
            assertEquals(0, response.path("code").asInt(), response.toString());
            var data = response.path("data"); assertBudget(data, budget);
            entityManager.flush(); entityManager.clear();
            var order = orders.findById(data.path("id").asLong()).orElseThrow();
            assertEquals(budget, order.getGoodsBudgetFen()); assertEquals(500, order.getEstimateFee()); assertEquals(500, order.getRealFee());
            var details = fees.findAll().stream().filter(f -> f.getOrderId().equals(order.getId())).toList();
            assertEquals(1, details.size()); assertEquals("BASE", details.get(0).getFeeType()); assertEquals(500, details.get(0).getAmount());
        }
    }

    @Test void nonBuyAcceptsOmittedOrNullBudgetButRejectsEveryNonNullBudgetWithoutInferringCustomPurchases() throws Exception {
        User publisher = user("USER", "13800138604");
        for (String type : List.of("SEND", "ERRAND")) {
            var body = requestBody(type); body.put("goodsDesc", "采购商品，预算100元");
            assertBudget(postBody("/api/v1/orders", publisher, body).path("data"), null);
            body.put("goodsBudgetFen", null);
            assertBudget(postBody("/api/v1/orders", publisher, body).path("data"), null);
        }
        long count = orders.count();
        for (String type : List.of("SEND", "ERRAND", "CUSTOM")) {
            for (Object budget : List.of(1, 100000000, "1", true)) {
                var body = requestBody(type); body.put("goodsBudgetFen", budget);
                assertEquals(400, postBody("/api/v1/orders", publisher, body).path("code").asInt());
            }
        }
        assertEquals(count, orders.count());
        var legacyCustom = historicalOrder(publisher, "CUSTOM");
        legacyCustom.setGoodsDesc("历史采购任务，预算100元"); orders.saveAndFlush(legacyCustom);
        var detail = getData("/api/v1/orders/" + legacyCustom.getId(), publisher);
        assertEquals("CUSTOM", detail.path("orderType").asText()); assertEquals("DELIVERY", detail.path("taskMode").asText()); assertBudget(detail, null);
    }

    @Test void maximumBuyBudgetReachesEveryDeliveryViewWithoutChangingPaymentSharesOrWallet() throws Exception {
        User publisher = user("USER", "13800138605"), rider = rider("13800138606");
        var body = requestBody("BUY"); body.put("goodsBudgetFen", 100000000);
        var response = postBody("/api/v1/orders", publisher, body); assertEquals(0, response.path("code").asInt());
        assertBudget(response.path("data"), 100000000);
        completeLifecycle(publisher, rider, response.path("data").path("id").asLong(), 100000000);
    }

    @Test void historicalBuyNullBudgetRemainsReadablePayableAndCompletableWithoutBackfill() throws Exception {
        User publisher = user("USER", "13800138607"), rider = rider("13800138608");
        var order = historicalOrder(publisher, "BUY"); assertNull(order.getGoodsBudgetFen());
        completeLifecycle(publisher, rider, order.getId(), null);
        assertNull(orders.findById(order.getId()).orElseThrow().getGoodsBudgetFen());
    }

    @Test void cancellationReturnsIndependentBudgetAndLeavesRecordedPaymentAmountUnchanged() throws Exception {
        User publisher = user("USER", "13800138609");
        var order = service.create(publisher.getId(), request("BUY", 12345));
        assertEquals(0, postBody("/api/v1/orders/" + order.id() + "/pay", publisher, Map.of()).path("code").asInt());
        var cancelled = postBody("/api/v1/orders/" + order.id() + "/cancel", publisher, Map.of("reason", "取消代购"));
        assertEquals(0, cancelled.path("code").asInt()); assertBudget(cancelled.path("data"), 12345);
        assertEquals("CANCELED", cancelled.path("data").path("status").asText());
        assertEquals(500, payments.findByOrderId(order.id()).get(0).getAmount());
        assertEquals(500, orders.findById(order.id()).orElseThrow().getEstimateFee());
        assertTrue(settlements.findByOrderId(order.id()).isEmpty());
    }

    @Test void previousRecordConstructorsRemainSourceCompatibleAndNeverInventBudgets() {
        OrderVO base = new OrderVO(1, "OLD", "BUY", "WAIT_PAY", "待付款", "起点", "终点", "商品",
                500, 500, null, "1234", null, null, null, null);
        OrderVO owner = new OrderVO(1, "OLD", "BUY", "WAIT_PAY", "待付款", "起点", "终点", "商品",
                500, 500, null, "1234", null, null, null, null, true);
        OrderVO serviceTask = new OrderVO(1, "OLD", "CUSTOM", "WAIT_PAY", "待付款", "起点", "终点", "任务",
                500, 500, null, "", null, null, null, null, true, "ONLINE");
        assertNull(base.goodsBudgetFen()); assertNull(owner.goodsBudgetFen()); assertNull(serviceTask.goodsBudgetFen());
        assertFalse(base.owner()); assertTrue(owner.owner()); assertEquals("DELIVERY", owner.taskMode()); assertEquals("ONLINE", serviceTask.taskMode());
        GrabVO oldGrab = new GrabVO(1, "OLD", "BUY", "起点", "终点", 1000, 350, "");
        GrabVO taskGrab = new GrabVO(1, "OLD", "CUSTOM", "起点", "终点", 1000, 350, "", "任务", "ONLINE", "", "");
        assertNull(oldGrab.goodsBudgetFen()); assertNull(taskGrab.goodsBudgetFen());
        AdminOrderVO admin = new AdminOrderVO(1, "OLD", "BUY", "WAIT_PAY", "待付款", "起点", "终点", "商品",
                500, 500, null, "1234", null, null, null, null, 2L, "用户", "", "", false, "DELIVERY");
        assertNull(admin.goodsBudgetFen()); assertNull(AdminOrderVO.from(base, 2L, null).goodsBudgetFen());
    }

    private void completeLifecycle(User publisher, User rider, long id, Integer budget) throws Exception {
        assertBudget(getData("/api/v1/orders/" + id, publisher), budget);
        assertBudget(find(getData("/api/v1/orders", publisher).path("content"), "id", id), budget);
        assertEquals(0, getData("/api/v1/grab/list", rider).size());
        var paid = postBody("/api/v1/orders/" + id + "/pay", publisher, Map.of());
        assertEquals(0, paid.path("code").asInt()); assertEquals(500, paid.path("data").path("amountFen").asInt());
        var payment = payments.findByOrderId(id).get(0);
        assertEquals(500, payment.getAmount()); assertEquals(350, payment.getShareRider()); assertEquals(150, payment.getSharePlatform());
        var hall = find(getData("/api/v1/grab/list", rider), "orderId", id);
        assertBudget(hall, budget); assertEquals(350, hall.path("rewardFen").asInt());
        assertFalse(hall.has("fromPhone")); assertFalse(hall.has("toPhone"));
        assertEquals(0, postBody("/api/v1/grab/" + id, rider, Map.of()).path("code").asInt());
        var riderOrder = find(getData("/api/v1/rider/orders", rider).path("content"), "id", id);
        assertBudget(riderOrder, budget); assertEquals(350, riderOrder.path("rewardFen").asInt());
        assertBudget(getData("/api/v1/orders/" + id, rider), budget);
        String pickupCode = orders.findById(id).orElseThrow().getPickupCode();
        assertEquals(0, postBody("/api/v1/orders/" + id + "/pickup", rider, Map.of("pickupCode", pickupCode)).path("code").asInt());
        for (String action : List.of("depart", "deliver")) {
            assertEquals(0, postBody("/api/v1/orders/" + id + "/" + action, rider, Map.of()).path("code").asInt());
        }
        for (int i = 0; i < 2; i++) {
            var confirmed = postBody("/api/v1/orders/" + id + "/confirm", publisher, Map.of());
            assertEquals(0, confirmed.path("code").asInt()); assertEquals("FINISHED", confirmed.path("data").path("status").asText());
            assertBudget(confirmed.path("data"), budget);
        }
        assertEquals(OrderStatus.FINISHED, orders.findById(id).orElseThrow().getStatus());
        assertEquals(350, settlements.findByOrderId(id).orElseThrow().getGrossFen());
        assertEquals(350, walletAccounts.findById(rider.getId()).orElseThrow().getAvailableFen());
        assertTrue(walletAccounts.findById(publisher.getId()).isEmpty());
        assertEquals(1, payments.findByOrderId(id).size());
    }

    private void assertBudget(JsonNode row, Integer budget) {
        assertTrue(row.has("goodsBudgetFen"), row.toString());
        if (budget == null) assertTrue(row.path("goodsBudgetFen").isNull(), row.toString());
        else assertEquals(budget.intValue(), row.path("goodsBudgetFen").asInt());
    }

    private JsonNode find(JsonNode rows, String key, long id) {
        for (JsonNode row : rows) if (row.path(key).asLong() == id) return row;
        fail("Missing order " + id + " in " + rows); return null;
    }

    private OrderCreateReq request(String type, Integer budget) {
        return new OrderCreateReq(type, "甲", "13800138000", "起点", null, null,
                "乙", "13900139000", "终点", null, null, 1000, 1, "商品", "", budget);
    }

    private Map<String, Object> requestBody(String type) {
        return new LinkedHashMap<>(Map.of("orderType", type, "fromContact", "甲", "fromPhone", "13800138000", "fromAddress", "起点",
                "toContact", "乙", "toPhone", "13900139000", "toAddress", "终点", "distanceM", 1000, "weightKg", 1));
    }

    private OrderMain historicalOrder(User publisher, String type) {
        OrderMain order = new OrderMain(); order.setOrderNo("LEGACY-" + publisher.getId()); order.setOrderType(type); order.setUserId(publisher.getId());
        order.setFromContact("甲"); order.setFromPhone("13800138000"); order.setFromAddress("起点");
        order.setToContact("乙"); order.setToPhone("13900139000"); order.setToAddress("终点");
        order.setGoodsDesc("历史商品"); order.setEstimateFee(500); order.setRealFee(500); order.setPickupCode("1234");
        return orders.saveAndFlush(order);
    }

    private User user(String role, String mobile) {
        User user = new User(); user.setRole(role); user.setMobile(mobile); return users.saveAndFlush(user);
    }

    private User rider(String mobile) {
        User user = user("RIDER", mobile);
        Rider rider = new Rider(); rider.setUserId(user.getId()); rider.setName("预算测试骑手"); rider.setOnlineStatus(1); riders.saveAndFlush(rider);
        return user;
    }

    private String token(User user) { return "Bearer " + JwtUtil.create(user.getId(), user.getRole()); }

    private JsonNode postBody(String path, User user, Object body) throws Exception {
        return json.readTree(mvc.perform(post(path).header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode getData(String path, User user) throws Exception {
        var result = mvc.perform(get(path).header("Authorization", token(user))).andExpect(jsonPath("$.code").value(0)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
}
