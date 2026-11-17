package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** Server-selected account and opposite application; never accepts a client-supplied user id. */
@Getter @Setter
@Entity
@Table(name = "t_miniapp_link_code", indexes = @Index(name = "idx_miniapp_link_expiry", columnList = "expires_at"))
public class MiniappLinkCode {
    @Id @Column(name = "token_hash", length = 64) private String tokenHash;
    @Column(nullable = false, length = 32) private String purpose = "LINK_IDENTITY";
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "source_client", nullable = false, length = 8) private String sourceClient;
    @Column(name = "target_client", nullable = false, length = 8) private String targetClient;
    @Column(name = "source_app_id", nullable = false, length = 64) private String sourceAppId;
    @Column(name = "target_app_id", nullable = false, length = 64) private String targetAppId;
    @Column(name = "source_fingerprint", nullable = false, length = 64) private String sourceFingerprint;
    @Column(name = "target_fingerprint", nullable = false, length = 64) private String targetFingerprint;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(nullable = false) private boolean consumed;
}
