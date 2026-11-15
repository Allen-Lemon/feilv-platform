package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/** Append-only business ledger. Amounts and post-operation snapshots are never updated. */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "t_wallet_entry", uniqueConstraints =
        @UniqueConstraint(name = "uk_wallet_entry_business", columnNames = "business_key"), indexes =
        @Index(name = "idx_wallet_entry_user_id", columnList = "user_id,id"))
public class WalletEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "business_key", nullable = false, length = 100, updatable = false)
    private String businessKey;
    @Column(nullable = false, length = 32, updatable = false)
    private String type;
    @Column(name = "amount_fen", nullable = false, updatable = false)
    private long amountFen;
    @Column(name = "available_after_fen", nullable = false, updatable = false)
    private long availableAfterFen;
    @Column(name = "frozen_after_fen", nullable = false, updatable = false)
    private long frozenAfterFen;
    @Column(name = "debt_after_fen", nullable = false, updatable = false)
    private long debtAfterFen;
    @Column(name = "order_id", updatable = false)
    private Long orderId;
    @Column(name = "complaint_id", updatable = false)
    private Long complaintId;
    @Column(name = "withdrawal_id", updatable = false)
    private Long withdrawalId;
    @Column(nullable = false, length = 255, updatable = false)
    private String remark;
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
