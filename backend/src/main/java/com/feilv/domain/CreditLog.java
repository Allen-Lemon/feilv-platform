package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 信用分变动（t_credit_log）— 保留≥2年，逐笔可追溯 */
@Getter @Setter
@Entity @Table(name = "t_credit_log")
public class CreditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rider_id", nullable = false)
    private Long riderId;

    @Column(name = "change_value", nullable = false)
    private int changeValue;   // 正加负减

    @Column(name = "reason_code", nullable = false)
    private String reasonCode;

    @Column(name = "ref_order_id")
    private Long refOrderId;

    @Column(name = "created_time", insertable = false, updatable = false)
    private LocalDateTime createdTime;
}
