package com.feilv.controller;

import com.feilv.domain.*;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实鉴权/事务/H2与MockMvc集成；不使用远程数据库或微信接口。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-users-orders;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "feilv.wechat.app-id=", "feilv.wechat.app-secret="})
@AutoConfigureMockMvc
@Transactional
class AdminUsersOrdersHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @SpyBean UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminUserRoleRepository userRoles;
    @Autowired AdminMenuRepository menus;
    @Autowired AdminRoleMenuRepository roleMenus;
    @Autowired OpLogRepository logs;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    User admin;
    AdminAccount account;
    AdminRole role;
    AdminMenu usersMenu;
    String token;

    @BeforeEach
    void authorize() {
        admin = user("后台管理员", "ADMIN", "13900000000", 1);
        account = new AdminAccount(); account.setUserId(admin.getId()); account.setUsername("admin-users-test");
        account.setPasswordHash("unused-test-password-hash"); account = accounts.saveAndFlush(account);
        role = new AdminRole(); role.setCode("OPERATOR"); role.setName("运营"); role = roles.saveAndFlush(role);
        AdminUserRole link = new AdminUserRole(); link.setUserId(admin.getId()); link.setRoleId(role.getId()); userRoles.saveAndFlush(link);
        usersMenu = grant("users"); grant("orders");
        token = "Bearer " + JwtUtil.create(admin.getId(), "ADMIN");
    }

    @Test
    void filtersPageAndMasksOnlyCustomersWithStableOrdering() throws Exception {
        for (int i = 0; i < 17; i++) user("用户" + i, i % 2 == 0 ? "USER" : "RIDER", null, 1);
        User frozen = user("冻结骑手", "RIDER", "13800138001", 0);
        User bound = user("绑定用户", "USER", "13800138002", 1);
        bound.setAvatarUrl("/api/v1/avatars/test.png"); users.saveAndFlush(bound);
        user("非C端账号", "OTHER", null, 1);
        // 相同创建时间仍必须以ID倒序，跨页没有重复/漏项。
        jdbc.update("update t_user set created_time = ?", LocalDateTime.of(2026, 11, 14, 10, 0)); em.clear();
        JsonNode first = data(getUsers("")), second = data(getUsers("?page=1"));
        assertEquals(19, first.path("totalElements").asInt());
        assertEquals(15, first.path("size").asInt()); assertEquals(0, first.path("number").asInt());
        assertEquals(4, second.path("content").size());
        List<Long> ids = StreamSupport.stream(first.path("content").spliterator(), false).map(n -> n.path("userId").asLong()).toList();
        assertEquals(ids.stream().sorted(java.util.Comparator.reverseOrder()).toList(), ids);
        assertEquals(bound.getId().longValue(), ids.get(0));
        assertFalse(StreamSupport.stream(second.path("content").spliterator(), false).anyMatch(n -> ids.contains(n.path("userId").asLong())));
        JsonNode row = first.path("content").get(0);
        assertEquals("138****8002", row.path("mobile").asText()); assertTrue(row.path("mobileBound").asBoolean());
        assertEquals("/api/v1/avatars/test.png", row.path("avatarUrl").asText()); assertFalse(row.path("createdTime").isNull());
        assertEquals(8, row.size()); assertFalse(first.toString().contains("13800138002"));
        JsonNode filtered = data(getUsers("?status=0&mobileBound=true"));
        assertEquals(1, filtered.path("totalElements").asInt()); assertEquals(frozen.getId().longValue(), filtered.path("content").get(0).path("userId").asLong());
        assertEquals(17, data(getUsers("?mobileBound=false")).path("totalElements").asInt());
    }

    @Test
    void keywordEscapesWildcardsAndPhoneSearchIsExactNotSubstring() throws Exception {
        User literal = user("百分%下划_转义!反斜\\尾", "USER", "13800138001", 1);
        user("普通用户", "RIDER", "13900139001", 1);
        user("百分任意下划X转义!反斜\\尾", "USER", null, 1);
        for (String keyword : List.of("%", "_", "%下划_转义!", "反斜\\尾", "13800138001")) {
            var result = mvc.perform(get("/api/v1/admin/users").header("Authorization", token).param("keyword", keyword))
                    .andExpect(jsonPath("$.code").value(0)).andReturn();
            JsonNode content = data(result).path("content");
            if (!"反斜\\尾".equals(keyword)) {
                assertEquals(1, content.size(), keyword); assertEquals(literal.getId().longValue(), content.get(0).path("userId").asLong());
            } else assertEquals(2, content.size());
        }
        assertEquals(0, data(mvc.perform(get("/api/v1/admin/users").header("Authorization", token).param("keyword", "1380013")).andReturn())
                .path("totalElements").asInt());
        assertEquals(1, data(mvc.perform(get("/api/v1/admin/users").header("Authorization", token).param("keyword", "普通")).andReturn())
                .path("totalElements").asInt());
    }

    @Test
    void detailAndStatusProtectAdminLockAndAuditWithoutPhone() throws Exception {
        User target = user("可管理骑手", "RIDER", "13800138001", 1);
        String endpoint = "/api/v1/admin/users/" + target.getId();
        mvc.perform(get(endpoint).header("Authorization", token))
                .andExpect(jsonPath("$.data.mobile").value("138****8001"))
                .andExpect(jsonPath("$.data.role").value("RIDER"));
        clearInvocations(users);
        mvc.perform(post(endpoint + "/status").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.status").value(0));
        verify(users).findByIdForUpdate(target.getId());
        em.flush(); em.clear();
        assertEquals(0, users.findById(target.getId()).orElseThrow().getStatus());
        var audit = logs.findAll(); assertEquals(1, audit.size()); assertEquals("USER_STATUS", audit.get(0).getAction());
        assertEquals("userId=" + target.getId() + ", oldStatus=1, status=0", audit.get(0).getDetail());
        assertFalse(audit.get(0).getDetail().contains("138"));
        // 冻结前签发的JWT在后续请求也不可用（由实时拦截器保证）。
        mvc.perform(get("/api/v1/user/me").header("Authorization", "Bearer " + JwtUtil.create(target.getId(), "RIDER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users/" + admin.getId()).header("Authorization", token)).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/v1/admin/users/" + admin.getId() + "/status").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(1, users.findById(admin.getId()).orElseThrow().getStatus()); assertEquals(1, logs.count());
        mvc.perform(get("/api/v1/admin/users/99999999").header("Authorization", token)).andExpect(jsonPath("$.code").value(404));
        mvc.perform(post("/api/v1/admin/users/99999999/status").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void invalidQueriesAndStatusBodyNeverWrite() throws Exception {
        User target = user("正常用户", "USER", null, 1);
        for (String suffix : List.of("?page=-1", "?page=abc", "?page=2147483648", "?status=2", "?status=x", "?mobileBound=1")) {
            getUsers(suffix, 400);
        }
        for (String body : List.of("{}", "{\"status\":null}", "{\"status\":-1}", "{\"status\":2}")) {
            mvc.perform(post("/api/v1/admin/users/" + target.getId() + "/status").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value(400));
        }
        for (String suffix : List.of("?page=-1", "?page=foo", "?userId=-1", "?userId=abc", "?status=BOGUS")) {
            mvc.perform(get("/api/v1/admin/orders" + suffix).header("Authorization", token)).andExpect(jsonPath("$.code").value(400));
        }
        mvc.perform(get("/api/v1/admin/users/not-a-number").header("Authorization", token)).andExpect(jsonPath("$.code").value(400));
        assertEquals(0, logs.count()); assertEquals(1, users.findById(target.getId()).orElseThrow().getStatus());
    }

    @Test
    void realtimeMenuRoleAccountAndUserEnablementAndSuperAdmin() throws Exception {
        User customer = user("客户", "USER", null, 1);
        mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + JwtUtil.create(customer.getId(), "USER")))
                .andExpect(status().isForbidden());
        usersMenu.setEnabled(0); menus.saveAndFlush(usersMenu);
        getUsers("", 403);
        mvc.perform(get("/api/v1/admin/users/" + customer.getId()).header("Authorization", token)).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/v1/admin/users/" + customer.getId() + "/status").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(1, users.findById(customer.getId()).orElseThrow().getStatus());
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", token)).andExpect(jsonPath("$.code").value(0));
        role.setEnabled(0); roles.saveAndFlush(role); getUsers("", 403);
        role.setEnabled(1); role.setCode("SUPER_ADMIN"); roles.saveAndFlush(role);
        // 启用超级管理员不需要菜单授权。
        roleMenus.deleteAll(); roleMenus.flush(); getUsers("", 0);
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", token)).andExpect(jsonPath("$.code").value(0));
        account.setEnabled(0); accounts.saveAndFlush(account); getUsers("", 403);
        account.setEnabled(1); accounts.saveAndFlush(account);
        admin.setStatus(0); users.saveAndFlush(admin);
        mvc.perform(get("/api/v1/admin/users").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void ordersRequireTheirOwnMenuAndReturnFlatOriginalFieldsWithBatchUsers() throws Exception {
        User customer = user("下单用户", "USER", "13800138001", 1);
        customer.setAvatarUrl("/api/v1/avatars/order.png"); users.saveAndFlush(customer);
        OrderMain first = order(customer.getId(), "FIRST"), second = order(customer.getId(), "SECOND");
        OrderMain orphan = order(99999999L, "ORPHAN");
        jdbc.update("update t_order set created_time = ?", LocalDateTime.of(2026, 11, 14, 10, 0)); em.clear();
        clearInvocations(users);
        JsonNode result = data(mvc.perform(get("/api/v1/admin/orders").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0)).andReturn());
        verify(users, times(1)).findAllById(any());
        verify(users, never()).findById(customer.getId()); verify(users, never()).findById(99999999L);
        var rows = result.path("content"); assertEquals(3, rows.size());
        assertEquals(orphan.getId().longValue(), rows.get(0).path("id").asLong());
        assertEquals(99999999L, rows.get(0).path("userId").asLong());
        for (String field : List.of("userNickname", "userAvatarUrl", "userMobile")) assertEquals("", rows.get(0).path(field).asText());
        assertFalse(rows.get(0).path("userMobileBound").asBoolean());
        JsonNode row = rows.get(1); assertEquals(second.getId().longValue(), row.path("id").asLong());
        assertEquals("下单用户", row.path("userNickname").asText()); assertEquals("/api/v1/avatars/order.png", row.path("userAvatarUrl").asText());
        assertEquals("138****8001", row.path("userMobile").asText()); assertTrue(row.path("userMobileBound").asBoolean());
        for (var component : com.feilv.dto.ApiDtos.OrderVO.class.getRecordComponents()) assertTrue(row.has(component.getName()), component.getName());
        assertEquals("WAIT_PAY", row.path("status").asText()); assertFalse(row.has("statusCode")); assertEquals(21, row.size());
        assertFalse(result.toString().contains("13800138001")); assertFalse(row.has("user"));
        JsonNode filtered = data(mvc.perform(get("/api/v1/admin/orders").header("Authorization", token)
                .param("userId", customer.getId().toString()).param("status", "WAIT_PAY")).andReturn());
        assertEquals(2, filtered.path("totalElements").asInt());
        assertEquals(first.getId().longValue(), filtered.path("content").get(1).path("id").asLong());
        assertEquals(0, data(mvc.perform(get("/api/v1/admin/orders").header("Authorization", token)
                .param("userId", customer.getId().toString()).param("status", "FINISHED")).andReturn()).path("totalElements").asInt());
        assertEquals(0, data(mvc.perform(get("/api/v1/admin/orders").header("Authorization", token).param("page", "1")).andReturn()).path("content").size());
        menus.findAll().stream().filter(m -> "orders".equals(m.getCode())).forEach(m -> { m.setEnabled(0); menus.saveAndFlush(m); });
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", token)).andExpect(jsonPath("$.code").value(403));
        for (String action : List.of("cancel", "complete")) {
            mvc.perform(post("/api/v1/admin/orders/" + first.getId() + "/" + action).header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"无授权不能干预\"}"))
                    .andExpect(jsonPath("$.code").value(403));
        }
        assertEquals(com.feilv.enums.OrderStatus.WAIT_PAY, orders.findById(first.getId()).orElseThrow().getStatus());
        assertEquals(0, logs.count());
    }

    @Test
    void nullBlankAndLegacyMobilesNeverLeakAndCustomerOrderDtoUnchanged() throws Exception {
        user("未绑定", "USER", null, 1); user("空号", "RIDER", " ", 1);
        User legacy = user("历史数据", "USER", "12345", 1);
        JsonNode result = data(getUsers("?mobileBound=false")); assertEquals(2, result.path("totalElements").asInt());
        for (JsonNode row : result.path("content")) {
            assertEquals("", row.path("mobile").asText()); assertEquals("", row.path("avatarUrl").asText()); assertFalse(row.path("mobileBound").asBoolean());
        }
        mvc.perform(get("/api/v1/admin/users/" + legacy.getId()).header("Authorization", token))
                .andExpect(jsonPath("$.data.mobile").value("****")).andExpect(jsonPath("$.data.mobileBound").value(true));
        assertEquals(16, com.feilv.dto.ApiDtos.OrderVO.class.getRecordComponents().length);
    }

    private MvcResult getUsers(String query) throws Exception { return getUsers(query, 0); }
    private MvcResult getUsers(String query, int code) throws Exception {
        return mvc.perform(get("/api/v1/admin/users" + query).header("Authorization", token)).andExpect(jsonPath("$.code").value(code)).andReturn();
    }
    private JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()).path("data"); }
    private User user(String nickname, String role, String mobile, int status) {
        User user = new User(); user.setNickname(nickname); user.setRole(role); user.setMobile(mobile); user.setStatus(status);
        return users.saveAndFlush(user);
    }
    private AdminMenu grant(String code) {
        AdminMenu menu = new AdminMenu(); menu.setCode(code); menu.setName(code); menu = menus.saveAndFlush(menu);
        AdminRoleMenu link = new AdminRoleMenu(); link.setRoleId(role.getId()); link.setMenuId(menu.getId()); roleMenus.saveAndFlush(link);
        return menu;
    }
    private OrderMain order(long userId, String orderNo) {
        OrderMain order = new OrderMain(); order.setUserId(userId); order.setOrderNo(orderNo); order.setOrderType("SEND");
        order.setFromContact("寄件人"); order.setFromPhone("13800138001"); order.setFromAddress("起点");
        order.setToContact("收件人"); order.setToPhone("13900139001"); order.setToAddress("终点");
        order.setGoodsDesc("文件"); order.setEstimateFee(500); order.setRealFee(600); order.setPickupCode("1234");
        return orders.saveAndFlush(order);
    }
}
