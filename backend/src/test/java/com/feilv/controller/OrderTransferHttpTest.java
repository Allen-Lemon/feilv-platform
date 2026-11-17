package com.feilv.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.domain.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import com.feilv.service.DispatchService;
import com.feilv.service.OrderTransferService;
import com.feilv.dto.OrderTransferDtos.TransferReq;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {TestAuth.TEST_SECRET_PROPERTY,"spring.datasource.url=jdbc:h2:mem:order-transfer-http;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class OrderTransferHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RiderRepository riders;
    @Autowired OrderRepository orders;
    @Autowired PayRecordRepository payments;
    @Autowired OrderTransferRepository transfers;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminUserRoleRepository userRoles;
    @Autowired AdminMenuRepository menus;
    @Autowired AdminRoleMenuRepository roleMenus;
    @Autowired CustomTaskRepository tasks;
    @Autowired OrderTransferService service;
    @Autowired DispatchService dispatch;
    private static final AtomicLong sequence = new AtomicLong(7000);
    private record Fixture(User publisher, User fromUser, Rider from, User toUser, Rider to, OrderMain order) {}
    private User user(String role) {
        User user = new User(); user.setRole(role); user.setMobile("139" + String.format("%08d", sequence.incrementAndGet())); user.setNickname("转派HTTP用户");
        return users.saveAndFlush(user);
    }
    private Rider rider(User user) {
        Rider rider = new Rider(); rider.setUserId(user.getId()); rider.setName("HTTP骑手"); rider.setOnlineStatus(1); return riders.saveAndFlush(rider);
    }
    private Fixture fixture() {
        User publisher = user("USER"), fromUser = user("RIDER"), toUser = user("RIDER"); Rider from = rider(fromUser), to = rider(toUser);
        OrderMain order = new OrderMain(); order.setOrderNo("TRANSFER-HTTP-" + sequence.incrementAndGet()); order.setOrderType("CUSTOM"); order.setUserId(publisher.getId()); order.setRiderId(from.getId());
        order.setFromContact("机密联系人"); order.setFromPhone("13800138000"); order.setFromAddress("机密上门地址");
        order.setToContact("机密收件人"); order.setToPhone("13900139000"); order.setToAddress("机密终点地址");
        order.setStatus(OrderStatus.ACCEPTED); order.setPickupCode(""); order.setAcceptedTime(LocalDateTime.now()); order.setEstimateFee(1000); order.setRealFee(1000); orders.saveAndFlush(order);
        CustomTask task = new CustomTask(); task.setOrderId(order.getId()); task.setUserId(publisher.getId()); task.setTitle("可见标题"); task.setContent("机密内容"); task.setTaskMode("ONSITE"); task.setLocationAddress("机密服务地址");
        task.setContact("机密联系人"); task.setPhone("13800138000"); task.setPublishSource("MINIAPP"); task.setCreatedBy(publisher.getId()); tasks.saveAndFlush(task);
        PayRecord pay = new PayRecord(); pay.setOrderId(order.getId()); pay.setPayNo("TRANSFER-HTTP-PAY-" + sequence.incrementAndGet());
        pay.setStatus("SUCCESS"); pay.setChannel("MOCK"); pay.setAmount(1000); pay.setShareRider(700); pay.setSharePlatform(300); payments.saveAndFlush(pay);
        return new Fixture(publisher, fromUser, from, toUser, to, order);
    }
    private String token(User user) { return TestAuth.bearer(user); }
    private JsonNode call(MockHttpServletRequestBuilder request, User user) throws Exception {
        String path = request.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI();
        boolean riderEndpoint = path.startsWith("/api/v1/rider/")
                || path.matches("^/api/v1/orders/[^/]+/(service-start|service-complete)$");
        String authorization = riderEndpoint ? TestAuth.riderBearer(user) : token(user);
        return json.readTree(mvc.perform(request.header("Authorization", authorization)).andReturn().getResponse().getContentAsByteArray());
    }
    private JsonNode postJson(String path, User user, String body) throws Exception {
        return call(post(path).contentType(MediaType.APPLICATION_JSON).content(body), user);
    }
    private String createPath(Fixture f) { return "/api/v1/rider/orders/" + f.order.getId() + "/transfers"; }
    private String responsePath(long id) { return "/api/v1/rider/transfers/" + id + "/respond"; }
    private long request(Fixture f) throws Exception {
        var result = postJson(createPath(f), f.fromUser, "{\"targetRiderId\":" + f.to.getId() + ",\"reason\":\"时间冲突\"}");
        assertEquals(0, result.path("code").asInt(), result.toString()); return result.path("data").path("id").asLong();
    }
    private User admin(boolean granted, boolean unrelatedMenu) {
        User user = user("ADMIN"); AdminAccount account = new AdminAccount(); account.setUserId(user.getId()); account.setUsername("transfer-admin-" + user.getId()); account.setPasswordHash("test"); accounts.saveAndFlush(account);
        AdminRole role = new AdminRole(); role.setCode("TRANSFER_" + user.getId()); role.setName("转派查看"); roles.saveAndFlush(role);
        AdminUserRole userRole = new AdminUserRole(); userRole.setUserId(user.getId()); userRole.setRoleId(role.getId()); userRoles.saveAndFlush(userRole);
        if (granted || unrelatedMenu) {
            String code = unrelatedMenu ? "users" : "orders";
            AdminMenu menu = menus.findAll().stream().filter(m -> code.equals(m.getCode())).findFirst().orElseGet(() -> {
                AdminMenu created = new AdminMenu(); created.setCode(code); created.setName("管理菜单"); return menus.saveAndFlush(created);
            });
            AdminRoleMenu grant = new AdminRoleMenu(); grant.setRoleId(role.getId()); grant.setMenuId(menu.getId()); roleMenus.saveAndFlush(grant);
        }
        return user;
    }

    @Test void targetIdRequiresStrictPositiveJsonLongAndReasonsEnforceLimitsWithoutAcceptingMassAssignment() throws Exception {
        Fixture f = fixture();
        for (String target : List.of("0", "-1", "1.0", "1.5", "1e2", "\"1\"", "true", "false", "null", "9223372036854775808", "[]", "{}")) {
            assertEquals(400, postJson(createPath(f), f.fromUser, "{\"targetRiderId\":" + target + ",\"reason\":\"有效原因\"}").path("code").asInt(), target);
        }
        for (String body : List.of("{}", "{\"reason\":\"缺ID\"}", "{\"targetRiderId\":" + f.to.getId() + "}",
                "{\"targetRiderId\":" + f.to.getId() + ",\"reason\":\" \"}", "{\"targetRiderId\":" + f.to.getId() + ",\"reason\":\"" + "长".repeat(256) + "\"}",
                "{\"targetRiderId\":" + f.to.getId() + ",\"reason\":\"有效\",\"riderId\":1}")) {
            assertEquals(400, postJson(createPath(f), f.fromUser, body).path("code").asInt(), body);
        }
        assertTrue(transfers.findByOrderIdOrderByIdDesc(f.order.getId()).isEmpty());
        long id = request(f);
        for (String body : List.of("{}", "{\"decision\":\"PENDING\"}", "{\"decision\":\"accepted\"}", "{\"decision\":\"ACCEPTED\",\"reason\":\"" + "长".repeat(256) + "\"}", "{\"decision\":\"ACCEPTED\",\"targetRiderId\":1}")) {
            assertEquals(400, postJson(responsePath(id), f.toUser, body).path("code").asInt(), body);
        }
        assertEquals(400, postJson("/api/v1/rider/transfers/" + id + "/cancel", f.fromUser, "{\"reason\":\"" + "长".repeat(256) + "\"}").path("code").asInt());
        assertEquals("PENDING", transfers.findById(id).orElseThrow().getStatus().name());
        assertEquals(0, postJson(responsePath(id), f.toUser, "{\"decision\":\"ACCEPTED\"}").path("code").asInt());
    }

    @Test void allRiderMutationsUseRealRoleAndOwnershipAndFrozenAccountCannotUseStaleJwt() throws Exception {
        Fixture f = fixture(); User ordinary = user("USER"); rider(ordinary); User stranger = user("RIDER"); rider(stranger);
        assertEquals(403, postJson(createPath(f), ordinary, "{\"targetRiderId\":" + f.to.getId() + ",\"reason\":\"普通用户\"}").path("code").asInt());
        assertEquals(403, postJson(createPath(f), stranger, "{\"targetRiderId\":" + f.to.getId() + ",\"reason\":\"非owner\"}").path("code").asInt());
        long id = request(f);
        assertEquals(403, postJson(responsePath(id), f.fromUser, "{\"decision\":\"ACCEPTED\"}").path("code").asInt());
        assertEquals(403, postJson(responsePath(id), stranger, "{\"decision\":\"REJECTED\"}").path("code").asInt());
        assertEquals(403, postJson("/api/v1/rider/transfers/" + id + "/cancel", f.toUser, "{}").path("code").asInt());
        String stale = TestAuth.riderBearer(f.toUser); f.toUser.setStatus(0); users.saveAndFlush(f.toUser);
        mvc.perform(post(responsePath(id)).header("Authorization", stale).contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPTED\"}")).andExpect(status().isForbidden());
        f.toUser.setStatus(1); users.saveAndFlush(f.toUser); f.to.setStatus("FROZEN"); riders.saveAndFlush(f.to);
        assertEquals(403, postJson(responsePath(id), f.toUser, "{\"decision\":\"ACCEPTED\"}").path("code").asInt());
        mvc.perform(post(responsePath(id)).contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPTED\"}")).andExpect(status().isUnauthorized());
    }

    @Test void acceptedHistoryHasOnlySafeFieldsAndHistoricalRiderCannotReadOrderTaskTrackComplaintsOrPerformService() throws Exception {
        Fixture f = fixture(); long id = request(f);
        // 目标接受前不能通过转派列表获得订单私有详情。
        assertEquals(403, call(get("/api/v1/rider/orders/" + f.order.getId()), f.toUser).path("code").asInt());
        assertEquals(0, postJson(responsePath(id), f.toUser, "{\"decision\":\"ACCEPTED\",\"reason\":\"确认接手\"}").path("code").asInt());
        for (User user : List.of(f.fromUser, f.toUser, f.publisher)) {
            var result = call(get("/api/v1/orders/" + f.order.getId() + "/transfers"), user);
            assertEquals(0, result.path("code").asInt()); JsonNode vo = result.path("data").get(0);
            Set<String> fields = new HashSet<>(); vo.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("id", "orderId", "orderNo", "fromRiderId", "fromRiderName", "toRiderId", "toRiderName", "status", "reason", "responseReason", "createdTime", "handledTime", "taskMode", "title"), fields);
            assertEquals("ONSITE", vo.path("taskMode").asText()); assertEquals("可见标题", vo.path("title").asText());
            for (String secret : List.of("机密", "13800138000", "13900139000")) assertFalse(vo.toString().contains(secret));
        }
        for (String path : List.of("/api/v1/orders/" + f.order.getId(), "/api/v1/orders/" + f.order.getId() + "/custom-task", "/api/v1/orders/" + f.order.getId() + "/track", "/api/v1/orders/" + f.order.getId() + "/complaints")) {
            assertEquals(403, call(get(path), f.fromUser).path("code").asInt(), path);
        }
        assertEquals(0, call(get("/api/v1/rider/orders/" + f.order.getId() + "/custom-task"), f.toUser).path("code").asInt());
        assertEquals(403, call(get("/api/v1/rider/orders/" + f.order.getId()), f.fromUser).path("code").asInt());
        assertEquals(403, call(get("/api/v1/rider/orders/" + f.order.getId() + "/custom-task"), f.fromUser).path("code").asInt());
        assertEquals(403, postJson("/api/v1/orders/" + f.order.getId() + "/service-start", f.fromUser, "{}").path("code").asInt());
        assertEquals(403, postJson("/api/v1/orders/" + f.order.getId() + "/service-complete", f.fromUser, "{}").path("code").asInt());
        assertEquals(0, call(get("/api/v1/rider/orders"), f.fromUser).path("data").path("totalElements").asLong());
        User stranger = user("RIDER"); rider(stranger);
        assertEquals(403, call(get("/api/v1/orders/" + f.order.getId() + "/transfers"), stranger).path("code").asInt());
    }

    @Test void userSuppliedNamesReasonsAndTitlesDoNotLeakUnmaskedMainlandPhones() throws Exception {
        Fixture f = fixture(); f.from.setName("骑手13800138000"); riders.saveAndFlush(f.from); f.to.setName("目标13900139000"); riders.saveAndFlush(f.to);
        var task = tasks.findByOrderId(f.order.getId()).orElseThrow(); task.setTitle("标题13800138000"); tasks.saveAndFlush(task);
        var created = postJson(createPath(f), f.fromUser, "{\"targetRiderId\":" + f.to.getId() + ",\"reason\":\"联系13800138000\"}").path("data");
        assertEquals("联系138****8000", created.path("reason").asText()); long id = created.path("id").asLong();
        var response = postJson(responsePath(id), f.toUser, "{\"decision\":\"REJECTED\",\"reason\":\"联系13900139000\"}").path("data");
        assertEquals("联系139****9000", response.path("responseReason").asText());
        assertEquals("骑手138****8000", response.path("fromRiderName").asText());
        assertEquals("目标139****9000", response.path("toRiderName").asText()); assertEquals("标题138****8000", response.path("title").asText());
        assertFalse(response.toString().contains("13800138000")); assertFalse(response.toString().contains("13900139000"));
        assertEquals("联系13800138000", transfers.findById(id).orElseThrow().getReason()); // 审计原文保留，VO脱敏。
    }

    @Test void incomingOutgoingStatusFilterAndPage15RemainStableAndInvalidQueriesAre400() throws Exception {
        Fixture f = fixture();
        for (int i = 0; i < 17; i++) {
            long id = request(f);
            assertEquals(0, postJson(responsePath(id), f.toUser, "{\"decision\":\"REJECTED\"}").path("code").asInt());
        }
        String incoming = "/api/v1/rider/transfers?direction=INCOMING&status=REJECTED";
        var page0 = call(get(incoming), f.toUser).path("data"); assertEquals(15, page0.path("size").asInt()); assertEquals(17, page0.path("totalElements").asInt()); assertEquals(15, page0.path("content").size());
        var page1 = call(get(incoming + "&page=1"), f.toUser).path("data"); assertEquals(2, page1.path("content").size());
        assertTrue(page0.path("content").get(14).path("id").asLong() > page1.path("content").get(0).path("id").asLong());
        assertEquals(17, call(get("/api/v1/rider/transfers?direction=OUTGOING"), f.fromUser).path("data").path("totalElements").asInt());
        assertEquals(0, call(get("/api/v1/rider/transfers?direction=OUTGOING"), f.toUser).path("data").path("totalElements").asInt());
        for (String query : List.of("direction=BAD", "direction=incoming", "direction=INCOMING&status=BAD", "direction=INCOMING&page=-1", "direction=INCOMING&page=1.5", "direction=INCOMING&page=2147483648", "page=0")) {
            assertEquals(400, call(get("/api/v1/rider/transfers?" + query), f.toUser).path("code").asInt(), query);
        }
        assertEquals(403, call(get("/api/v1/rider/transfers?direction=INCOMING"), f.publisher).path("code").asInt());
    }

    @Test void rejectionCancelAndExpireAreReturnedWithHandledTimeAndTerminalConflicts() throws Exception {
        Fixture f = fixture(); long rejected = request(f);
        var rejectedResult = postJson(responsePath(rejected), f.toUser, "{\"decision\":\"REJECTED\",\"reason\":\"无暇\"}").path("data");
        assertEquals("REJECTED", rejectedResult.path("status").asText()); assertFalse(rejectedResult.path("handledTime").isNull());
        assertEquals(409, postJson(responsePath(rejected), f.toUser, "{\"decision\":\"REJECTED\"}").path("code").asInt());
        long canceled = request(f);
        var canceledResult = call(post("/api/v1/rider/transfers/" + canceled + "/cancel"), f.fromUser).path("data"); assertEquals("CANCELED", canceledResult.path("status").asText());
        assertEquals(409, postJson(responsePath(canceled), f.toUser, "{\"decision\":\"ACCEPTED\"}").path("code").asInt());
        long expired = request(f); dispatch.serviceStart(f.fromUser.getId(), f.order.getId());
        assertEquals(409, postJson(responsePath(expired), f.toUser, "{\"decision\":\"ACCEPTED\"}").path("code").asInt());
        var expiredPage = call(get("/api/v1/rider/transfers?direction=INCOMING&status=EXPIRED"), f.toUser).path("data");
        assertEquals(1, expiredPage.path("totalElements").asInt()); assertFalse(expiredPage.path("content").get(0).path("handledTime").isNull());
    }

    @Test void administrationRequiresOrdersMenuAndHasNoForceAcceptOrTransferEndpoint() throws Exception {
        Fixture f = fixture(); long id = request(f); User allowed = admin(true, false), ungranted = admin(false, false), unrelated = admin(false, true);
        String path = "/api/v1/admin/order-transfers?orderId=" + f.order.getId();
        mvc.perform(get(path).header("Authorization", token(f.fromUser))).andExpect(status().isForbidden());
        assertEquals(403, call(get(path), ungranted).path("code").asInt()); assertEquals(403, call(get(path), unrelated).path("code").asInt());
        mvc.perform(get(path).header("Authorization", token(allowed))).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.size").value(15)).andExpect(jsonPath("$.data.totalElements").value(1));
        for (String query : List.of("page=-1", "page=no", "orderId=0", "orderId=no", "status=BAD")) {
            assertEquals(400, call(get("/api/v1/admin/order-transfers?" + query), allowed).path("code").asInt(), query);
        }
        mvc.perform(post("/api/v1/admin/order-transfers").header("Authorization", token(allowed)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        assertNotEquals(0, postJson("/api/v1/admin/order-transfers/" + id + "/respond", allowed, "{\"decision\":\"ACCEPTED\"}").path("code").asInt());
        assertEquals(f.from.getId(), orders.findById(f.order.getId()).orElseThrow().getRiderId());
    }
}
