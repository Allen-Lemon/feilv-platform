package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 评价记录（t_review）— 一单一评；提交后不可修改 */
@Getter @Setter
@Entity @Table(name = "t_review")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "rider_id", nullable = false)
    private Long riderId;

    @Column(nullable = false)
    private int stars;   // 1-5

    @Column(length = 255)
    private String content;

    @Column(name = "created_time", insertable = false, updatable = false)
    private LocalDateTime createdTime;
}
