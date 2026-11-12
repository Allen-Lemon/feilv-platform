package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 订单费用明细（t_order_fee_detail）— 下单页透明展示构成 */
@Getter @Setter
@Entity @Table(name = "t_order_fee_detail")
public class OrderFeeDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "fee_type", nullable = false)
    private String feeType;   // BASE / DISTANCE / WEIGHT / NIGHT

    @Column(nullable = false)
    private int amount;       // 分
}
