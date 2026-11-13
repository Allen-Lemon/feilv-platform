package com.feilv.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 每组微信配置一行；机密只存AES-GCM密文，禁止将实体直接作为API响应。 */
@Entity
@Table(name = "t_wechat_config")
@Getter
@Setter
public class WechatConfig {
    @Id @Column(length = 16)
    private String section;
    @Version
    private Long version;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "app_id", length = 64)
    private String appId = "";
    @Column(name = "mch_id", length = 32)
    private String mchId = "";
    @Column(name = "merchant_serial_no", length = 64)
    private String merchantSerialNo = "";
    @Column(name = "notify_url", length = 1024)
    private String notifyUrl = "";
    @Column(name = "refund_notify_url", length = 1024)
    private String refundNotifyUrl = "";
    @Column(name = "platform_public_key_id", length = 128)
    private String platformPublicKeyId = "";
    @JsonIgnore @Lob @Column(name = "app_secret_encrypted", columnDefinition = "TEXT")
    private String appSecretEncrypted;
    @JsonIgnore @Lob @Column(name = "api_v3_key_encrypted", columnDefinition = "TEXT")
    private String apiV3KeyEncrypted;
    @JsonIgnore @Lob @Column(name = "merchant_private_key_encrypted", columnDefinition = "TEXT")
    private String merchantPrivateKeyEncrypted;
    @JsonIgnore @Lob @Column(name = "platform_public_key_encrypted", columnDefinition = "TEXT")
    private String platformPublicKeyEncrypted;
    @Column(name = "updated_by")
    private Long updatedBy;
    @Column(name = "updated_time")
    private LocalDateTime updatedTime;
}
