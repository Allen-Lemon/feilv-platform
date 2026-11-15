package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** MOCK账务扣款/等额赔付账本；不修改支付原流水，不触发真实资金操作。 */
@Getter
@Setter
@Entity
@Table(name = "t_complaint_deduction", uniqueConstraints =
        @UniqueConstraint(name = "uk_complaint_deduction_complaint", columnNames = "complaint_id"), indexes = {
        @Index(name = "idx_complaint_deduction_order", columnList = "order_id"),
        @Index(name = "idx_complaint_deduction_rider", columnList = "rider_id")})
public class ComplaintDeduction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "complaint_id", nullable = false, updatable = false)
    private Long complaintId;
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;
    @Column(name = "rider_id", nullable = false, updatable = false)
    private Long riderId;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "amount_fen", nullable = false, updatable = false)
    private int amountFen;
    @Column(nullable = false, length = 16, updatable = false)
    private String channel = "MOCK";
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
