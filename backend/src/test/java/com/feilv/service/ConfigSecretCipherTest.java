package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.util.ConfigSecretCipher;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSecretCipherTest {
    @Test void randomizedCiphertextRoundtripAndBoundContext() {
        var cipher = new ConfigSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String one = cipher.encrypt("server-secret", "LOGIN.appSecret");
        String two = cipher.encrypt("server-secret", "LOGIN.appSecret");
        assertNotEquals(one, two); assertEquals("server-secret", cipher.decrypt(one, "LOGIN.appSecret"));
        assertThrows(BizException.class, () -> cipher.decrypt(one, "PAYMENT.apiV3Key"));
    }
    @Test void wrongMasterKeyAndInvalidEncodingFailClosed() {
        byte[] other = new byte[32]; other[0] = 1;
        var cipher = new ConfigSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String encoded = cipher.encrypt("private", "LOGIN.appSecret");
        var wrong = new ConfigSecretCipher(Base64.getEncoder().encodeToString(other));
        assertThrows(BizException.class, () -> wrong.decrypt(encoded, "LOGIN.appSecret"));
        assertFalse(new ConfigSecretCipher("not-base64").ready());
        assertFalse(new ConfigSecretCipher(Base64.getEncoder().encodeToString(new byte[16])).ready());
    }
}
