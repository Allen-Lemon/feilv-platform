package com.feilv.service;

import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 本地 H2 持久化回归，禁止接触远程业务数据库。 */
@DataJpaTest(properties = {com.feilv.controller.TestAuth.TEST_SECRET_PROPERTY, "spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import({WechatLoginService.class, AuthTokenService.class})
class WechatLoginPersistenceTest {
    @MockBean WechatSessionClient client;
    @Autowired WechatLoginService service;
    @Autowired UserRepository users;
    @Autowired WechatIdentityRepository identities;

    @Test
    void persistsAndReusesWechatIdentityWithoutFakeMobile() {
        long initialUsers = users.count();
        when(client.exchange("first")).thenReturn(new WechatSessionClient.Session("app-a", "openid-a", null));
        when(client.exchange("again")).thenReturn(new WechatSessionClient.Session("app-a", "openid-a", "union-a"));
        when(client.exchange("second-user")).thenReturn(new WechatSessionClient.Session("app-a", "openid-b", null));
        var first = service.login("first", true);
        var again = service.login("again", true);
        var second = service.login("second-user", true);
        assertEquals(first.userId(), again.userId());
        assertNotEquals(first.userId(), second.userId());
        assertEquals(initialUsers + 2, users.count());
        assertNull(users.findById(first.userId()).orElseThrow().getMobile());
        assertNull(users.findById(second.userId()).orElseThrow().getMobile());
        assertEquals("union-a", identities.findByAppIdAndOpenid("app-a", "openid-a").orElseThrow().getUnionid());
    }
}
