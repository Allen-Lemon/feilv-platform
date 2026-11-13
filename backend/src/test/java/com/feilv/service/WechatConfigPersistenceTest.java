package com.feilv.service;

import com.feilv.common.UserContext;
import com.feilv.dto.WechatConfigDtos.LoginRequest;
import com.feilv.repository.WechatConfigRepository;
import com.feilv.util.ConfigSecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "feilv.wechat.app-id=", "feilv.wechat.app-secret=",
        "feilv.config-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Import({WechatConfigService.class, ConfigSecretCipher.class})
class WechatConfigPersistenceTest {
    @MockBean AuditService audit;
    @Autowired WechatConfigService service;
    @Autowired WechatConfigRepository repo;
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
