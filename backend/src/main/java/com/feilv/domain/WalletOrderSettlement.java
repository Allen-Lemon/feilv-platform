package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Immutable
@Table(name = "t_wallet_order_settlement", uniqueConstraints =
        @UniqueConstraint(name = "uk_wallet_settlement_order", columnNames = "order_id"))
@Check(constraints = "gross_fen >= 0 and deducted_fen >= 0 and net_fen >= 0 and gross_fen = deducted_fen + net_fen")
public class WalletOrderSettlement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;
    @Column(name = "rider_id", nullable = false, updatable = false)
    private Long riderId;
    /** Recipient t_user.id, not t_rider.id. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "gross_fen", nullable = false, updatable = false)
    private long grossFen;
    @Column(name = "deducted_fen", nullable = false, updatable = false)
    private long deductedFen;
    @Column(name = "net_fen", nullable = false, updatable = false)
    private long netFen;
    @Column(nullable = false, length = 16, updatable = false)
    private String channel = "MOCK";
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
