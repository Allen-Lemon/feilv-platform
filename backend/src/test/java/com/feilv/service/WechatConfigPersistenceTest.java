package com.feilv.service;

import com.feilv.common.UserContext;
import com.feilv.dto.WechatConfigDtos.LoginRequest;
import com.feilv.repository.WechatConfigRepository;
import com.feilv.util.ConfigSecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:wechat-config-persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "feilv.wechat.rider-app-id=", "feilv.wechat.rider-app-secret=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "feilv.wechat.app-id=", "feilv.wechat.app-secret=",
        "feilv.config-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Import({WechatConfigService.class, ConfigSecretCipher.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(isolation = Isolation.SERIALIZABLE)
class WechatConfigPersistenceTest {
    @MockBean AuditService audit;
    @Autowired WechatConfigService service;
    @Autowired WechatConfigRepository repo;
    @Autowired javax.sql.DataSource dataSource;
    @Test void riderAndUserPersistSeparateVersionsAndExactCipherContexts() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:wechat-config-persistence"));
        }
        UserContext.set(new UserContext.CurrentUser(1, "ADMIN"));
        try {
            var user = service.saveLogin(new LoginRequest(true, "wx1234567890abcdef", "0123456789abcdef0123456789abcdef", false, null));
            var rider = service.saveLogin("RIDER", new LoginRequest(true, "wxabcdef1234567890", "a".repeat(32), false, null));
            var cipher = new ConfigSecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            String userCipher = repo.findById("LOGIN").orElseThrow().getAppSecretEncrypted();
            String riderCipher = repo.findById("LOGIN_RIDER").orElseThrow().getAppSecretEncrypted();
            assertEquals("0123456789abcdef0123456789abcdef", cipher.decrypt(userCipher, "LOGIN.appSecret"));
            assertEquals("a".repeat(32), cipher.decrypt(riderCipher, "LOGIN_RIDER.appSecret"));
            assertThrows(com.feilv.common.BizException.class, () -> cipher.decrypt(riderCipher, "LOGIN.appSecret"));
            var updated = service.saveLogin("RIDER", new LoginRequest(false, "wxabcdef1234567890", "", true, rider.version()));
            assertTrue(updated.version() > rider.version());
            assertEquals(user.version(), repo.findById("LOGIN").orElseThrow().getVersion());
            assertEquals(userCipher, repo.findById("LOGIN").orElseThrow().getAppSecretEncrypted());
            assertNull(repo.findById("LOGIN_RIDER").orElseThrow().getAppSecretEncrypted());
            assertTrue(service.overview().login().enabled()); assertFalse(service.overview().riderLogin().enabled());
        } finally { UserContext.clear(); }
    }
    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentRiderEditsCannotBothOverwriteTheSameExpectedVersion() throws Exception {
        repo.deleteAll();
        com.feilv.dto.WechatConfigDtos.LoginView initial;
        UserContext.set(new UserContext.CurrentUser(1, "ADMIN"));
        try {
            initial = service.saveLogin("RIDER", new LoginRequest(true, "wxabcdef1234567890", "a".repeat(32), false, null));
        } finally { UserContext.clear(); }
        var start = new java.util.concurrent.CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var edit = (java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                UserContext.set(new UserContext.CurrentUser(1, "ADMIN"));
                try {
                    service.saveLogin("RIDER", new LoginRequest(false, "wxabcdef1234567890", "", false, initial.version()));
                    return true;
                } catch (com.feilv.common.BizException e) {
                    assertEquals(409, e.getCode()); return false;
                } catch (org.springframework.dao.ConcurrencyFailureException e) {
                    return false;
                } finally { UserContext.clear(); }
            };
            var first = workers.submit(edit); var second = workers.submit(edit);
            start.countDown();
            int successes = (first.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(initial.version() + 1, repo.findById("LOGIN_RIDER").orElseThrow().getVersion());
            assertFalse(repo.existsById("LOGIN"));
        } finally {
            workers.shutdownNow(); workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); repo.deleteAll();
        }
    }
    @Test void storesCiphertextAndUpdatesVersionInRealPersistence() {
        UserContext.set(new UserContext.CurrentUser(1, "ADMIN"));
        try {
            var first = service.saveLogin(new LoginRequest(true, "wx1234567890abcdef", "0123456789abcdef0123456789abcdef", false, null));
            assertNotNull(first.version());
            assertTrue(repo.findById("LOGIN").orElseThrow().getAppSecretEncrypted().startsWith("v1:"));
            var updated = service.saveLogin(new LoginRequest(false, "wx1234567890abcdef", "", false, first.version()));
            assertTrue(updated.version() > first.version());
            assertFalse(service.overview().login().enabled());
        } finally { UserContext.clear(); }
    }
}
