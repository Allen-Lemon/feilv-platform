package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "t_wallet_withdrawal", uniqueConstraints =
        @UniqueConstraint(name = "uk_wallet_withdrawal_request", columnNames = {"user_id", "request_id"}), indexes = {
        @Index(name = "idx_wallet_withdrawal_user_id", columnList = "user_id,id"),
        @Index(name = "idx_wallet_withdrawal_status_id", columnList = "status,id")})
@Check(constraints = "amount_fen >= 1 and amount_fen <= 100000000 and status in ('PENDING','APPROVED','REJECTED')")
public class WalletWithdrawal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "amount_fen", nullable = false, updatable = false)
    private long amountFen;
    @Column(name = "request_id", nullable = false, length = 64, updatable = false)
    private String requestId;
    @Column(nullable = false, length = 16)
    private String status = "PENDING";
    @Column(length = 255)
    private String reason;
    @Column(name = "reviewed_by")
    private Long reviewedBy;
    @Column(name = "reviewed_time")
    private LocalDateTime reviewedTime;
    @Column(nullable = false, length = 16, updatable = false)
    private String channel = "MOCK";
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
