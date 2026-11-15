package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 转派责任审计；只有目标确认才改订单骑手，不分摊收入或投诉责任。 */
@Getter @Setter
@Entity
@Table(name = "t_order_transfer", indexes = {
        @Index(name = "idx_transfer_order_status", columnList = "order_id,status,id"),
        @Index(name = "idx_transfer_from_status", columnList = "from_rider_id,status,id"),
        @Index(name = "idx_transfer_to_status", columnList = "to_rider_id,status,id")})
public class OrderTransfer {
    public enum Status { PENDING, ACCEPTED, REJECTED, CANCELED, EXPIRED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;
    @Column(name = "from_rider_id", nullable = false, updatable = false)
    private Long fromRiderId;
    @Column(name = "to_rider_id", nullable = false, updatable = false)
    private Long toRiderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;
    @Column(nullable = false, length = 255, updatable = false)
    private String reason;
    @Column(name = "response_reason", length = 255)
    private String responseReason;
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime = LocalDateTime.now();
    @Column(name = "handled_time")
    private LocalDateTime handledTime;
}
