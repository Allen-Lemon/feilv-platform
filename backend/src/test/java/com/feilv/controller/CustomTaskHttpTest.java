package com.feilv.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:custom-task-http;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
@Transactional
class CustomTaskHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired TaskTagRepository tags;
    @Autowired CustomTaskRepository tasks;
    @Autowired CustomTaskTagRepository snapshots;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminUserRoleRepository userRoles;
    @Autowired RiderRepository riders;
    @Autowired PayRecordRepository payments;
    @Autowired OrderFeeDetailRepository fees;
    @Autowired OrderStatusLogRepository logs;
    @Autowired WalletAccountRepository walletAccounts;
    @Autowired WalletOrderSettlementRepository settlements;
    @Autowired RiderTrackRepository tracks;
    @Autowired com.feilv.service.OrderService orderService;
    @Autowired com.feilv.service.PayService payService;
    @Autowired com.feilv.service.DispatchService dispatch;
    @org.springframework.boot.test.mock.mockito.SpyBean com.feilv.service.PricingService pricing;

    private User user(String role, String mobile) {
        User user = new User(); user.setRole(role); user.setMobile(mobile); user.setNickname("任务用户"); return users.saveAndFlush(user);
    }
    private String token(User user) { return "Bearer " + com.feilv.util.JwtUtil.create(user.getId(), user.getRole()); }
    private User admin() {
        User user = user("ADMIN", "admin-task-test");
        AdminAccount account = new AdminAccount(); account.setUserId(user.getId()); account.setUsername("task-admin"); account.setPasswordHash("test-hash"); accounts.saveAndFlush(account);
        AdminRole role = new AdminRole(); role.setCode("SUPER_ADMIN"); role.setName("任务管理员"); role = roles.saveAndFlush(role);
        AdminUserRole link = new AdminUserRole(); link.setUserId(user.getId()); link.setRoleId(role.getId()); userRoles.saveAndFlush(link);
        return user;
    }
    private Map<String, Object> order() {
        return new LinkedHashMap<>(Map.of("orderType", "CUSTOM", "fromContact", "寄件人", "fromPhone", "13800138000", "fromAddress", "取件地址",
                "toContact", "收件人", "toPhone", "13900139000", "toAddress", "收件地址", "distanceM", 2000, "weightKg", 1));
    }
    private Map<String, Object> request(List<Long> ids) { return new LinkedHashMap<>(Map.of("title", "自定义任务标题", "content", "任务详细说明", "tagIds", ids,
            "taskMode", "ONLINE", "priceFen", 12345, "contact", "任务发布人", "phone", "13800138000")); }
    private JsonNode postBody(String path, User user, Object body) throws Exception {
        return json.readTree(mvc.perform(post(path).header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    private TaskTag tag(String name) { TaskTag tag = new TaskTag(); tag.setName(name); return tags.saveAndFlush(tag); }

    @Test void miniappPublicationCreatesLinkedOrderAndTagSnapshotWithoutPaymentAndKeepsOwnerAuthorization() throws Exception {
        User user = user("USER", "13800138001"); TaskTag tag = tag("加急");
        JsonNode response = postBody("/api/v1/custom-tasks", user, request(List.of(tag.getId())));
        assertEquals(0, response.path("code").asInt()); long id = response.path("data").path("id").asLong();
        assertTrue(response.path("data").path("owner").asBoolean());
        assertEquals("CUSTOM", orders.findById(id).orElseThrow().getOrderType()); assertEquals(com.feilv.enums.OrderStatus.WAIT_PAY, orders.findById(id).orElseThrow().getStatus());
        tag.setName("新标签名"); tags.saveAndFlush(tag);
        mvc.perform(get("/api/v1/orders/" + id + "/custom-task").header("Authorization", token(user)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.title").value("自定义任务标题"))
                .andExpect(jsonPath("$.data.tags[0].name").value("加急"));
        User other = user("USER", "13800138002");
        mvc.perform(get("/api/v1/orders/" + id + "/custom-task").header("Authorization", token(other))).andExpect(jsonPath("$.code").value(403));
    }

    @Test void disabledDuplicateMissingTagsAndUnboundAccountsNeverLeaveOrphanOrders() throws Exception {
        User user = user("USER", "13800138003"); TaskTag tag = tag("标签"); tag.setEnabled(false); tags.saveAndFlush(tag);
        long before = orders.count();
        for (List<Long> ids : List.of(List.of(tag.getId()), List.of(tag.getId(), tag.getId()), List.of(99999L))) {
            assertNotEquals(0, postBody("/api/v1/custom-tasks", user, request(ids)).path("code").asInt());
        }
        assertEquals(before, orders.count()); assertEquals(0, tasks.count()); assertEquals(0, snapshots.count());
        User unbound = user("USER", null);
        assertEquals(4091, postBody("/api/v1/custom-tasks", unbound, request(List.of())).path("code").asInt());
        assertEquals(before, orders.count());
    }

    @Test void inputLimitsAndLegacyOrderRouteCannotBypassTaskMetadata() throws Exception {
        User user = user("USER", "13800138004");
        var body = request(List.of()); body.put("title", "a".repeat(65));
        assertEquals(400, postBody("/api/v1/custom-tasks", user, body).path("code").asInt());
        body = request(List.of()); body.put("content", "a".repeat(1001));
        assertEquals(400, postBody("/api/v1/custom-tasks", user, body).path("code").asInt());
        var raw = order(); raw.put("goodsDesc", "绕过元数据");
        assertEquals(400, postBody("/api/v1/orders", user, raw).path("code").asInt());
        assertEquals(0, orders.count());
        assertEquals(400, postBody("/api/v1/custom-tasks/quote", user, Map.of("distanceM", -1, "weightKg", 1)).path("code").asInt());
        assertEquals(400, postBody("/api/v1/custom-tasks/quote", user, Map.of("distanceM", 2000, "weightKg", 1)).path("code").asInt());
        var quote = postBody("/api/v1/custom-tasks/quote", user, Map.of("priceFen", 100000000));
        assertEquals(0, quote.path("code").asInt()); assertEquals(100000000, quote.path("data").path("totalFen").asInt());
        assertEquals(70000000, quote.path("data").path("riderShareFen").asInt());
        assertEquals(30000000, quote.path("data").path("platformShareFen").asInt());
        assertEquals("发布者自定任务总价", quote.path("data").path("items").get(0).path("label").asText());
    }

    @Test void adminCanPublishForBoundCustomerButCannotImpersonateAnAdminCustomerAndUsesVersionedTagUpdates() throws Exception {
        User admin = admin(), user = user("USER", "13800138005");
        var created = postBody("/api/v1/admin/task-tags", admin, Map.of("name", "后台标签", "sort", 1, "enabled", true));
        assertEquals(0, created.path("code").asInt()); long tagId = created.path("data").path("id").asLong(); long version = created.path("data").path("version").asLong();
        var body = request(List.of(tagId)); body.put("userId", user.getId());
        var result = postBody("/api/v1/admin/custom-tasks", admin, body);
        assertEquals(0, result.path("code").asInt()); assertEquals("ADMIN", result.path("data").path("publishSource").asText());
        assertEquals(user.getId().longValue(), result.path("data").path("userId").asLong());
        assertEquals(admin.getId().longValue(), result.path("data").path("createdBy").asLong());
        mvc.perform(put("/api/v1/admin/task-tags/" + tagId).header("Authorization", token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "已停用", "sort", 1, "enabled", false, "version", version))))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/v1/admin/task-tags/" + tagId).header("Authorization", token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "覆盖旧版本", "sort", 1, "enabled", true, "version", version))))
                .andExpect(jsonPath("$.code").value(409));
        mvc.perform(get("/api/v1/task-tags").header("Authorization", token(user))).andExpect(jsonPath("$.data").isEmpty());
        body = request(List.of()); body.put("userId", admin.getId());
        assertEquals(403, postBody("/api/v1/admin/custom-tasks", admin, body).path("code").asInt());
    }

    @Test void strictJsonPriceContractAppliesToBothPublicationAndQuoteIncludingAdmin() throws Exception {
        User user = user("USER", "13800138101"), admin = admin();
        for (String value : List.of("0", "-1", "1.0", "1.5", "1e2", "\"1\"", "true", "false", "null", "100000001", "2147483648", "[]", "{}")) {
            for (String path : List.of("/api/v1/custom-tasks", "/api/v1/admin/custom-tasks")) {
                var body = request(List.of()); body.remove("priceFen");
                if (path.contains("/admin/")) body.put("userId", user.getId());
                String text = json.writeValueAsString(body);
                text = text.substring(0, text.length() - 1) + ",\"priceFen\":" + value + "}";
                mvc.perform(post(path).header("Authorization", token(path.contains("/admin/") ? admin : user))
                                .contentType(MediaType.APPLICATION_JSON).content(text))
                        .andExpect(jsonPath("$.code").value(400));
            }
            for (String path : List.of("/api/v1/custom-tasks/quote", "/api/v1/admin/custom-tasks/quote")) {
                mvc.perform(post(path).header("Authorization", token(path.contains("/admin/") ? admin : user))
                                .contentType(MediaType.APPLICATION_JSON).content("{\"priceFen\":" + value + "}"))
                        .andExpect(jsonPath("$.code").value(400));
            }
        }
        var missing = request(List.of()); missing.remove("priceFen");
        assertEquals(400, postBody("/api/v1/custom-tasks", user, missing).path("code").asInt());
        var legacy = new LinkedHashMap<String, Object>(Map.of("title", "旧体", "content", "旧体内容", "order", order()));
        assertEquals(400, postBody("/api/v1/custom-tasks", user, legacy).path("code").asInt());
        var nested = request(List.of()); nested.put("order", order());
        assertEquals(400, postBody("/api/v1/custom-tasks", user, nested).path("code").asInt());
        var impersonate = request(List.of()); impersonate.put("userId", user.getId());
        assertEquals(400, postBody("/api/v1/custom-tasks", user, impersonate).path("code").asInt());
        for (String field : List.of("distanceM", "weightKg")) {
            var body = request(List.of()); body.put(field, 1);
            assertEquals(400, postBody("/api/v1/custom-tasks", user, body).path("code").asInt());
        }
        assertEquals(0, orders.count()); org.mockito.Mockito.verifyNoInteractions(pricing);
    }

    @Test void codepointAndModeSpecificValidationAndOnlineNormalization() throws Exception {
        User user = user("USER", "13800138102");
        var body = request(List.of()); body.put("title", "标".repeat(64)); body.put("content", "详".repeat(1000));
        body.put("contact", "人".repeat(32)); body.put("locationAddress", "陈旧地址"); body.put("serviceTime", "陈旧时间");
        var created = postBody("/api/v1/custom-tasks", user, body);
        assertEquals(0, created.path("code").asInt()); long id = created.path("data").path("id").asLong();
        assertEquals("ONLINE", created.path("data").path("taskMode").asText());
        var task = tasks.findByOrderId(id).orElseThrow(); assertEquals("", task.getLocationAddress()); assertEquals("", task.getServiceTime());
        var order = orders.findById(id).orElseThrow();
        assertEquals("线上任务，无需到场", order.getFromAddress()); assertEquals(order.getFromAddress(), order.getToAddress());
        assertNull(order.getFromLng()); assertNull(order.getFromLat()); assertNull(order.getToLng()); assertNull(order.getToLat());
        assertEquals(0, order.getDistanceM()); assertEquals(1, order.getWeightKg()); assertEquals("", order.getPickupCode());
        for (String field : List.of("title", "content", "contact", "locationAddress", "serviceTime")) {
            int max = Map.of("title", 64, "content", 1000, "contact", 32, "locationAddress", 255, "serviceTime", 100).get(field);
            body = request(List.of()); body.put(field, "😀".repeat(max + 1));
            assertEquals(400, postBody("/api/v1/custom-tasks", user, body).path("code").asInt(), field);
        }
        for (String mode : List.of("DELIVERY", "online", "", "ONSITE", "PART_TIME")) {
            body = request(List.of()); body.put("taskMode", mode);
            assertEquals(400, postBody("/api/v1/custom-tasks", user, body).path("code").asInt(), mode);
        }
        for (String badPhone : List.of("12800138000", "1380013800", "138001380001", "+8613800138000")) {
            body = request(List.of()); body.put("phone", badPhone);
            assertEquals(400, postBody("/api/v1/custom-tasks", user, body).path("code").asInt());
        }
        body = request(List.of()); body.put("taskMode", "PART_TIME"); body.put("locationAddress", "工地");
        assertEquals(400, postBody("/api/v1/custom-tasks", user, body).path("code").asInt());
        body.put("serviceTime", "明日9点至17点");
        assertEquals(0, postBody("/api/v1/custom-tasks", user, body).path("code").asInt());
        body = request(List.of()); body.put("taskMode", "ONSITE"); body.put("locationAddress", "址".repeat(255));
        assertEquals(0, postBody("/api/v1/custom-tasks", user, body).path("code").asInt());
        org.mockito.Mockito.verifyNoInteractions(pricing);
    }

    private User rider(String mobile) {
        User user = user("RIDER", mobile); Rider rider = new Rider(); rider.setUserId(user.getId()); rider.setName("现役骑手"); rider.setOnlineStatus(1); riders.saveAndFlush(rider); return user;
    }
    private long publish(User user, String mode, int price) throws Exception {
        var body = request(List.of()); body.put("taskMode", mode); body.put("priceFen", price);
        if (!"ONLINE".equals(mode)) body.put("locationAddress", "唯一服务地点");
        if ("PART_TIME".equals(mode)) body.put("serviceTime", "本周六9至18点");
        var result = postBody("/api/v1/custom-tasks", user, body);
        assertEquals(0, result.path("code").asInt(), result.toString()); return result.path("data").path("id").asLong();
    }
    private void action(User user, long id, String action, int code) throws Exception {
        mvc.perform(post("/api/v1/orders/" + id + "/" + action).header("Authorization", token(user)))
                .andExpect(jsonPath("$.code").value(code));
    }
    private JsonNode getBody(String path, User user) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", token(user))).andReturn().getResponse().getContentAsByteArray());
    }

    @Test void paidServiceLifecycleKeepsPrivateDetailsAndSettlesOriginalShareExactlyOnceForEveryMode() throws Exception {
        User publisher = user("USER", "13800138103"), rider = rider("13800138104"), other = rider("13800138105");
        for (String mode : List.of("ONLINE", "ONSITE", "PART_TIME")) {
            long id = publish(publisher, mode, 100000000);
            assertEquals(0, getBody("/api/v1/grab/list", rider).path("data").size());
            action(publisher, id, "pay", 0);
            var payment = payments.findByOrderId(id).get(0);
            assertEquals(100000000, payment.getAmount()); assertEquals(70000000, payment.getShareRider()); assertEquals(30000000, payment.getSharePlatform());
            var hall = getBody("/api/v1/grab/list", rider).path("data"); assertEquals(1, hall.size());
            assertEquals(mode, hall.get(0).path("taskMode").asText()); assertEquals("自定义任务标题", hall.get(0).path("title").asText());
            assertFalse(hall.toString().contains("任务详细说明")); assertFalse(hall.toString().contains("13800138000"));
            assertFalse(hall.get(0).has("phone")); assertFalse(hall.get(0).has("contact")); assertFalse(hall.get(0).has("content"));
            assertEquals(70000000, hall.get(0).path("rewardFen").asInt());
            assertEquals(403, getBody("/api/v1/orders/" + id + "/custom-task", other).path("code").asInt());
            var grabbed = postBody("/api/v1/grab/" + id, rider, Map.of()); assertEquals(0, grabbed.path("code").asInt()); assertEquals("", grabbed.path("data").path("pickupCode").asText());
            var detail = getBody("/api/v1/orders/" + id + "/custom-task", rider).path("data");
            assertEquals("任务详细说明", detail.path("content").asText()); assertEquals("13800138000", detail.path("phone").asText());
            assertEquals(mode, getBody("/api/v1/orders/" + id, rider).path("data").path("taskMode").asText());
            action(rider, id, "service-complete", 4090);
            for (String action : List.of("pickup", "depart", "deliver")) action(rider, id, action, 4090);
            action(other, id, "service-start", 403);
            action(publisher, id, "confirm", 4090);
            action(rider, id, "service-start", 0); action(rider, id, "service-start", 0);
            assertEquals(com.feilv.enums.OrderStatus.PICKING, orders.findById(id).orElseThrow().getStatus());
            assertEquals("服务中", getBody("/api/v1/orders/" + id, rider).path("data").path("statusLabel").asText());
            var track = postBody("/api/v1/track/report", rider, Map.of("orderId", id, "lng", 100, "lat", 20)); assertEquals(4090, track.path("code").asInt());
            assertEquals(4090, getBody("/api/v1/orders/" + id + "/track", publisher).path("code").asInt());
            assertTrue(tracks.findTop50ByOrderIdOrderByIdDesc(id).isEmpty());
            action(rider, id, "service-complete", 0); action(rider, id, "service-complete", 0);
            assertEquals(com.feilv.enums.OrderStatus.DELIVERED, orders.findById(id).orElseThrow().getStatus());
            assertNotNull(orders.findById(id).orElseThrow().getDeliveredTime());
            assertEquals("待确认完成", getBody("/api/v1/orders/" + id + "/custom-task", publisher).path("data").path("statusLabel").asText());
            var statusLogs = logs.findAll().stream().filter(l -> l.getOrderId().equals(id)).toList();
            assertEquals(1, statusLogs.stream().filter(l -> "DELIVERING".equals(l.getToStatus())).count());
            assertEquals(1, statusLogs.stream().filter(l -> "DELIVERED".equals(l.getToStatus())).count());
            assertTrue(settlements.findAll().stream().noneMatch(s -> s.getOrderId().equals(id)));
            org.springframework.test.util.ReflectionTestUtils.setField(orderService, "riderPercent", 10);
            try {
                var rows = getBody("/api/v1/rider/orders", rider).path("data").path("content");
                var row = java.util.stream.StreamSupport.stream(rows.spliterator(), false).filter(r -> r.path("id").asLong() == id).findFirst().orElseThrow();
                assertEquals(70000000, row.path("rewardFen").asInt()); assertEquals("CUSTOM", row.path("orderType").asText()); assertEquals(mode, row.path("taskMode").asText());
                assertEquals(70000000, getBody("/api/v1/orders/" + id + "/custom-task", rider).path("data").path("riderShareFen").asInt());
            } finally { org.springframework.test.util.ReflectionTestUtils.setField(orderService, "riderPercent", 70); }
            action(publisher, id, "confirm", 0); action(publisher, id, "confirm", 0); action(rider, id, "service-complete", 0);
            assertEquals(1, settlements.findAll().stream().filter(s -> s.getOrderId().equals(id)).count());
            assertEquals(70000000, settlements.findByOrderId(id).orElseThrow().getGrossFen());
        }
        assertEquals(210000000, walletAccounts.findById(rider.getId()).orElseThrow().getAvailableFen());
        org.mockito.Mockito.verifyNoInteractions(pricing);
    }

    @Test void hundredThousandAndMillionYuanAndOneFenBoundariesKeepFeesAndShareInRange() throws Exception {
        User publisher = user("USER", "13800138106");
        for (int price : List.of(1, 10000000, 100000000)) {
            long id = publish(publisher, "ONLINE", price);
            assertEquals(price, orders.findById(id).orElseThrow().getEstimateFee());
            var items = fees.findAll().stream().filter(f -> f.getOrderId().equals(id)).toList();
            assertEquals(1, items.size()); assertEquals("CUSTOM", items.get(0).getFeeType()); assertEquals(price, items.get(0).getAmount());
            action(publisher, id, "pay", 0);
            var paid = payments.findByOrderId(id).get(0); assertEquals((long) price * 70 / 100, paid.getShareRider()); assertEquals(price, paid.getShareRider() + paid.getSharePlatform());
        }
        var oldOrder = new com.feilv.dto.ApiDtos.OrderCreateReq("CUSTOM", "甲", "13800138000", "起点", null, null, "乙", "13900139000", "终点", null, null, 1000, 1, "试图绕过", "");
        assertEquals(400, assertThrows(com.feilv.common.BizException.class, () -> orderService.createCustom(publisher.getId(), oldOrder)).getCode());
        org.mockito.Mockito.verifyNoInteractions(pricing);
    }

    @Test void riderEligibilitySelfGrabAndSuccessfulPaymentAreEnforcedInServiceLayer() throws Exception {
        User publisher = rider("13800138107"), taker = rider("13800138108"), ordinary = user("USER", "13800138109");
        long id = publish(publisher, "ONSITE", 1000);
        assertEquals(4091, postBody("/api/v1/grab/" + id, taker, Map.of()).path("code").asInt());
        action(publisher, id, "pay", 0);
        assertEquals(403, postBody("/api/v1/grab/" + id, publisher, Map.of()).path("code").asInt());
        assertEquals(0, getBody("/api/v1/grab/list", publisher).path("data").size());
        assertThrows(com.feilv.common.BizException.class, () -> dispatch.grab(ordinary.getId(), id));
        var profile = riders.findByUserId(taker.getId()).orElseThrow(); profile.setStatus("FROZEN"); riders.saveAndFlush(profile);
        assertEquals(403, postBody("/api/v1/grab/" + id, taker, Map.of()).path("code").asInt());
        assertEquals(403, getBody("/api/v1/grab/list", taker).path("code").asInt());
        profile.setStatus("ACTIVE"); profile.setOnlineStatus(0); riders.saveAndFlush(profile);
        assertEquals(400, postBody("/api/v1/grab/" + id, taker, Map.of()).path("code").asInt());
        profile.setOnlineStatus(1); riders.saveAndFlush(profile); taker.setStatus(0); users.saveAndFlush(taker);
        assertThrows(com.feilv.common.BizException.class, () -> dispatch.grab(taker.getId(), id));
        taker.setStatus(1); users.saveAndFlush(taker);
        var order = orders.findById(id).orElseThrow(); payments.deleteAll();
        assertEquals(4090, postBody("/api/v1/grab/" + id, taker, Map.of()).path("code").asInt());
        assertEquals(0, getBody("/api/v1/grab/list", taker).path("data").size());
        assertEquals(com.feilv.enums.OrderStatus.WAIT_GRAB, order.getStatus());
    }

    @Test void legacyDeliveryCustomReadsOrderPriceAndKeepsPickupFlow() throws Exception {
        User publisher = user("USER", "13800138110"), rider = rider("13800138111");
        long id = publish(publisher, "ONSITE", 5000);
        var task = tasks.findByOrderId(id).orElseThrow(); task.setTaskMode("DELIVERY"); task.setPriceFen(null); task.setContact(null); task.setPhone(null); tasks.saveAndFlush(task);
        var order = orders.findById(id).orElseThrow(); order.setPickupCode("1234"); order.setRealFee(4999); orders.saveAndFlush(order);
        var detail = getBody("/api/v1/orders/" + id + "/custom-task", publisher).path("data");
        assertEquals("DELIVERY", detail.path("taskMode").asText()); assertEquals(4999, detail.path("priceFen").asInt()); assertEquals(4999, detail.path("amountFen").asInt());
        action(publisher, id, "pay", 0); assertEquals(0, postBody("/api/v1/grab/" + id, rider, Map.of()).path("code").asInt());
        action(rider, id, "service-start", 4090); action(rider, id, "service-complete", 4090);
        assertEquals(0, postBody("/api/v1/orders/" + id + "/pickup", rider, Map.of("pickupCode", "1234")).path("code").asInt());
        action(rider, id, "depart", 0); action(rider, id, "deliver", 0); action(publisher, id, "confirm", 0);
        assertEquals(com.feilv.enums.OrderStatus.FINISHED, orders.findById(id).orElseThrow().getStatus());
    }

    @Test void ungrantedAdminAndAnonymousCannotAccessTaskManagement() throws Exception {
        mvc.perform(get("/api/v1/task-tags")).andExpect(status().isUnauthorized());
        User user = user("USER", "13800138006");
        mvc.perform(get("/api/v1/admin/custom-tasks").header("Authorization", token(user))).andExpect(status().isForbidden());
        User admin = user("ADMIN", "limited-admin");
        mvc.perform(get("/api/v1/admin/custom-tasks").header("Authorization", token(admin))).andExpect(jsonPath("$.code").value(403));
    }
}
