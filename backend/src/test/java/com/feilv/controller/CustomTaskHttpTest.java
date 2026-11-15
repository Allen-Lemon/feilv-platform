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
    private Map<String, Object> request(List<Long> ids) { return new LinkedHashMap<>(Map.of("title", "自定义任务标题", "content", "任务详细说明", "tagIds", ids, "order", order())); }
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
        assertEquals(0, postBody("/api/v1/custom-tasks/quote", user, Map.of("distanceM", 2000, "weightKg", 1)).path("code").asInt());
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

    @Test void ungrantedAdminAndAnonymousCannotAccessTaskManagement() throws Exception {
        mvc.perform(get("/api/v1/task-tags")).andExpect(status().isUnauthorized());
        User user = user("USER", "13800138006");
        mvc.perform(get("/api/v1/admin/custom-tasks").header("Authorization", token(user))).andExpect(status().isForbidden());
        User admin = user("ADMIN", "limited-admin");
        mvc.perform(get("/api/v1/admin/custom-tasks").header("Authorization", token(admin))).andExpect(jsonPath("$.code").value(403));
    }
}
