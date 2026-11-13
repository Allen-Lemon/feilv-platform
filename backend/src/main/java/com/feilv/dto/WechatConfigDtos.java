package com.feilv.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class WechatConfigDtos {
    private WechatConfigDtos() {}
    public record LoginRequest(@NotNull Boolean enabled, @Size(max = 64) String appId,
                               @Size(max = 256) String appSecret, boolean clearAppSecret, Long version) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    public record PaymentRequest(@NotNull Boolean enabled, @Size(max = 64) String appId,
                                 @Size(max = 32) String mchId, @Size(max = 64) String merchantSerialNo,
                                 @Size(max = 1024) String notifyUrl, @Size(max = 1024) String refundNotifyUrl,
                                 @Size(max = 256) String apiV3Key, @Size(max = 12000) String merchantPrivateKey,
                                 @Size(max = 12000) String platformPublicKey, @Size(max = 128) String platformPublicKeyId,
                                 boolean clearApiV3Key, boolean clearMerchantPrivateKey,
                                 boolean clearPlatformPublicKey, Long version) {
        @Override public String toString() { return "PaymentRequest[redacted]"; }
    }
    public record LoginView(boolean enabled, String appId, boolean appSecretConfigured, String source,
                            Long version, LocalDateTime updatedTime) {}
    public record PaymentView(boolean enabled, String appId, String mchId, String merchantSerialNo,
                              String notifyUrl, String refundNotifyUrl, boolean apiV3KeyConfigured,
                              boolean merchantPrivateKeyConfigured, boolean platformPublicKeyConfigured,
                              String platformPublicKeyId, String mode, Long version, LocalDateTime updatedTime) {}
    public record Overview(boolean encryptionReady, LoginView login, PaymentView payment, String paymentRuntime) {}
    public record ValidateRequest(@NotNull String section) {}
    public record ValidationView(boolean valid, List<String> messages, String runtime) {}
}
