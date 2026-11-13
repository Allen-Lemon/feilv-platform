package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.domain.WechatIdentity;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** 仅使用本地H2，真实事务与唯一索引兜底绑定竞争。 */
@DataJpaTest(showSql = false, properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.show-sql=false"})
@Import(UserProfileService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserProfilePersistenceTest {
    @MockBean WechatPhoneClient phone;
    @MockBean WechatConfigService settings;
    @Autowired UserProfileService profiles;
    @Autowired UserRepository users;
    @Autowired WechatIdentityRepository identities;
    @Autowired PlatformTransactionManager manager;

    private long create(String marker) {
        return new TransactionTemplate(manager).execute(status -> {
            User user = new User(); user.setNickname("微信用户");
            users.saveAndFlush(user);
            WechatIdentity identity = new WechatIdentity(); identity.setUserId(user.getId());
            identity.setAppId("profile-app"); identity.setOpenid(marker);
            identities.saveAndFlush(identity);
            return user.getId();
        });
    }

    @Test
    void concurrentOwnersCannotClaimSamePhoneOrTransferIdentity() throws Exception {
        long first = create("profile-first"), second = create("profile-second");
        var credentials = new WechatConfigService.LoginCredentials("profile-app", "profile-secret");
        when(settings.effectiveLogin()).thenReturn(credentials);
        var rendezvous = new CountDownLatch(2);
        when(phone.exchange(anyString(), eq(credentials))).thenAnswer(call -> {
            rendezvous.countDown();
            if (!rendezvous.await(5, TimeUnit.SECONDS)) throw new AssertionError("Both exchanges must run outside write transactions");
            return new WechatPhoneClient.Phone("profile-app", "13800138001");
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> bind(first, "code-first"));
            var b = executor.submit(() -> bind(second, "code-second"));
            int resultA = a.get(10, TimeUnit.SECONDS), resultB = b.get(10, TimeUnit.SECONDS);
            assertTrue(resultA == 0 && resultB == 409 || resultA == 409 && resultB == 0);
            long owner = users.findByMobile("13800138001").orElseThrow().getId();
            long loser = owner == first ? second : first;
            assertNull(users.findById(loser).orElseThrow().getMobile());
            assertEquals("USER", users.findById(owner).orElseThrow().getRole());
            assertTrue(identities.existsByUserIdAndAppId(first, "profile-app"));
            assertTrue(identities.existsByUserIdAndAppId(second, "profile-app"));
            assertEquals("138****8001", profiles.me(owner).mobile());
            when(phone.exchange("new-code", credentials)).thenReturn(new WechatPhoneClient.Phone("profile-app", "13800138001"));
            assertTrue(profiles.bindWechatPhone(owner, "new-code").mobileBound());
        } finally {
            executor.shutdownNow();
        }
    }

    private int bind(long userId, String code) {
        try { profiles.bindWechatPhone(userId, code); return 0; }
        catch (BizException e) { return e.getCode(); }
    }
}
