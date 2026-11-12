package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 商户主档（t_merchant）— 简化实现入驻/审核（完整商家端见二期） */
@Getter @Setter
@Entity @Table(name = "t_merchant")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", unique = true)
    private Long userId;

    @Column(name = "shop_name", nullable = false)
    private String shopName;

    private String category;

    private String address;

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING/APPROVED/FROZEN

    @Column(name = "created_time", insertable = false, updatable = false)
    private LocalDateTime createdTime;
}
