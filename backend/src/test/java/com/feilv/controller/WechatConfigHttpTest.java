package com.feilv.controller;

import com.feilv.domain.*;
import com.feilv.repository.*;
import com.feilv.service.AuthTokenService;
import com.feilv.util.PasswordUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP随机端口+隔离H2，禁止访问远程数据库或微信网络。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:wechat-config-http;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "feilv.wechat.app-id=", "feilv.wechat.app-secret=",
        "feilv.wechat.rider-app-id=", "feilv.wechat.rider-app-secret=", TestAuth.TEST_SECRET_PROPERTY,
        "feilv.config-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class WechatConfigHttpTest {
    @Autowired TestRestTemplate rest;
    @Autowired UserRepository users;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminUserRoleRepository userRoles;
    @Autowired WechatConfigRepository configs;
    @Autowired AuthTokenService tokens;
    @Autowired javax.sql.DataSource dataSource;

    @Test void fullAppCanSaveMaskedConfigurationAndImmediatelyDisableWechatLogin() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:wechat-config-http"));
        }
        User user = new User(); user.setMobile("config-http-admin"); user.setNickname("配置测试"); user.setRole("ADMIN");
        user = users.saveAndFlush(user);
        AdminAccount account = new AdminAccount(); account.setUserId(user.getId()); account.setUsername("config-http-admin");
        account.setPasswordHash(PasswordUtil.sha256("test-only-password")); account.setEnabled(1); accounts.saveAndFlush(account);
        AdminRole role = new AdminRole(); role.setCode("SUPER_ADMIN"); role.setName("超级管理员"); role.setEnabled(1); role = roles.saveAndFlush(role);
        AdminUserRole link = new AdminUserRole(); link.setUserId(user.getId()); link.setRoleId(role.getId()); userRoles.saveAndFlush(link);
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(tokens.createAdmin(user.getId())); headers.setContentType(MediaType.APPLICATION_JSON);
        String endpoint = "/api/v1/admin/system/wechat";
        assertEquals(HttpStatus.UNAUTHORIZED, rest.getForEntity(endpoint, JsonNode.class).getStatusCode());
        var before = rest.exchange(endpoint, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertEquals(0, before.getBody().path("code").asInt()); assertTrue(before.getBody().path("data").path("encryptionReady").asBoolean());
        String secret = "0123456789abcdef0123456789abcdef";
        var save = rest.exchange(endpoint + "/login", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", true, "appId", "wx1234567890abcdef", "appSecret", secret), headers), JsonNode.class);
        assertEquals(0, save.getBody().path("code").asInt());
        long version = save.getBody().path("data").path("version").asLong();
        var after = rest.exchange(endpoint, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertFalse(after.getBody().contains(secret)); assertFalse(after.getBody().contains("v1:"));
        assertNotEquals(secret, configs.findById("LOGIN").orElseThrow().getAppSecretEncrypted());
        var validate = rest.exchange(endpoint + "/validate", HttpMethod.POST,
                new HttpEntity<>(Map.of("section", "LOGIN"), headers), JsonNode.class);
        assertTrue(validate.getBody().path("data").path("valid").asBoolean());
        assertTrue(before.getHeaders().getCacheControl().contains("no-store"));
        assertTrue(before.getBody().path("data").has("riderLogin"));
        assertFalse(before.getBody().path("data").path("riderLogin").path("enabled").asBoolean());
        var missingRider = rest.exchange(endpoint + "/validate", HttpMethod.POST,
                new HttpEntity<>(Map.of("section", "LOGIN", "clientType", "RIDER"), headers), JsonNode.class);
        assertFalse(missingRider.getBody().path("data").path("valid").asBoolean());
        String riderSecret = "abcdef0123456789abcdef0123456789";
        var rider = rest.exchange(endpoint + "/login/RIDER", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", true, "appId", "wxabcdef1234567890", "appSecret", riderSecret), headers), JsonNode.class);
        assertEquals(0, rider.getBody().path("code").asInt());
        long riderVersion = rider.getBody().path("data").path("version").asLong();
        assertNotEquals(secret, configs.findById("LOGIN_RIDER").orElseThrow().getAppSecretEncrypted());
        var duplicate = rest.exchange(endpoint + "/login/RIDER", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", true, "appId", "wx1234567890abcdef", "appSecret", riderSecret, "version", riderVersion), headers), JsonNode.class);
        assertEquals(400, duplicate.getBody().path("code").asInt());
        assertEquals("wxabcdef1234567890", configs.findById("LOGIN_RIDER").orElseThrow().getAppId());
        var stale = rest.exchange(endpoint + "/login/RIDER", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false, "appId", "wxabcdef1234567890", "version", riderVersion + 1), headers), JsonNode.class);
        assertEquals(409, stale.getBody().path("code").asInt());
        var invalidClient = rest.exchange(endpoint + "/login/rider", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false), headers), JsonNode.class);
        assertEquals(400, invalidClient.getBody().path("code").asInt());
        var invalidPayment = rest.exchange(endpoint + "/validate", HttpMethod.POST,
                new HttpEntity<>(Map.of("section", "PAYMENT", "clientType", "RIDER"), headers), JsonNode.class);
        assertEquals(400, invalidPayment.getBody().path("code").asInt());
        var validRider = rest.exchange(endpoint + "/validate", HttpMethod.POST,
                new HttpEntity<>(Map.of("section", "LOGIN", "clientType", "RIDER"), headers), JsonNode.class);
        assertTrue(validRider.getBody().path("data").path("valid").asBoolean());
        var both = rest.exchange(endpoint, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertFalse(both.getBody().contains(secret)); assertFalse(both.getBody().contains(riderSecret)); assertFalse(both.getBody().contains("v1:"));
        var disable = rest.exchange(endpoint + "/login", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false, "appId", "wx1234567890abcdef", "appSecret", "", "version", version), headers), JsonNode.class);
        assertEquals(0, disable.getBody().path("code").asInt());
        var login = rest.postForEntity("/api/v1/auth/wechat-login", Map.of("code", "never-sent-to-wechat", "agreed", true), JsonNode.class);
        assertEquals(503, login.getBody().path("code").asInt());
        assertTrue(login.getBody().path("msg").asText().contains("关闭"));
    }
}
