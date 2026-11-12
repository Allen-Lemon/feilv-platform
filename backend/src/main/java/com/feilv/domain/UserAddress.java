package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 用户地址簿（t_user_address，容量≥50条） */
@Getter @Setter
@Entity @Table(name = "t_user_address")
public class UserAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String contact;

    @Column(nullable = false)
    private String phone;

    @Column(name = "poi_name")
    private String poiName;

    @Column(nullable = false)
    private String address;

    private BigDecimal lng;
    private BigDecimal lat;

    @Column(name = "is_default")
    private int isDefault;
}
