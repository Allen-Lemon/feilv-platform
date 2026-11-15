package com.feilv.controller;

import com.feilv.domain.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import com.feilv.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实鉴权、H2事务和JSON契约；仅本地内存数据库，不调用任何资金渠道。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:complaint-http;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "feilv.wechat.app-id=", "feilv.wechat.app-secret="})
@AutoConfigureMockMvc
@Transactional
class ComplaintHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RiderRepository riders;
    @Autowired OrderRepository orders;
    @Autowired ComplaintRepository complaints;
    @Autowired ComplaintDeductionRepository deductions;
    @Autowired PayRecordRepository payments;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminUserRoleRepository userRoles;
    @Autowired AdminMenuRepository menus;
    @Autowired AdminRoleMenuRepository roleMenus;
    @Autowired EntityManager em;
    // 此套件隔离既有投诉JSON契约；钱包与真实完成/投诉整合由OrderWalletIntegrationTest覆盖。
    @org.springframework.boot.test.mock.mockito.MockBean com.feilv.service.WalletAccountingService wallet;
    User publisher, riderUser, outsider, admin;
    Rider rider;
    AdminMenu complaintMenu, settlementMenu;
    String adminToken, publisherToken, riderToken, outsiderToken;

    @BeforeEach
    void setup() {
        publisher = user("发布者", "USER"); riderUser = user("骑手账户", "RIDER"); outsider = user("其他用户", "USER");
        rider = new Rider(); rider.setUserId(riderUser.getId()); rider.setName("骑手"); rider = riders.saveAndFlush(rider);
        admin = user("投诉审核员", "ADMIN");
        var account = new AdminAccount(); account.setUserId(admin.getId()); account.setUsername("complaint-test-admin");
        account.setPasswordHash("unused-password-hash"); accounts.saveAndFlush(account);
        var role = new AdminRole(); role.setCode("COMPLAINT_TEST"); role.setName("投诉审核"); role = roles.saveAndFlush(role);
        var userRole = new AdminUserRole(); userRole.setUserId(admin.getId()); userRole.setRoleId(role.getId()); userRoles.saveAndFlush(userRole);
        complaintMenu = grant("complaints", role.getId()); settlementMenu = grant("settlements", role.getId());
        adminToken = token(admin); publisherToken = token(publisher); riderToken = token(riderUser); outsiderToken = token(outsider);
    }

    @Test
    void fullFlowCumulativeDeductionSettlementAndOriginalPaymentRemainIntact() throws Exception {
        OrderMain order = order(OrderStatus.FINISHED, "CUSTOM");
        PayRecord original = payment(order, "MOCK", "SUCCESS", 800);
        payment(order, "MOCK", "FAILED", 3000); payment(order, "WECHAT", "SUCCESS", 4000);
        long first = create(order);
        JsonNode processing = review(first, "PROCESSING", "客服受理", "0", 0);
        assertEquals("PROCESSING", processing.path("status").asText());
        JsonNode approved = review(first, "APPROVED", "投诉成立，联系13800138000", "300", 0);
        assertEquals("APPROVED", approved.path("status").asText());
        assertEquals(300, approved.path("compensationFen").asInt()); assertEquals(300, approved.path("deductedFen").asLong());
        assertEquals(300, approved.path("orderDeductedFen").asLong()); assertEquals(500, approved.path("maxDeductibleFen").asLong());
        assertEquals("MOCK", approved.path("paymentChannel").asText());
        assertFalse(approved.path("createdTime").isNull()); assertFalse(approved.path("handledTime").isNull());
        assertEquals(admin.getId().longValue(), approved.path("handledBy").asLong());
        assertEquals(3, approved.path("actions").size()); assertTrue(approved.toString().contains("MOCK账务模拟"));
        assertFalse(approved.toString().contains("13800138000")); assertTrue(approved.toString().contains("138****8000"));
        review(first, "APPROVED", "再次审批", "300", 4090);
        handle(first, "DONE", "旧接口重复", "0", 4090);
        long second = create(order);
        review(second, "APPROVED", "超过剩余额度", "501", 4090);
        assertEquals("OPEN", complaints.findById(second).orElseThrow().getStatus());
        JsonNode secondResult = review(second, "APPROVED", "余额内赔付", "500", 0);
        assertEquals(800, secondResult.path("orderDeductedFen").asLong()); assertEquals(0, secondResult.path("maxDeductibleFen").asLong());
        long third = create(order); review(third, "APPROVED", "额度耗尽", "1", 4090);
        review(third, "REJECTED", "证据不足", "0", 0);
        JsonNode mine = data(mvc.perform(get("/api/v1/orders/" + order.getId() + "/complaints").header("Authorization", publisherToken))
                .andExpect(jsonPath("$.code").value(0)).andReturn());
        assertEquals(3, mine.size()); assertEquals(third, mine.get(0).path("id").asLong());
        assertEquals(800, mine.get(2).path("orderDeductedFen").asLong()); assertEquals(300, mine.get(2).path("deductedFen").asLong());
        mvc.perform(get("/api/v1/orders/" + order.getId() + "/complaints").header("Authorization", riderToken))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data[0].status").value("REJECTED"));
        JsonNode settlements = data(mvc.perform(get("/api/v1/admin/settlements").header("Authorization", adminToken))
                .andExpect(jsonPath("$.code").value(0)).andReturn());
        JsonNode row = null; for (JsonNode candidate : settlements) if (candidate.path("riderId").asLong() == rider.getId()) row = candidate;
        assertNotNull(row); assertEquals(800, row.path("totalShareFen").asLong()); assertEquals("8.00", row.path("totalShareYuan").asText());
        assertEquals(800, row.path("deductedFen").asLong()); assertEquals(0, row.path("netShareFen").asLong()); assertEquals(800, row.path("compensationFen").asLong());
        em.flush(); em.clear();
        PayRecord unchanged = payments.findById(original.getId()).orElseThrow();
        assertEquals(800, unchanged.getShareRider()); assertEquals(1000, unchanged.getAmount());
        assertEquals(200, unchanged.getSharePlatform()); assertEquals("SUCCESS", unchanged.getStatus());
        assertEquals("MOCK", unchanged.getChannel()); assertEquals(original.getPayNo(), unchanged.getPayNo());
        assertEquals(2, deductions.findByOrderIdIn(List.of(order.getId())).size());
    }

    @Test
    void ownershipCreationStatesDescriptionsAndOneActiveTicket() throws Exception {
        OrderMain accepted = order(OrderStatus.ACCEPTED, "SEND");
        mvc.perform(post("/api/v1/complaints").header("Authorization", outsiderToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(accepted.getId(), "问题"))).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/v1/complaints").header("Authorization", riderToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(accepted.getId(), "问题"))).andExpect(jsonPath("$.code").value(403));
        long id = create(accepted);
        mvc.perform(post("/api/v1/complaints").header("Authorization", publisherToken).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(accepted.getId(), "重复工单"))).andExpect(jsonPath("$.code").value(4090));
        mvc.perform(get("/api/v1/orders/" + accepted.getId() + "/complaints").header("Authorization", outsiderToken))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/v1/orders/" + accepted.getId() + "/complaints").header("Authorization", adminToken))
                .andExpect(jsonPath("$.code").value(403));
        payment(accepted, "MOCK", "SUCCESS", 800); review(id, "APPROVED", "未送达不能扣款", "1", 4090);
        assertEquals(0, deductions.findByOrderIdIn(List.of(accepted.getId())).size());
        review(id, "APPROVED", "零元成立", "0", 0);
        for (OrderStatus state : List.of(OrderStatus.WAIT_PAY, OrderStatus.WAIT_GRAB, OrderStatus.CANCELED)) {
            OrderMain invalid = order(state, "SEND");
            mvc.perform(post("/api/v1/complaints").header("Authorization", publisherToken).contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(invalid.getId(), "不可投诉"))).andExpect(jsonPath("$.code").value(4090));
        }
        for (OrderStatus state : List.of(OrderStatus.PICKING, OrderStatus.DELIVERING, OrderStatus.DELIVERED)) create(order(state, "SEND"));
        for (String description : List.of("", " ", "x".repeat(256))) {
            mvc.perform(post("/api/v1/complaints").header("Authorization", publisherToken).contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(accepted.getId(), description))).andExpect(jsonPath("$.code").value(400));
        }
        mvc.perform(post("/api/v1/complaints").header("Authorization", publisherToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + accepted.getId() + ",\"type\":\"OTHER\"}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void noPaymentNoRiderAndNonMockNeverDeductButZeroDecisionAllowed() throws Exception {
        OrderMain noPayment = order(OrderStatus.DELIVERED, "SEND"); long first = create(noPayment);
        review(first, "APPROVED", "缺少成功MOCK支付", "1", 4090);
        review(first, "APPROVED", "无需赔付", "0", 0);
        OrderMain noRider = order(OrderStatus.FINISHED, "SEND"); noRider.setRiderId(null); orders.saveAndFlush(noRider);
        payment(noRider, "MOCK", "SUCCESS", 800); long second = create(noRider);
        review(second, "APPROVED", "缺少骑手", "1", 4090); review(second, "REJECTED", "驳回", "0", 0);
        OrderMain realPayment = order(OrderStatus.FINISHED, "SEND"); payment(realPayment, "WECHAT", "SUCCESS", 800);
        long third = create(realPayment); JsonNode state = review(third, "PROCESSING", "先受理", "0", 0);
        assertTrue(state.path("paymentChannel").isNull()); assertEquals(0, state.path("maxDeductibleFen").asLong());
        review(third, "APPROVED", "非MOCK不可扣", "1", 4090);
        review(third, "REJECTED", "无法扣款", "0", 0);
    }

    @Test
    void strictIntegerReasonDecisionAndSafeLegacyHandle() throws Exception {
        OrderMain order = order(OrderStatus.FINISHED, "SEND"); payment(order, "MOCK", "SUCCESS", 800); long id = create(order);
        for (String value : List.of("-1", "1.5", "1.0", "\"1\"", "true", "2147483648", "null")) {
            review(id, "APPROVED", "参数错误", value, 400);
            if (!"null".equals(value)) handle(id, "DONE", "旧金额参数错误", value, 400);
        }
        review(id, "REJECTED", "驳回不能带钱", "1", 400);
        review(id, "PROCESSING", "受理不能带钱", "1", 400);
        review(id, "APPROVED", " ", "0", 400); review(id, "APPROVED", "x".repeat(256), "0", 400);
        review(id, "DONE", "旧状态不能用于审核", "0", 400);
        mvc.perform(post("/api/v1/admin/complaints/" + id + "/review").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\",\"reason\":\"缺少金额\"}"))
                .andExpect(jsonPath("$.code").value(400));
        handle(id, "DONE", "旧接口禁止钱", "1", 400); handle(id, "APPROVED", "旧接口不可成立", "0", 400);
        mvc.perform(post("/api/v1/admin/complaints/" + id + "/handle").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\",\"result\":\"绕过金额\",\"amountFen\":1}"))
                .andExpect(jsonPath("$.code").value(400));
        handle(id, "PROCESSING", "旧受理", "0", 0); handle(id, "DONE", "零元旧办结", "0", 0);
        review(id, "APPROVED", "不可重新打开", "1", 4090);
        assertEquals(0, complaints.findById(id).orElseThrow().getCompensationFen());
        assertTrue(deductions.findByOrderIdIn(List.of(order.getId())).isEmpty());
    }

    @Test
    void pagedAdminFiltersStableOrderingAndMenuGuardsIncludeLegacyAndSettlements() throws Exception {
        long latest = 0;
        for (int i = 0; i < 17; i++) latest = create(order(OrderStatus.ACCEPTED, "SEND"));
        JsonNode first = adminList(""), second = adminList("?page=1");
        assertEquals(15, first.path("size").asInt()); assertEquals(17, first.path("totalElements").asLong());
        assertEquals(latest, first.path("content").get(0).path("id").asLong()); assertEquals(2, second.path("content").size());
        long previous = Long.MAX_VALUE;
        for (JsonNode row : first.path("content")) { assertTrue(row.path("id").asLong() < previous); previous = row.path("id").asLong(); }
        for (JsonNode row : second.path("content")) { assertTrue(row.path("id").asLong() < previous); previous = row.path("id").asLong(); }
        long selectedOrder = first.path("content").get(0).path("orderId").asLong();
        assertEquals(1, adminList("?status=&orderId=" + selectedOrder).path("totalElements").asLong());
        review(latest, "REJECTED", "终审驳回", "0", 0);
        assertEquals(1, adminList("?status=REJECTED").path("totalElements").asLong());
        assertEquals(17, adminList("").path("totalElements").asLong());
        for (String query : List.of("?page=-1", "?page=abc", "?orderId=-1", "?orderId=abc", "?status=INVALID")) {
            mvc.perform(get("/api/v1/admin/complaints" + query).header("Authorization", adminToken)).andExpect(jsonPath("$.code").value(400));
        }
        complaintMenu.setEnabled(0); menus.saveAndFlush(complaintMenu);
        mvc.perform(get("/api/v1/admin/complaints").header("Authorization", adminToken)).andExpect(jsonPath("$.code").value(403));
        review(latest, "APPROVED", "无权限", "0", 403); handle(latest, "DONE", "无权限", "0", 403);
        mvc.perform(get("/api/v1/admin/settlements").header("Authorization", adminToken)).andExpect(jsonPath("$.code").value(0));
        settlementMenu.setEnabled(0); menus.saveAndFlush(settlementMenu);
        mvc.perform(get("/api/v1/admin/settlements").header("Authorization", adminToken)).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/v1/admin/complaints").header("Authorization", publisherToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/complaints")).andExpect(status().isUnauthorized());
    }

    private JsonNode adminList(String query) throws Exception {
        return data(mvc.perform(get("/api/v1/admin/complaints" + query).header("Authorization", adminToken))
                .andExpect(jsonPath("$.code").value(0)).andReturn());
    }
    private long create(OrderMain order) throws Exception {
        JsonNode result = data(mvc.perform(post("/api/v1/complaints").header("Authorization", publisherToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(order.getId(), "配送问题，电话13800138000")))
                .andExpect(jsonPath("$.code").value(0)).andReturn());
        assertEquals(2, result.size()); assertEquals("一般投诉48小时内办结", result.path("sla").asText());
        return result.path("ticketId").asLong();
    }
    private String createBody(long id, String description) throws Exception {
        return json.writeValueAsString(java.util.Map.of("orderId", id, "type", "OTHER", "description", description));
    }
    private JsonNode review(long id, String decision, String reason, String amountJson, int expected) throws Exception {
        return data(mvc.perform(post("/api/v1/admin/complaints/" + id + "/review").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":" + json.writeValueAsString(decision)
                                + ",\"reason\":" + json.writeValueAsString(reason) + ",\"amountFen\":" + amountJson + "}"))
                .andExpect(jsonPath("$.code").value(expected)).andReturn());
    }
    private void handle(long id, String state, String result, String amountJson, int expected) throws Exception {
        mvc.perform(post("/api/v1/admin/complaints/" + id + "/handle").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":" + json.writeValueAsString(state)
                                + ",\"result\":" + json.writeValueAsString(result) + ",\"compensationFen\":" + amountJson + "}"))
                .andExpect(jsonPath("$.code").value(expected));
    }
    private JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()).path("data"); }
    private User user(String name, String role) {
        var user = new User(); user.setNickname(name); user.setRole(role); return users.saveAndFlush(user);
    }
    private String token(User user) { return "Bearer " + JwtUtil.create(user.getId(), user.getRole()); }
    private AdminMenu grant(String code, long roleId) {
        var menu = new AdminMenu(); menu.setCode(code); menu.setName(code); menu = menus.saveAndFlush(menu);
        var link = new AdminRoleMenu(); link.setRoleId(roleId); link.setMenuId(menu.getId()); roleMenus.saveAndFlush(link); return menu;
    }
    private OrderMain order(OrderStatus status, String type) {
        var order = new OrderMain(); order.setOrderNo("COMPLAINT-" + java.util.UUID.randomUUID()); order.setOrderType(type);
        order.setUserId(publisher.getId()); order.setRiderId(rider.getId()); order.setStatus(status);
        order.setFromContact("寄件人"); order.setFromPhone("13800138000"); order.setFromAddress("起点");
        order.setToContact("收件人"); order.setToPhone("13900139000"); order.setToAddress("终点");
        order.setEstimateFee(1000); order.setRealFee(1000); return orders.saveAndFlush(order);
    }
    private PayRecord payment(OrderMain order, String channel, String status, int share) {
        var pay = new PayRecord(); pay.setPayNo("C-PAY-" + java.util.UUID.randomUUID()); pay.setOrderId(order.getId());
        pay.setChannel(channel); pay.setStatus(status); pay.setAmount(share + 200); pay.setShareRider(share); pay.setSharePlatform(200);
        return payments.saveAndFlush(pay);
    }
}
