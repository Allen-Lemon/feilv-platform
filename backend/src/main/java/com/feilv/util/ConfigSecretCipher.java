package com.feilv.util;

import com.feilv.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** 主密钥只通过环境变量/密钥管理注入；每次独立随机IV，绑定配置项防止密文调换。 */
@Component
public class ConfigSecretCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public ConfigSecretCipher(@Value("${feilv.config-encryption-key:}") String value) {
        byte[] parsed = null;
        try { parsed = Base64.getDecoder().decode(value == null ? "" : value.trim()); } catch (IllegalArgumentException ignored) {}
        key = parsed != null && parsed.length == 32 ? parsed : null;
    }
    public boolean ready() { return key != null; }
    public void requireReady() {
        if (!ready()) throw BizException.of(503, "请先在后端配置有效的 CONFIG_ENCRYPTION_KEY（32字节Base64主密钥）");
    }
    public String encrypt(String plain, String context) {
        requireReady();
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw BizException.of(503, "配置加密失败，请检查服务密钥配置"); }
    }
    public String decrypt(String encoded, String context) {
        requireReady();
        try {
            String[] parts = encoded.split(":");
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            if (iv.length != 12) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw BizException.of(503, "配置无法解密，请确认 CONFIG_ENCRYPTION_KEY 未变更并正确配置"); }
    }
}
