package com.feilv.controller;

import com.feilv.domain.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import com.feilv.service.WalletAccountingService;
import com.feilv.util.JwtUtil;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:wallet-http;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never", "feilv.wechat.app-id=", "feilv.wechat.app-secret="})
@AutoConfigureMockMvc
@Transactional
class WalletHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RiderRepository riders;
    @Autowired OrderRepository orders;
    @Autowired PayRecordRepository payments;
    @Autowired WalletAccountRepository accounts;
    @Autowired WalletAccountingService accounting;
    @Autowired WalletWithdrawalRepository withdrawals;
    @Autowired AdminAccountRepository adminAccounts;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminUserRoleRepository userRoles;
    @Autowired AdminMenuRepository menus;
    @Autowired AdminRoleMenuRepository roleMenus;
    User publisher, riderUser, outsider, admin;
    Rider rider;
    AdminMenu walletMenu, withdrawalMenu;
    String riderToken, publisherToken, outsiderToken, adminToken;

    @BeforeEach void setup() {
        publisher = user("发布者", "USER", "13800138000"); riderUser = user("骑手13900139000", "RIDER", "13900139000");
        outsider = user("其他用户", "USER", null); admin = user("钱包审核员", "ADMIN", null);
        rider = new Rider(); rider.setUserId(riderUser.getId()); rider.setName("钱包骑手"); rider = riders.saveAndFlush(rider);
        AdminAccount account = new AdminAccount(); account.setUserId(admin.getId()); account.setUsername("wallet-http-admin"); account.setPasswordHash("unused"); adminAccounts.saveAndFlush(account);
        AdminRole role = new AdminRole(); role.setCode("WALLET_TEST"); role.setName("钱包测试"); role = roles.saveAndFlush(role);
        AdminUserRole ur = new AdminUserRole(); ur.setUserId(admin.getId()); ur.setRoleId(role.getId()); userRoles.saveAndFlush(ur);
        walletMenu = grant("wallets", role.getId()); withdrawalMenu = grant("withdrawals", role.getId());
        riderToken = token(riderUser); publisherToken = token(publisher); outsiderToken = token(outsider); adminToken = token(admin);
    }

    @Test void walletReadsAreMockNoStoreAndDoNotCreateOrBackfillAnAccount() throws Exception {
        finishedOrder(800); // Merely reading an old FINISHED order/account must not trigger settlement.
        JsonNode wallet = data(mvc.perform(get("/api/v1/wallet").header("Authorization", riderToken))
                .andExpect(jsonPath("$.code").value(0)).andExpect(header().string("Cache-Control", "no-store")).andReturn());
        assertEquals(riderUser.getId().longValue(), wallet.path("userId").asLong()); assertEquals("MOCK", wallet.path("channel").asText());
        for (String field : List.of("availableFen", "frozenFen", "debtFen", "totalIncomeFen", "totalWithdrawnFen")) assertEquals(0, wallet.path(field).asLong());
        assertFalse(wallet.path("withdrawAllowed").asBoolean()); assertFalse(accounts.existsById(riderUser.getId()));
        for (String path : List.of("entries", "withdrawals")) mvc.perform(get("/api/v1/wallet/" + path).header("Authorization", riderToken))
                .andExpect(jsonPath("$.data.content").isEmpty()).andExpect(jsonPath("$.data.size").value(15)).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void fullWithdrawalContractReplayApprovalAndOwnership() throws Exception {
        fund(800); JsonNode pending = request(500, "http-unique-key", 0); long id = pending.path("id").asLong();
        assertEquals(riderUser.getId().longValue(), pending.path("userId").asLong()); assertEquals(500, pending.path("amountFen").asLong());
        assertEquals("PENDING", pending.path("status").asText()); assertEquals("http-unique-key", pending.path("requestId").asText());
        assertEquals("MOCK", pending.path("channel").asText()); assertFalse(pending.path("createdTime").isNull());
        assertTrue(pending.path("reviewedBy").isNull()); assertTrue(pending.path("reviewedTime").isNull());
        assertEquals(id, request(500, "http-unique-key", 0).path("id").asLong()); request(501, "http-unique-key", 409);
        mvc.perform(get("/api/v1/wallet").header("Authorization", riderToken)).andExpect(jsonPath("$.data.availableFen").value(300))
                .andExpect(jsonPath("$.data.frozenFen").value(500)).andExpect(jsonPath("$.data.totalIncomeFen").value(800));
        mvc.perform(get("/api/v1/wallet/entries").header("Authorization", riderToken)).andExpect(jsonPath("$.data.content[0].type").value("WITHDRAWAL_FREEZE"))
                .andExpect(jsonPath("$.data.content[0].amountFen").value(-500)).andExpect(jsonPath("$.data.content[0].availableAfterFen").value(300))
                .andExpect(jsonPath("$.data.content[0].frozenAfterFen").value(500)).andExpect(jsonPath("$.data.content[0].withdrawalId").value(id));
        mvc.perform(get("/api/v1/wallet/withdrawals").header("Authorization", publisherToken)).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/v1/wallet/entries").header("Authorization", outsiderToken).param("userId", riderUser.getId().toString()))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        JsonNode approved = review(id, "APPROVED", "MOCK审核电话13800138000", 0);
        assertEquals("APPROVED", approved.path("status").asText()); assertEquals(admin.getId().longValue(), approved.path("reviewedBy").asLong());
        assertFalse(approved.toString().contains("13800138000")); assertTrue(approved.path("reason").asText().contains("138****8000"));
        assertFalse(approved.path("reviewedTime").isNull()); review(id, "APPROVED", "重复审核", 409); review(id, "REJECTED", "不可改判", 409);
        assertEquals(id, request(500, "http-unique-key", 0).path("id").asLong());
        mvc.perform(get("/api/v1/wallet").header("Authorization", riderToken)).andExpect(jsonPath("$.data.availableFen").value(300))
                .andExpect(jsonPath("$.data.frozenFen").value(0)).andExpect(jsonPath("$.data.totalWithdrawnFen").value(500));
    }

    @Test void amountMustBeStrictJsonIntegerLongPositiveAndWithinCapNoIdentityFields() throws Exception {
        fund(800);
        for (String amount : List.of("-1", "0", "1.0", "1.5", "1e2", "\"1\"", "true", "null", "9223372036854775808", "100000001", "{}", "[]")) {
            rawRequest("{\"amountFen\":" + amount + ",\"requestId\":\"invalid-amount-key\"}", 400);
        }
        rawRequest("{\"requestId\":\"missing-amount\"}", 400);
        for (String key : List.of("short", "x".repeat(65), "1234567 ", "1234567/", "13800138000@")) {
            rawRequest(json.writeValueAsString(Map.of("amountFen", 1, "requestId", key)), 400);
        }
        for (String extra : List.of("\"userId\":" + publisher.getId(), "\"role\":\"ADMIN\"", "\"status\":\"APPROVED\"", "\"channel\":\"WECHAT\"")) {
            rawRequest("{\"amountFen\":1,\"requestId\":\"spoof-test-key\"," + extra + "}", 400);
        }
        mvc.perform(get("/api/v1/wallet").header("Authorization", riderToken)).andExpect(jsonPath("$.data.availableFen").value(800))
                .andExpect(jsonPath("$.data.frozenFen").value(0));
    }

    @Test void adminCannotUseCustomerWalletAndUnboundFrozenOrStaleRoleTokensCannotWithdraw() throws Exception {
        fund(800);
        mvc.perform(get("/api/v1/wallet").header("Authorization", adminToken)).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/v1/wallet/withdrawals").header("Authorization", outsiderToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountFen\":1,\"requestId\":\"no-phone-key\"}")).andExpect(jsonPath("$.code").value(403));
        riderUser.setMobile(null); users.saveAndFlush(riderUser); request(1, "changed-phone-key", 403);
        riderUser.setMobile("13900139000"); riderUser.setStatus(0); users.saveAndFlush(riderUser);
        mvc.perform(get("/api/v1/wallet").header("Authorization", riderToken)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/wallet/withdrawals").header("Authorization", riderToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountFen\":1,\"requestId\":\"frozen-token-key\"}")).andExpect(status().isForbidden());
        riderUser.setStatus(1); riderUser.setRole("USER"); users.saveAndFlush(riderUser);
        mvc.perform(get("/api/v1/wallet").header("Authorization", riderToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/wallet").header("Authorization", token(riderUser))).andExpect(jsonPath("$.code").value(0));
    }

    @Test void adminWalletsMaskPhoneAndNicknameAndPaginateBothZeroAndExistingWallets() throws Exception {
        fund(800);
        for (int i = 0; i < 17; i++) user("更多用户" + i, "USER", null);
        JsonNode first = adminData("/api/v1/admin/wallets"), second = adminData("/api/v1/admin/wallets?page=1");
        assertEquals(15, first.path("size").asInt()); assertEquals(20, first.path("totalElements").asInt()); assertEquals(5, second.path("content").size());
        JsonNode filtered = adminData("/api/v1/admin/wallets?userId=" + riderUser.getId());
        assertEquals(1, filtered.path("totalElements").asInt()); JsonNode wallet = filtered.path("content").get(0);
        assertEquals("139****9000", wallet.path("mobile").asText()); assertFalse(wallet.path("nickname").asText().contains("13900139000"));
        assertEquals("RIDER", wallet.path("role").asText()); assertEquals(1, wallet.path("status").asInt());
        assertEquals("MOCK", wallet.path("channel").asText()); assertEquals(800, wallet.path("availableFen").asLong()); assertTrue(wallet.path("withdrawAllowed").asBoolean());
        assertEquals(0, adminData("/api/v1/admin/wallets?userId=" + admin.getId()).path("totalElements").asInt());
        assertFalse(first.toString().contains("13800138000")); assertFalse(filtered.toString().contains("13900139000"));
    }

    @Test void pagesAndStatusFiltersAreStableSizeFifteenAndInputsAreValidated() throws Exception {
        fund(800); long last = 0;
        for (int i = 0; i < 17; i++) last = request(1, "paged-request-" + i, 0).path("id").asLong();
        JsonNode first = mineData("/api/v1/wallet/withdrawals"), second = mineData("/api/v1/wallet/withdrawals?page=1");
        assertEquals(17, first.path("totalElements").asLong()); assertEquals(15, first.path("size").asInt()); assertEquals(2, second.path("content").size());
        assertEquals(last, first.path("content").get(0).path("id").asLong());
        JsonNode entries = mineData("/api/v1/wallet/entries"), entriesSecond = mineData("/api/v1/wallet/entries?page=1");
        assertEquals(18, entries.path("totalElements").asLong()); assertEquals(3, entriesSecond.path("content").size());
        long previous = Long.MAX_VALUE; for (JsonNode row : first.path("content")) { assertTrue(row.path("id").asLong() < previous); previous = row.path("id").asLong(); }
        for (JsonNode row : second.path("content")) { assertTrue(row.path("id").asLong() < previous); previous = row.path("id").asLong(); }
        review(last, "REJECTED", "MOCK驳回", 0);
        assertEquals(1, adminData("/api/v1/admin/withdrawals?status=REJECTED&userId=" + riderUser.getId()).path("totalElements").asLong());
        assertEquals(16, adminData("/api/v1/admin/withdrawals?status=PENDING").path("totalElements").asLong());
        assertEquals(17, adminData("/api/v1/admin/withdrawals?status=").path("totalElements").asLong());
        for (String path : List.of("/api/v1/wallet/entries?page=-1", "/api/v1/wallet/withdrawals?page=abc", "/api/v1/wallet/entries?page=2147483648"))
            mvc.perform(get(path).header("Authorization", riderToken)).andExpect(jsonPath("$.code").value(400));
        for (String path : List.of("/api/v1/admin/wallets?userId=abc", "/api/v1/admin/wallets?userId=0", "/api/v1/admin/wallets?page=-1",
                "/api/v1/admin/withdrawals?status=UNKNOWN", "/api/v1/admin/withdrawals?userId=-1", "/api/v1/admin/wallets/abc/entries"))
            mvc.perform(get(path).header("Authorization", adminToken)).andExpect(jsonPath("$.code").value(400));
    }

    @Test void realtimeIndependentMenusAndAccountRoleDisablingGuardEveryAdminEndpoint() throws Exception {
        fund(800); long id = request(500, "menu-check-key", 0).path("id").asLong();
        walletMenu.setEnabled(0); menus.saveAndFlush(walletMenu);
        for (String path : List.of("/api/v1/admin/wallets", "/api/v1/admin/wallets/" + riderUser.getId() + "/entries"))
            mvc.perform(get(path).header("Authorization", adminToken)).andExpect(jsonPath("$.code").value(403));
        adminData("/api/v1/admin/withdrawals");
        withdrawalMenu.setEnabled(0); menus.saveAndFlush(withdrawalMenu);
        mvc.perform(get("/api/v1/admin/withdrawals").header("Authorization", adminToken)).andExpect(jsonPath("$.code").value(403)); review(id, "APPROVED", "无菜单", 403);
        walletMenu.setEnabled(1); withdrawalMenu.setEnabled(1); menus.saveAndFlush(walletMenu); menus.saveAndFlush(withdrawalMenu);
        AdminAccount account = adminAccounts.findAll().stream().filter(a -> a.getUserId().equals(admin.getId())).findFirst().orElseThrow(); account.setEnabled(0); adminAccounts.saveAndFlush(account);
        mvc.perform(get("/api/v1/admin/wallets").header("Authorization", adminToken)).andExpect(jsonPath("$.code").value(403));
        review(id, "APPROVED", "账号已停用", 403); assertEquals("PENDING", withdrawals.findById(id).orElseThrow().getStatus());
        mvc.perform(get("/api/v1/admin/wallets").header("Authorization", riderToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/withdrawals")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/wallet")).andExpect(status().isUnauthorized());
    }

    @Test void reviewRequiresReasonTerminalDecisionAndRejectsExtraIdentityOrBalanceFields() throws Exception {
        fund(800); long id = request(500, "review-input-key", 0).path("id").asLong();
        for (String decision : List.of("PENDING", "DONE", "approved", "")) review(id, decision, "不合法", 400);
        for (String reason : List.of("", " ", "x".repeat(256))) review(id, "APPROVED", reason, 400);
        mvc.perform(post("/api/v1/admin/withdrawals/" + id + "/review").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\",\"reason\":\"MOCK\",\"reviewedBy\":1,\"amountFen\":1}")).andExpect(jsonPath("$.code").value(400));
        assertEquals("PENDING", withdrawals.findById(id).orElseThrow().getStatus());
        review(id, "REJECTED", "MOCK余额恢复", 0);
        mvc.perform(get("/api/v1/wallet").header("Authorization", riderToken)).andExpect(jsonPath("$.data.availableFen").value(800))
                .andExpect(jsonPath("$.data.totalIncomeFen").value(800)).andExpect(jsonPath("$.data.totalWithdrawnFen").value(0));
        mvc.perform(get("/api/v1/wallet/entries").header("Authorization", riderToken)).andExpect(jsonPath("$.data.content[0].amountFen").value(500))
                .andExpect(jsonPath("$.data.content[0].type").value("WITHDRAWAL_REJECTED"));
    }

    @Test void arbitraryRechargeOrBalanceMutationEndpointsDoNotExist() throws Exception {
        fund(800);
        for (String path : List.of("/api/v1/wallet/recharge", "/api/v1/admin/wallets/" + riderUser.getId() + "/balance", "/api/v1/admin/wallets/" + riderUser.getId() + "/recharge"))
            mvc.perform(post(path).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON).content("{\"amountFen\":999999}"))
                    .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/wallet").header("Authorization", riderToken)).andExpect(jsonPath("$.data.availableFen").value(800));
    }

    private void fund(int share) { accounting.settleFinishedOrder(finishedOrder(share).getId()); }
    private JsonNode request(long amount, String key, int code) throws Exception { return rawRequest(json.writeValueAsString(Map.of("amountFen", amount, "requestId", key)), code); }
    private JsonNode rawRequest(String body, int code) throws Exception {
        return data(mvc.perform(post("/api/v1/wallet/withdrawals").header("Authorization", riderToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(code)).andExpect(header().string("Cache-Control", "no-store")).andReturn());
    }
    private JsonNode review(long id, String decision, String reason, int code) throws Exception {
        return data(mvc.perform(post("/api/v1/admin/withdrawals/" + id + "/review").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("decision", decision, "reason", reason))))
                .andExpect(jsonPath("$.code").value(code)).andExpect(header().string("Cache-Control", "no-store")).andReturn());
    }
    private JsonNode mineData(String path) throws Exception { return data(mvc.perform(get(path).header("Authorization", riderToken)).andExpect(jsonPath("$.code").value(0)).andReturn()); }
    private JsonNode adminData(String path) throws Exception { return data(mvc.perform(get(path).header("Authorization", adminToken)).andExpect(jsonPath("$.code").value(0)).andExpect(header().string("Cache-Control", "no-store")).andReturn()); }
    private JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()).path("data"); }
    private User user(String name, String role, String mobile) { User u = new User(); u.setNickname(name); u.setRole(role); u.setMobile(mobile); return users.saveAndFlush(u); }
    private String token(User user) { return "Bearer " + JwtUtil.create(user.getId(), user.getRole()); }
    private AdminMenu grant(String code, long roleId) { AdminMenu m = new AdminMenu(); m.setCode(code); m.setName(code); m = menus.saveAndFlush(m); AdminRoleMenu rm = new AdminRoleMenu(); rm.setRoleId(roleId); rm.setMenuId(m.getId()); roleMenus.saveAndFlush(rm); return m; }
    private OrderMain finishedOrder(int share) {
        OrderMain o = new OrderMain(); o.setOrderNo("W-HTTP-" + UUID.randomUUID()); o.setOrderType("SEND"); o.setUserId(publisher.getId()); o.setRiderId(rider.getId()); o.setStatus(OrderStatus.FINISHED);
        o.setFromContact("寄件人"); o.setFromPhone("13800138000"); o.setFromAddress("起点"); o.setToContact("收件人"); o.setToPhone("13900139000"); o.setToAddress("终点"); o = orders.saveAndFlush(o);
        PayRecord p = new PayRecord(); p.setPayNo("W-HTTP-P-" + UUID.randomUUID()); p.setOrderId(o.getId()); p.setChannel("MOCK"); p.setAmount(share + 200); p.setShareRider(share); p.setSharePlatform(200); payments.saveAndFlush(p); return o;
    }
}
