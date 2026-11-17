package com.feilv.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.domain.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import com.feilv.service.MessageService;
import com.feilv.service.UserService;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 独立内存数据库；不执行应用SQL、不启动真实HTTP端口、不发送短信。 */
@SpringBootTest(properties = {TestAuth.TEST_SECRET_PROPERTY,
        "spring.datasource.url=jdbc:h2:mem:rider-capability-http;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class RiderCapabilityHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RiderRepository riders;
    @Autowired OrderRepository orders;
    @Autowired CustomTaskRepository tasks;
    @Autowired PayRecordRepository payments;
    @Autowired OrderTransferRepository transfers;
    @Autowired OpLogRepository audits;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminUserRoleRepository userRoles;
    @Autowired AdminMenuRepository menus;
    @Autowired AdminRoleMenuRepository roleMenus;
    @Autowired UserService userService;
    @MockBean MessageService messages;

    private static final AtomicLong sequence = new AtomicLong(81000);
    private static final Set<String> VIEW_FIELDS = Set.of("riderId", "name", "creditScore", "onlineStatus", "status", "rideEnabled", "drivingEnabled");
    private record RiderUser(User user, Rider rider) {}
    private record Admin(User user, AdminAccount account, AdminRole role) {}
    private Admin manager;

    @BeforeEach void prepare() {
        // 每个用例的大厅只包含本用例订单；不删除或改写其他测试文件的数据配置。
        transfers.deleteAll(); tasks.deleteAll(); payments.deleteAll(); orders.deleteAll();
        manager = admin("riders");
    }

    private User user(String role) {
        User user = new User(); user.setRole(role); user.setNickname("能力HTTP用户");
        user.setMobile("137" + String.format("%08d", sequence.incrementAndGet()));
        return users.saveAndFlush(user);
    }

    private RiderUser rider() {
        User user = user("RIDER"); Rider rider = new Rider(); rider.setUserId(user.getId());
        rider.setName("能力HTTP骑手"); rider.setOnlineStatus(1); rider.setVehicleType("CAR");
        rider.setPlateNo("贵D测试"); rider.setIdCardEnc("不得返回的身份资料");
        return new RiderUser(user, riders.saveAndFlush(rider));
    }

    private Admin admin(String menuCode) {
        User user = user("ADMIN"); AdminAccount account = new AdminAccount(); account.setUserId(user.getId());
        account.setUsername("cap-http-" + user.getId()); account.setPasswordHash("not-a-real-password"); accounts.saveAndFlush(account);
        AdminRole role = new AdminRole(); role.setCode("CAP_HTTP_" + user.getId()); role.setName("能力授权测试"); roles.saveAndFlush(role);
        AdminUserRole link = new AdminUserRole(); link.setUserId(user.getId()); link.setRoleId(role.getId()); userRoles.saveAndFlush(link);
        if (menuCode != null) {
            AdminMenu menu = menus.findAll().stream().filter(m -> menuCode.equals(m.getCode())).findFirst().orElseGet(() -> {
                AdminMenu created = new AdminMenu(); created.setCode(menuCode); created.setName("能力测试菜单"); return menus.saveAndFlush(created);
            });
            AdminRoleMenu grant = new AdminRoleMenu(); grant.setRoleId(role.getId()); grant.setMenuId(menu.getId()); roleMenus.saveAndFlush(grant);
        }
        return new Admin(user, account, role);
    }

    private OrderMain order(String mode) {
        User publisher = user("USER"); OrderMain order = new OrderMain();
        order.setOrderNo("CAP-HTTP-" + sequence.incrementAndGet()); order.setOrderType("SEND".equals(mode) ? "SEND" : "CUSTOM");
        order.setUserId(publisher.getId()); order.setStatus(OrderStatus.WAIT_GRAB); order.setEstimateFee(1000); order.setRealFee(1000);
        order.setPickupCode("SEND".equals(mode) ? "1234" : ""); order.setGoodsDesc("公开摘要");
        order.setFromContact("私有联系人"); order.setFromPhone("13800138000"); order.setFromAddress("起始地址");
        order.setToContact(""); order.setToPhone(""); order.setToAddress(""); orders.saveAndFlush(order);
        if (!"SEND".equals(mode)) {
            CustomTask task = new CustomTask(); task.setOrderId(order.getId()); task.setUserId(publisher.getId());
            task.setTaskMode(mode); task.setTitle(mode + "服务"); task.setContent("不能在大厅公开的需求");
            task.setContact("私有联系人"); task.setPhone("13800138000"); task.setLocationAddress("起始地址"); task.setServiceTime("明天18:30");
            task.setPublishSource("MINIAPP"); task.setCreatedBy(publisher.getId()); tasks.saveAndFlush(task);
        }
        PayRecord payment = new PayRecord(); payment.setOrderId(order.getId()); payment.setPayNo("CAP-HTTP-PAY-" + sequence.incrementAndGet());
        payment.setChannel("MOCK"); payment.setStatus("SUCCESS"); payment.setAmount(1000); payment.setShareRider(700); payment.setSharePlatform(300);
        payments.saveAndFlush(payment); return order;
    }

    private String token(User user) { return TestAuth.bearer(user); }
    private String path(Rider rider, String mode) { return "/api/v1/admin/riders/" + rider.getId() + "/capabilities/" + mode; }
    private String body(boolean enabled, boolean expected, String reason) throws Exception {
        var value = json.createObjectNode(); value.put("enabled", enabled); value.put("expectedEnabled", expected); value.put("reason", reason);
        return json.writeValueAsString(value);
    }
    private JsonNode call(MockHttpServletRequestBuilder request, User user) throws Exception {
        String path = request.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI();
        boolean riderEndpoint = path.startsWith("/api/v1/rider/") || path.startsWith("/api/v1/grab/")
                || path.matches("^/api/v1/orders/[^/]+/(service-start|service-complete)$");
        String authorization = riderEndpoint ? TestAuth.riderBearer(user) : token(user);
        return json.readTree(mvc.perform(request.header("Authorization", authorization)).andReturn().getResponse().getContentAsByteArray());
    }
    private JsonNode update(Admin admin, Rider rider, String mode, String body) throws Exception {
        return call(put(path(rider, mode)).contentType(MediaType.APPLICATION_JSON).content(body), admin.user());
    }
    private JsonNode update(Rider rider, String mode, boolean enabled, boolean expected) throws Exception {
        return update(manager, rider, mode, body(enabled, expected, "平台能力审核"));
    }
    private JsonNode postJson(String path, User user, String body) throws Exception {
        return call(post(path).contentType(MediaType.APPLICATION_JSON).content(body), user);
    }
    private Set<String> fields(JsonNode node) { Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add); return fields; }
    private List<OpLog> capabilityAudits(Rider rider) {
        return audits.findAll().stream().filter(a -> "RIDER_CAPABILITY_UPDATE".equals(a.getAction()) && a.getDetail().startsWith("riderId=" + rider.getId() + ",")).toList();
    }
    private void assertDisabled(Rider rider) {
        Rider actual = riders.findById(rider.getId()).orElseThrow(); assertFalse(actual.isRideEnabled()); assertFalse(actual.isDrivingEnabled());
    }

    @Test void dedicatedViewIndependentCapabilitiesCasAndAuditNeverExposeOrMutateOtherRiderFields() throws Exception {
        RiderUser target = rider(); Rider rider = target.rider(); assertDisabled(rider);
        JsonNode granted = update(manager, rider, "RIDE", body(true, false, "  接送开通审核  "));
        assertEquals(0, granted.path("code").asInt(), granted.toString()); JsonNode view = granted.path("data");
        assertEquals(VIEW_FIELDS, fields(view)); assertEquals(rider.getId().longValue(), view.path("riderId").asLong());
        assertTrue(view.path("rideEnabled").isBoolean()); assertTrue(view.path("rideEnabled").asBoolean()); assertFalse(view.path("drivingEnabled").asBoolean());
        assertEquals(409, update(rider, "RIDE", true, false).path("code").asInt());
        assertEquals(409, update(rider, "RIDE", false, false).path("code").asInt());
        assertEquals(0, update(rider, "RIDE", true, true).path("code").asInt()); // 同值且预期匹配，无新增审计。
        assertEquals(1, capabilityAudits(rider).size());
        OpLog audit = capabilityAudits(rider).get(0); assertEquals(manager.user().getId(), audit.getOperatorId());
        assertTrue(audit.getDetail().contains("mode=RIDE, from=false, to=true")); assertTrue(audit.getDetail().endsWith("reason=接送开通审核"));
        assertEquals(0, update(rider, "DRIVING", true, false).path("code").asInt());
        assertEquals(0, update(rider, "RIDE", false, true).path("code").asInt());
        assertEquals(3, capabilityAudits(rider).size());
        JsonNode list = call(get("/api/v1/admin/riders"), manager.user()); assertEquals(0, list.path("code").asInt());
        JsonNode listed = null;
        for (JsonNode row : list.path("data")) {
            assertEquals(VIEW_FIELDS, fields(row));
            if (row.path("riderId").asLong() == rider.getId()) listed = row;
        }
        assertNotNull(listed); assertFalse(listed.path("rideEnabled").asBoolean()); assertTrue(listed.path("drivingEnabled").asBoolean());
        Rider saved = riders.findById(rider.getId()).orElseThrow();
        assertEquals(rider.getName(), saved.getName()); assertEquals(100, saved.getCreditScore()); assertEquals(1, saved.getOnlineStatus());
        assertEquals("ACTIVE", saved.getStatus()); assertEquals("CAR", saved.getVehicleType()); assertEquals("贵D测试", saved.getPlateNo());
        assertEquals("不得返回的身份资料", saved.getIdCardEnc()); assertEquals("RIDER", users.findById(target.user().getId()).orElseThrow().getRole());
    }

    @Test void booleansAreStrictAndMissingOrUnknownMassAssignmentFieldsAreRejectedWithoutAudit() throws Exception {
        Rider rider = rider().rider();
        for (String field : List.of("enabled", "expectedEnabled")) {
            for (String value : List.of("\"true\"", "\"false\"", "\"\"", "0", "1", "1.0", "1e0", "[]", "{}", "null")) {
                String enabled = "enabled".equals(field) ? value : "true";
                String expected = "expectedEnabled".equals(field) ? value : "false";
                String invalid = "{\"enabled\":" + enabled + ",\"expectedEnabled\":" + expected + ",\"reason\":\"有效原因\"}";
                assertEquals(400, update(manager, rider, "RIDE", invalid).path("code").asInt(), invalid);
            }
        }
        for (String invalid : List.of("{}", "{\"enabled\":true,\"reason\":\"原因\"}", "{\"expectedEnabled\":false,\"reason\":\"原因\"}",
                "{\"enabled\":true,\"expectedEnabled\":false}", "{\"enabled\":true,\"expectedEnabled\":false,\"reason\":null}")) {
            assertEquals(400, update(manager, rider, "RIDE", invalid).path("code").asInt(), invalid);
        }
        for (String field : List.of("riderId", "userId", "role", "status", "creditScore", "onlineStatus", "income", "rideEnabled", "drivingEnabled", "mode", "unexpected")) {
            var invalid = json.readTree(body(true, false, "合法原因")).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put(field, "不接受整实体更新");
            assertEquals(400, update(manager, rider, "RIDE", invalid.toString()).path("code").asInt(), field);
        }
        assertDisabled(rider); assertTrue(capabilityAudits(rider).isEmpty());
    }

    @Test void reasonAllows160CharactersRejects161AndInvalidModesAndMissingRidersAreExplicit() throws Exception {
        Rider rider = rider().rider();
        for (String reason : List.of("", " ", "\t\n", "长".repeat(161), "长".repeat(256))) {
            assertEquals(400, update(manager, rider, "RIDE", body(true, false, reason)).path("code").asInt(), "length=" + reason.length());
        }
        for (String mode : List.of("ride", "driving", "ONSITE", "DELIVERY", "UNKNOWN")) {
            assertEquals(400, update(manager, rider, mode, body(true, false, "合法原因")).path("code").asInt(), mode);
        }
        assertEquals(404, call(put("/api/v1/admin/riders/9223372036854775807/capabilities/RIDE")
                .contentType(MediaType.APPLICATION_JSON).content(body(true, false, "不存在的骑手")), manager.user()).path("code").asInt());
        assertDisabled(rider); assertTrue(capabilityAudits(rider).isEmpty());
        String longest = "界".repeat(160);
        JsonNode result = update(manager, rider, "DRIVING", body(true, false, longest)); assertEquals(0, result.path("code").asInt(), result.toString());
        assertTrue(riders.findById(rider.getId()).orElseThrow().isDrivingEnabled());
        assertEquals(1, capabilityAudits(rider).size()); assertTrue(capabilityAudits(rider).get(0).getDetail().endsWith("reason=" + longest));
        assertTrue(capabilityAudits(rider).get(0).getDetail().length() <= 255);
    }

    @Test void listAndUpdateRequireLiveRidersMenuNotOnlyAdminJwtAndPermissionRevocationIsImmediate() throws Exception {
        RiderUser rider = rider(); Admin denied = admin(null), unrelated = admin("users");
        String valid = body(true, false, "权限测试");
        for (Admin admin : List.of(denied, unrelated)) {
            assertEquals(403, call(get("/api/v1/admin/riders"), admin.user()).path("code").asInt());
            assertEquals(403, update(admin, rider.rider(), "RIDE", valid).path("code").asInt());
        }
        mvc.perform(get("/api/v1/admin/riders")).andExpect(status().isUnauthorized());
        mvc.perform(put(path(rider.rider(), "RIDE")).contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isUnauthorized());
        for (User user : List.of(rider.user(), user("USER"))) {
            mvc.perform(get("/api/v1/admin/riders").header("Authorization", token(user))).andExpect(status().isForbidden());
            mvc.perform(put(path(rider.rider(), "RIDE")).header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isForbidden());
        }
        assertEquals(0, call(get("/api/v1/admin/riders"), manager.user()).path("code").asInt());
        manager.role().setEnabled(0); roles.saveAndFlush(manager.role());
        assertEquals(403, call(get("/api/v1/admin/riders"), manager.user()).path("code").asInt());
        assertEquals(403, update(manager, rider.rider(), "RIDE", valid).path("code").asInt());
        manager.role().setEnabled(1); roles.saveAndFlush(manager.role());
        manager.account().setEnabled(0); accounts.saveAndFlush(manager.account());
        assertEquals(403, call(get("/api/v1/admin/riders"), manager.user()).path("code").asInt());
        assertEquals(403, update(manager, rider.rider(), "RIDE", valid).path("code").asInt());
        manager.account().setEnabled(1); accounts.saveAndFlush(manager.account());
        AdminMenu menu = menus.findAll().stream().filter(m -> "riders".equals(m.getCode())).findFirst().orElseThrow();
        try {
            menu.setEnabled(0); menus.saveAndFlush(menu);
            assertEquals(403, call(get("/api/v1/admin/riders"), manager.user()).path("code").asInt());
            assertEquals(403, update(manager, rider.rider(), "RIDE", valid).path("code").asInt());
        } finally { menu.setEnabled(1); menus.saveAndFlush(menu); }
        String staleToken = token(manager.user()); manager.user().setRole("USER"); users.saveAndFlush(manager.user());
        mvc.perform(get("/api/v1/admin/riders").header("Authorization", staleToken)).andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(40101));
        assertDisabled(rider.rider()); assertTrue(capabilityAudits(rider.rider()).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void fourCapabilityCombinationsFilterHallAndDirectGrabCannotBypassAuthorization(boolean ride, boolean driving) throws Exception {
        RiderUser rider = rider();
        if (ride) assertEquals(0, update(rider.rider(), "RIDE", true, false).path("code").asInt());
        if (driving) assertEquals(0, update(rider.rider(), "DRIVING", true, false).path("code").asInt());
        OrderMain rideOrder = order("RIDE"), drivingOrder = order("DRIVING"), onsite = order("ONSITE"), delivery = order("SEND");
        JsonNode hall = call(get("/api/v1/grab/list"), rider.user()); assertEquals(0, hall.path("code").asInt());
        Set<Long> visible = new HashSet<>(); for (JsonNode row : hall.path("data")) visible.add(row.path("orderId").asLong());
        Set<Long> expected = new HashSet<>(Set.of(onsite.getId(), delivery.getId()));
        if (ride) expected.add(rideOrder.getId()); if (driving) expected.add(drivingOrder.getId()); assertEquals(expected, visible);
        assertFalse(hall.toString().contains("13800138000")); assertFalse(hall.toString().contains("私有联系人")); assertFalse(hall.toString().contains("不能在大厅公开的需求"));
        for (OrderMain order : List.of(rideOrder, drivingOrder)) {
            boolean allowed = order.getId().equals(rideOrder.getId()) ? ride : driving;
            JsonNode result = call(post("/api/v1/grab/" + order.getId()), rider.user()); assertEquals(allowed ? 0 : 403, result.path("code").asInt(), result.toString());
            OrderMain actual = orders.findById(order.getId()).orElseThrow();
            assertEquals(allowed ? OrderStatus.ACCEPTED : OrderStatus.WAIT_GRAB, actual.getStatus());
            assertEquals(allowed ? rider.rider().getId() : null, actual.getRiderId());
            if (allowed) assertEquals("", result.path("data").path("pickupCode").asText());
        }
        assertEquals(0, call(post("/api/v1/grab/" + onsite.getId()), rider.user()).path("code").asInt());
        assertEquals(0, call(post("/api/v1/grab/" + delivery.getId()), rider.user()).path("code").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RIDE", "DRIVING"})
    void revokedCapabilityBlocksFirstStartAndTransferAcceptanceButOriginalRiderCanTransferOut(String mode) throws Exception {
        RiderUser source = rider(), target = rider(); OrderMain order = order(mode);
        assertEquals(0, update(source.rider(), mode, true, false).path("code").asInt());
        assertEquals(0, call(post("/api/v1/grab/" + order.getId()), source.user()).path("code").asInt());
        assertEquals(0, update(source.rider(), mode, false, true).path("code").asInt());
        assertEquals(403, call(post("/api/v1/orders/" + order.getId() + "/service-start"), source.user()).path("code").asInt());
        String createPath = "/api/v1/rider/orders/" + order.getId() + "/transfers";
        String request = "{\"targetRiderId\":" + target.rider().getId() + ",\"reason\":\"撤权后协调转出\"}";
        assertEquals(403, postJson(createPath, source.user(), request).path("code").asInt());
        assertEquals(0, update(target.rider(), mode, true, false).path("code").asInt());
        JsonNode pending = postJson(createPath, source.user(), request); assertEquals(0, pending.path("code").asInt(), pending.toString());
        long transferId = pending.path("data").path("id").asLong();
        assertEquals(0, update(target.rider(), mode, false, true).path("code").asInt());
        String responsePath = "/api/v1/rider/transfers/" + transferId + "/respond";
        assertEquals(403, postJson(responsePath, target.user(), "{\"decision\":\"ACCEPTED\"}").path("code").asInt());
        assertEquals(OrderTransfer.Status.PENDING, transfers.findById(transferId).orElseThrow().getStatus());
        assertEquals(source.rider().getId(), orders.findById(order.getId()).orElseThrow().getRiderId());
        assertEquals(0, update(target.rider(), mode, true, false).path("code").asInt());
        assertEquals(0, postJson(responsePath, target.user(), "{\"decision\":\"ACCEPTED\"}").path("code").asInt());
        assertEquals(target.rider().getId(), orders.findById(order.getId()).orElseThrow().getRiderId());
        assertEquals(0, update(target.rider(), mode, false, true).path("code").asInt());
        assertEquals(0, postJson(responsePath, target.user(), "{\"decision\":\"ACCEPTED\"}").path("code").asInt()); // 已接受的幂等重试不重新接单。
    }

    @ParameterizedTest
    @ValueSource(strings = {"RIDE", "DRIVING"})
    void alreadyStartedServiceCanRetryStartAndCompleteAfterRevocation(String mode) throws Exception {
        RiderUser rider = rider(); OrderMain order = order(mode);
        assertEquals(0, update(rider.rider(), mode, true, false).path("code").asInt());
        assertEquals(0, call(post("/api/v1/grab/" + order.getId()), rider.user()).path("code").asInt());
        String start = "/api/v1/orders/" + order.getId() + "/service-start", complete = "/api/v1/orders/" + order.getId() + "/service-complete";
        assertEquals(0, call(post(start), rider.user()).path("code").asInt());
        assertEquals(0, update(rider.rider(), mode, false, true).path("code").asInt());
        for (int i = 0; i < 2; i++) {
            assertEquals(0, call(post(start), rider.user()).path("code").asInt());
            assertEquals(0, call(post(complete), rider.user()).path("code").asInt());
        }
        assertEquals(OrderStatus.DELIVERED, orders.findById(order.getId()).orElseThrow().getStatus()); assertDisabled(rider.rider());
    }

    @Test void legacyPublicRegistrationCannotCreateOrPromoteRiderOrEnableCapabilities() throws Exception {
        User existing = user("USER");
        long userCount = users.count(), riderCount = riders.count(), auditCount = audits.count();
        for (String mobile : List.of(existing.getMobile(), "136" + String.format("%08d", sequence.incrementAndGet()))) {
            var request = json.createObjectNode(); request.put("mobile", mobile); request.put("name", "旧版汽车骑手");
            request.put("code", "123456"); request.put("plateNo", "贵D注册");
            JsonNode result = json.readTree(mvc.perform(post("/api/v1/auth/rider-register").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                    .andReturn().getResponse().getContentAsByteArray());
            assertEquals(410, result.path("code").asInt(), result.toString());
        }
        assertEquals("USER", users.findById(existing.getId()).orElseThrow().getRole());
        assertTrue(riders.findByUserId(existing.getId()).isEmpty());
        assertEquals(userCount, users.count()); assertEquals(riderCount, riders.count()); assertEquals(auditCount, audits.count());
        // Protected enrollment's ACTIVE/default-disabled capability behavior is covered by MiniappEnrollment*Test.
    }
}
