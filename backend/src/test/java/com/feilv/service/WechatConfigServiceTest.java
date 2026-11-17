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
    @Test void riderUsesIndependentEnvironmentWithoutUserFallbackAndStrictClientEnum() {
        var env = new WechatConfigService(repo, new ConfigSecretCipher(key), audit, app, secret);
        assertEquals(secret, env.effectiveLogin().secret());
        assertFalse(env.overview().riderLogin().enabled());
        assertEquals("", env.overview().riderLogin().appId());
        assertEquals(503, assertThrows(BizException.class, () -> env.effectiveLogin("RIDER")).getCode());
        assertFalse(env.validate("LOGIN", "RIDER").valid());
        for (String unknown : Arrays.asList(null, "", "user", "rider", "ADMIN", " USER ")) {
            assertEquals(400, assertThrows(BizException.class, () -> env.effectiveLogin(unknown)).getCode());
            assertEquals(400, assertThrows(BizException.class, () -> env.saveLogin(unknown, new LoginRequest(false, "", "", false, null))).getCode());
            if (unknown != null) assertEquals(400, assertThrows(BizException.class, () -> env.validate("LOGIN", unknown)).getCode());
        }
        assertTrue(env.validate("LOGIN").valid());
        for (String irrelevant : List.of("USER", "RIDER", "")) {
            assertEquals(400, assertThrows(BizException.class, () -> env.validate("PAYMENT", irrelevant)).getCode());
        }
    }
    @Test void userAndRiderKeepExactSeparateAadAndIndependentVersionsAndBlankSecrets() throws Exception {
        String riderApp = "wxabcdef1234567890", riderSecret = "a".repeat(32);
        var user = service.saveLogin(new LoginRequest(true, app, secret, false, null));
        String userCiphertext = rows.get("LOGIN").getAppSecretEncrypted();
        var rider = service.saveLogin("RIDER", new LoginRequest(true, riderApp, riderSecret, false, null));
        String riderCiphertext = rows.get("LOGIN_RIDER").getAppSecretEncrypted();
        var cipher = new ConfigSecretCipher(key);
        assertEquals(secret, cipher.decrypt(userCiphertext, "LOGIN.appSecret"));
        assertEquals(riderSecret, cipher.decrypt(riderCiphertext, "LOGIN_RIDER.appSecret"));
        assertThrows(BizException.class, () -> cipher.decrypt(riderCiphertext, "LOGIN.appSecret"));
        assertThrows(BizException.class, () -> cipher.decrypt(userCiphertext, "LOGIN_RIDER.appSecret"));
        var saved = service.saveLogin("RIDER", new LoginRequest(true, riderApp, " \n\t ", false, rider.version()));
        assertEquals(riderCiphertext, rows.get("LOGIN_RIDER").getAppSecretEncrypted());
        assertEquals(user.version(), rows.get("LOGIN").getVersion());
        assertEquals(userCiphertext, rows.get("LOGIN").getAppSecretEncrypted());
        assertEquals(app, service.effectiveLogin().appId());
        assertEquals(riderApp, service.effectiveLogin("RIDER").appId());
        assertEquals(409, assertThrows(BizException.class, () -> service.saveLogin("RIDER", new LoginRequest(false, riderApp, "", false, rider.version()))).getCode());
        assertTrue(service.validate("LOGIN", "RIDER").valid());
        assertEquals("MOCK", service.overview().paymentRuntime());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(service.overview());
        assertTrue(json.contains("riderLogin"));
        for (String sensitive : List.of(secret, riderSecret, userCiphertext, riderCiphertext)) assertFalse(json.contains(sensitive));
        service.saveLogin("RIDER", new LoginRequest(false, riderApp, "", true, saved.version()));
        assertNull(rows.get("LOGIN_RIDER").getAppSecretEncrypted());
        assertEquals(secret, service.effectiveLogin("USER").secret());
        assertEquals(503, assertThrows(BizException.class, () -> service.effectiveLogin("RIDER")).getCode());
        verify(audit, atLeastOnce()).record(eq("WECHAT_LOGIN_CONFIG_SAVE"), contains("clientType=RIDER"));
    }
    @Test void legacyCiphertextIsReadInPlaceAndNeverCopiedToRider() {
        var cipher = new ConfigSecretCipher(key);
        WechatConfig legacy = new WechatConfig(); legacy.setSection("LOGIN"); legacy.setVersion(8L);
        legacy.setEnabled(true); legacy.setAppId(app);
        String encoded = cipher.encrypt(secret, "LOGIN.appSecret"); legacy.setAppSecretEncrypted(encoded); rows.put("LOGIN", legacy);
        assertEquals(secret, service.effectiveLogin().secret());
        assertEquals(encoded, rows.get("LOGIN").getAppSecretEncrypted());
        assertFalse(rows.containsKey("LOGIN_RIDER"));
        assertEquals(503, assertThrows(BizException.class, () -> service.effectiveLogin("RIDER")).getCode());
        verify(repo, never()).saveAndFlush(any());
    }
    @Test void perClientDbOverridesOnlyItsOwnEnvironmentIncludingExplicitDisable() {
        String riderApp = "wxabcdef1234567890", riderSecret = "a".repeat(32);
        var env = new WechatConfigService(repo, new ConfigSecretCipher(key), audit, app, secret, riderApp, riderSecret);
        var rider = env.saveLogin("RIDER", new LoginRequest(true, riderApp, "", false, null));
        assertEquals(riderSecret, env.effectiveLogin("RIDER").secret());
        assertEquals("DATABASE", env.overview().riderLogin().source());
        assertEquals("ENVIRONMENT", env.overview().login().source());
        assertEquals(secret, env.effectiveLogin().secret());
        env.saveLogin("RIDER", new LoginRequest(false, riderApp, "", true, rider.version()));
        assertEquals(503, assertThrows(BizException.class, () -> env.effectiveLogin("RIDER")).getCode());
        assertEquals(secret, env.effectiveLogin().secret());
        assertFalse(env.validate("LOGIN", "RIDER").valid());
        assertTrue(env.validate("LOGIN", "USER").valid());
    }
    @Test void bothConfiguredAppIdsMustDifferAcrossDatabaseEnvironmentAndDisabledRows() {
        var first = service.saveLogin(new LoginRequest(true, app, secret, false, null));
        assertEquals(400, assertThrows(BizException.class, () -> service.saveLogin("RIDER", new LoginRequest(true, app, secret, false, null))).getCode());
        assertFalse(rows.containsKey("LOGIN_RIDER"));
        service.saveLogin(new LoginRequest(false, app, "", false, first.version()));
        assertEquals(400, assertThrows(BizException.class, () -> service.saveLogin("RIDER", new LoginRequest(false, app.toUpperCase(Locale.ROOT).replace("WX", "wx"), "", false, null))).getCode());
        var env = new WechatConfigService(repo, new ConfigSecretCipher(key), audit, "", "", "wxabcdef1234567890", "a".repeat(32));
        assertEquals(400, assertThrows(BizException.class, () -> env.saveLogin(new LoginRequest(true, "wxabcdef1234567890", secret, false, rows.get("LOGIN").getVersion()))).getCode());
        rows.clear();
        var duplicateEnv = new WechatConfigService(repo, new ConfigSecretCipher(key), audit, app, secret, app, "a".repeat(32));
        assertFalse(duplicateEnv.validate("LOGIN", "USER").valid());
        assertFalse(duplicateEnv.validate("LOGIN", "RIDER").valid());
        assertEquals(503, assertThrows(BizException.class, () -> duplicateEnv.effectiveLogin("USER")).getCode());
        assertEquals(503, assertThrows(BizException.class, () -> duplicateEnv.effectiveLogin("RIDER")).getCode());
    }
    @Test void riderAppChangeMustReplaceOrExplicitlyClearPairedSecretAndMasksNeverPersist() {
        String riderApp = "wxabcdef1234567890";
        var rider = service.saveLogin("RIDER", new LoginRequest(true, riderApp, secret, false, null));
        assertEquals(400, assertThrows(BizException.class, () -> service.saveLogin("RIDER", new LoginRequest(false, app, "", false, rider.version()))).getCode());
        for (String mask : List.of("********", "••••", "＊＊＊＊")) {
            assertEquals(400, assertThrows(BizException.class, () -> service.saveLogin("RIDER", new LoginRequest(false, riderApp, mask, false, rider.version()))).getCode());
        }
        assertEquals(400, assertThrows(BizException.class, () -> service.saveLogin("RIDER", new LoginRequest(false, riderApp, secret, true, rider.version()))).getCode());
        service.saveLogin("RIDER", new LoginRequest(false, app, "", true, rider.version()));
        assertNull(rows.get("LOGIN_RIDER").getAppSecretEncrypted());
        assertEquals(app, service.overview().riderLogin().appId());
    }
    @Test void firstSaveCannotSilentlyDiscardOtherAppEnvironmentSecretEvenWhileDisabled() {
        var env = new WechatConfigService(repo, new ConfigSecretCipher(key), audit, "", "", "wxabcdef1234567890", secret);
        assertEquals(400, assertThrows(BizException.class, () -> env.saveLogin("RIDER", new LoginRequest(false, app, "", false, null))).getCode());
        assertFalse(rows.containsKey("LOGIN_RIDER"));
        env.saveLogin("RIDER", new LoginRequest(false, app, "", true, null));
        assertFalse(env.overview().riderLogin().appSecretConfigured());
    }
    @Test void bothWriteEntryPointsHaveSerializableIsolationForCrossClientAppIdChanges() throws Exception {
        for (var signature : List.of(new Class<?>[]{LoginRequest.class}, new Class<?>[]{String.class, LoginRequest.class})) {
            var tx = WechatConfigService.class.getMethod("saveLogin", signature).getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertEquals(org.springframework.transaction.annotation.Isolation.SERIALIZABLE, tx.isolation());
        }
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
