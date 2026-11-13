package com.feilv.controller;

import com.feilv.domain.*;
import com.feilv.repository.*;
import com.feilv.util.JwtUtil;
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
        "feilv.config-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class WechatConfigHttpTest {
    @Autowired TestRestTemplate rest;
    @Autowired UserRepository users;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminUserRoleRepository userRoles;
    @Autowired WechatConfigRepository configs;

    @Test void fullAppCanSaveMaskedConfigurationAndImmediatelyDisableWechatLogin() {
        User user = new User(); user.setMobile("config-http-admin"); user.setNickname("配置测试"); user.setRole("ADMIN");
        user = users.saveAndFlush(user);
        AdminAccount account = new AdminAccount(); account.setUserId(user.getId()); account.setUsername("config-http-admin");
        account.setPasswordHash(PasswordUtil.sha256("test-only-password")); account.setEnabled(1); accounts.saveAndFlush(account);
        AdminRole role = new AdminRole(); role.setCode("SUPER_ADMIN"); role.setName("超级管理员"); role.setEnabled(1); role = roles.saveAndFlush(role);
        AdminUserRole link = new AdminUserRole(); link.setUserId(user.getId()); link.setRoleId(role.getId()); userRoles.saveAndFlush(link);
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(JwtUtil.create(user.getId(), "ADMIN")); headers.setContentType(MediaType.APPLICATION_JSON);
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
        var disable = rest.exchange(endpoint + "/login", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false, "appId", "wx1234567890abcdef", "appSecret", "", "version", version), headers), JsonNode.class);
        assertEquals(0, disable.getBody().path("code").asInt());
        var login = rest.postForEntity("/api/v1/auth/wechat-login", Map.of("code", "never-sent-to-wechat", "agreed", true), JsonNode.class);
        assertEquals(503, login.getBody().path("code").asInt());
        assertTrue(login.getBody().path("msg").asText().contains("关闭"));
    }
}
