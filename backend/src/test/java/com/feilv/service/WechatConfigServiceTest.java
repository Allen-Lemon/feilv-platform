package com.feilv.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.WechatConfig;
import com.feilv.dto.WechatConfigDtos.*;
import com.feilv.repository.WechatConfigRepository;
import com.feilv.util.ConfigSecretCipher;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.security.KeyPairGenerator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WechatConfigServiceTest {
    private final String app = "wx1234567890abcdef";
    private final String secret = "0123456789abcdef0123456789abcdef";
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private WechatConfigRepository repo;
    private AuditService audit;
    private WechatConfigService service;
    private Map<String, WechatConfig> rows;

    @BeforeEach void setup() {
        repo = mock(WechatConfigRepository.class); audit = mock(AuditService.class); rows = new HashMap<>();
        when(repo.findById(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(repo.saveAndFlush(any())).thenAnswer(call -> {
            WechatConfig c = call.getArgument(0); c.setVersion(c.getVersion() == null ? 0 : c.getVersion() + 1); rows.put(c.getSection(), c); return c;
        });
        service = new WechatConfigService(repo, new ConfigSecretCipher(key), audit, "", "");
        UserContext.set(new UserContext.CurrentUser(1L, "ADMIN"));
    }
    @AfterEach void cleanup() { UserContext.clear(); }
    @Test void encryptsAtRestAndNeverReturnsSecretsOrAuditValues() throws Exception {
        var view = service.saveLogin(new LoginRequest(true, app, secret, false, null));
        String encoded = rows.get("LOGIN").getAppSecretEncrypted();
        assertNotEquals(secret, encoded); assertTrue(encoded.startsWith("v1:"));
        assertEquals(secret, service.effectiveLogin().secret());
        assertTrue(view.appSecretConfigured()); assertEquals("DATABASE", view.source());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(service.overview());
        assertFalse(json.contains(secret)); assertFalse(json.contains(encoded));
        assertFalse(service.effectiveLogin().toString().contains(secret));
        verify(audit).record(eq("WECHAT_LOGIN_CONFIG_SAVE"), argThat(value -> !value.contains(secret) && !value.contains(encoded)));
    }
    @Test void blankKeepsSecretExplicitClearRemovesItAndDisabledDbOverridesEnvironment() {
        var first = service.saveLogin(new LoginRequest(true, app, secret, false, null));
        String old = rows.get("LOGIN").getAppSecretEncrypted();
        var second = service.saveLogin(new LoginRequest(true, app, "", false, first.version()));
        assertEquals(old, rows.get("LOGIN").getAppSecretEncrypted());
        service.saveLogin(new LoginRequest(false, app, "", true, second.version()));
        assertNull(rows.get("LOGIN").getAppSecretEncrypted());
        assertThrows(BizException.class, () -> service.effectiveLogin());
    }
    @Test void environmentUsedOnlyBeforeAnyDatabaseOverride() {
        var env = new WechatConfigService(repo, new ConfigSecretCipher(""), audit, app, secret);
        assertEquals("ENVIRONMENT", env.overview().login().source());
        assertEquals(secret, env.effectiveLogin().secret());
        WechatConfig c = new WechatConfig(); c.setSection("LOGIN"); c.setEnabled(false); rows.put("LOGIN", c);
        assertThrows(BizException.class, env::effectiveLogin);
    }
    @Test void firstDatabaseSaveCanPreserveMatchingEnvironmentSecretWithoutExposingIt() {
        var env = new WechatConfigService(repo, new ConfigSecretCipher(key), audit, app, secret);
        var view = env.saveLogin(new LoginRequest(true, app, "", false, null));
        assertTrue(view.appSecretConfigured()); assertEquals("DATABASE", view.source());
        assertEquals(secret, env.effectiveLogin().secret());
        assertNotEquals(secret, rows.get("LOGIN").getAppSecretEncrypted());
    }
    @Test void firstSaveWithDifferentAppCannotReuseEnvironmentSecret() {
        var env = new WechatConfigService(repo, new ConfigSecretCipher(key), audit, app, secret);
        assertThrows(BizException.class, () -> env.saveLogin(new LoginRequest(true, "wxabcdef1234567890", "", false, null)));
    }
    @Test void missingMasterKeyRejectsWritesInsteadOfPlaintextFallback() {
        var unsafe = new WechatConfigService(repo, new ConfigSecretCipher(""), audit, "", "");
        assertFalse(unsafe.overview().encryptionReady());
        BizException error = assertThrows(BizException.class, () -> unsafe.saveLogin(new LoginRequest(true, app, secret, false, null)));
        assertEquals(503, error.getCode()); verify(repo, never()).saveAndFlush(any());
    }
    @Test void staleVersionAndMalformedAppIdOrPlaceholderSecretAreRejected() {
        var first = service.saveLogin(new LoginRequest(true, app, secret, false, null));
        assertEquals(409, assertThrows(BizException.class, () -> service.saveLogin(new LoginRequest(false, app, "", false, null))).getCode());
        assertThrows(BizException.class, () -> service.saveLogin(new LoginRequest(true, "touristappid", secret, false, first.version())));
        assertThrows(BizException.class, () -> service.saveLogin(new LoginRequest(true, app, "********", false, first.version())));
    }
    @Test void changingAppCannotSilentlyReuseOldSecret() {
        var first = service.saveLogin(new LoginRequest(true, app, secret, false, null));
        assertThrows(BizException.class, () -> service.saveLogin(new LoginRequest(true, "wxabcdef1234567890", "", false, first.version())));
    }
    private PaymentRequest payment(String apiKey, String privateKey, String publicKey, String notify, Long version) {
        return new PaymentRequest(true, app, "1234567890", "ABCDEF1234567890", notify, "", apiKey, privateKey, publicKey,
                "PUB_KEY_ID_TEST123", false, false, false, version);
    }
    @Test void paymentParametersPersistEncryptedButRuntimeRemainsMock() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair = generator.generateKeyPair();
        String privateKey = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
        String publicKey = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        String apiKey = "12345678901234567890123456789012";
        var view = service.savePayment(payment(apiKey, privateKey, publicKey, "https://xt.allms.cn/notify/pay/wechat", null));
        assertTrue(view.enabled()); assertEquals("MOCK", view.mode()); assertTrue(view.merchantPrivateKeyConfigured());
        var validation = service.validate("PAYMENT"); assertTrue(validation.valid()); assertEquals("MOCK", validation.runtime());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(service.overview());
        assertFalse(json.contains(apiKey)); assertFalse(json.contains("BEGIN PRIVATE KEY"));
        assertNotEquals(privateKey, rows.get("PAYMENT").getMerchantPrivateKeyEncrypted());
    }
    @Test void invalidPaymentSecretsAndHttpCallbacksAreRejected() {
        assertThrows(BizException.class, () -> service.savePayment(payment("short", "", "", "https://xt.allms.cn/callback", null)));
        assertThrows(BizException.class, () -> service.savePayment(payment("", "bad pem", "", "https://xt.allms.cn/callback", null)));
        assertThrows(BizException.class, () -> service.savePayment(payment("", "", "", "http://xt.allms.cn/callback", null)));
        assertThrows(BizException.class, () -> service.validate("UNKNOWN"));
    }
}
