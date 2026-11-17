package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** Short-lived identity proof. Only the SHA-256 digest of the bearer ticket is persisted. */
@Getter @Setter
@Entity
@Table(name = "t_miniapp_login_ticket", indexes = @Index(name = "idx_miniapp_ticket_expiry", columnList = "expires_at"))
public class MiniappLoginTicket {
    @Id @Column(name = "token_hash", length = 64) private String tokenHash;
    @Column(nullable = false, length = 32) private String purpose = "IDENTITY_LOGIN";
    @Column(nullable = false, length = 8) private String client;
    @Column(name = "app_id", nullable = false, length = 64) private String appId;
    @Column(nullable = false, length = 128) private String openid;
    @Column(length = 128) private String unionid;
    @Column(name = "config_fingerprint", nullable = false, length = 64) private String configFingerprint;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(nullable = false) private boolean consumed;
    @Column(nullable = false) private int failures;
}
