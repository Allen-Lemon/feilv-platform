package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/** One compensation per complaint, even when rider deduction is deferred to order settlement. */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "t_wallet_complaint_receipt", uniqueConstraints = {
        @UniqueConstraint(name = "uk_wallet_receipt_complaint", columnNames = "complaint_id"),
        @UniqueConstraint(name = "uk_wallet_receipt_deduction", columnNames = "deduction_id")})
public class WalletComplaintReceipt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "complaint_id", nullable = false, updatable = false)
    private Long complaintId;
    @Column(name = "deduction_id", nullable = false, updatable = false)
    private Long deductionId;
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;
    @Column(name = "rider_id", nullable = false, updatable = false)
    private Long riderId;
    @Column(name = "rider_user_id", nullable = false, updatable = false)
    private Long riderUserId;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "amount_fen", nullable = false, updatable = false)
    private long amountFen;
    @Column(name = "rider_debited", nullable = false, updatable = false)
    private boolean riderDebited;
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
