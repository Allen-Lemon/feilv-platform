package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 订单主表（t_order）— 保留≥3年；金额单位：分 */
@Getter @Setter
@Entity @Table(name = "t_order")
public class OrderMain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true)
    private String orderNo;

    @Column(name = "order_type", nullable = false)
    private String orderType;      // SEND / BUY / ERRAND

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "rider_id")
    private Long riderId;

    @Column(name = "from_contact", nullable = false)
    private String fromContact;
    @Column(name = "from_phone", nullable = false)
    private String fromPhone;
    @Column(name = "from_address", nullable = false)
    private String fromAddress;
    @Column(name = "from_lng")
    private java.math.BigDecimal fromLng;
    @Column(name = "from_lat")
    private java.math.BigDecimal fromLat;

    @Column(name = "to_contact", nullable = false)
    private String toContact;
    @Column(name = "to_phone", nullable = false)
    private String toPhone;
    @Column(name = "to_address", nullable = false)
    private String toAddress;
    @Column(name = "to_lng")
    private java.math.BigDecimal toLng;
    @Column(name = "to_lat")
    private java.math.BigDecimal toLat;

    @Column(name = "distance_m")
    private int distanceM;

    @Column(name = "weight_kg")
    private int weightKg;

    @Column(name = "goods_desc")
    private String goodsDesc;

    /** 代购商品预算（分），独立于配送费；历史订单可为空。 */
    @Column(name = "goods_budget_fen")
    private Integer goodsBudgetFen;

    private String remark;

    @Column(name = "estimate_fee", nullable = false)
    private int estimateFee;

    @Column(name = "real_fee", nullable = false)
    private int realFee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private com.feilv.enums.OrderStatus status = com.feilv.enums.OrderStatus.WAIT_PAY;

    @Column(name = "pickup_code")
    private String pickupCode;

    @Column(name = "accepted_time")
    private LocalDateTime acceptedTime;

    @Column(name = "delivered_time")
    private LocalDateTime deliveredTime;

    @Column(name = "finish_time")
    private LocalDateTime finishTime;

    @Column(name = "created_time", insertable = false, updatable = false)
    private LocalDateTime createdTime;

    @Column(name = "updated_time", insertable = false, updatable = false)
    private LocalDateTime updatedTime;
}
