package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 支付流水（t_pay_record）— 保留≥3年；生产经持牌机构收单分账，本工程模拟记录分账结果 */
@Getter @Setter
@Entity @Table(name = "t_pay_record")
public class PayRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pay_no", nullable = false, unique = true)
    private String payNo;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private String channel;          // MOCK / WECHAT / ALIPAY

    @Column(nullable = false)
    private int amount;              // 实付（分）

    @Column(name = "share_rider", nullable = false)
    private int shareRider;          // 骑手分账（分）

    @Column(name = "share_platform", nullable = false)
    private int sharePlatform;       // 平台分账（分）

    @Column(nullable = false)
    private String status = "SUCCESS";

    @Column(name = "pay_time", insertable = false, updatable = false)
    private LocalDateTime payTime;
}
