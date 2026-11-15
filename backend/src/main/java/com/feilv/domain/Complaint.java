package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 投诉工单（t_complaint）— 一般投诉48小时内办结 */
@Getter @Setter
@Entity @Table(name = "t_complaint", indexes =
        @Index(name = "idx_complaint_order_status", columnList = "order_id,status"))
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String type;   // LOST/DAMAGE/OVERTIME/ATTITUDE/OTHER

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private String status = "OPEN";

    @Column(length = 255)
    private String result;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_time")
    private LocalDateTime handledTime;

    @Column(name = "sla_deadline")
    private LocalDateTime slaDeadline;

    @Column(name = "compensation_fen", nullable = false)
    private int compensationFen;

    @Column(name = "created_time", updatable = false)
    private LocalDateTime createdTime;

    @PrePersist
    void initializeTimes() {
        if (createdTime == null) createdTime = LocalDateTime.now();
        if (slaDeadline == null) slaDeadline = createdTime.plusHours(48);
    }
}
