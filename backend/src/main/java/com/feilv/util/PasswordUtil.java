package com.feilv.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 密码摘要工具；生产环境建议替换为 BCrypt/Argon2。 */
public final class PasswordUtil {
    private PasswordUtil() {}

    public static String sha256(String raw) {
        if (raw == null) throw new IllegalArgumentException("密码不能为空");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static boolean matches(String raw, String encoded) {
        return encoded != null && MessageDigest.isEqual(
                sha256(raw).getBytes(StandardCharsets.US_ASCII),
                encoded.getBytes(StandardCharsets.US_ASCII));
    }
}
