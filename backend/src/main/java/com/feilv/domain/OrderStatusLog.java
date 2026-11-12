package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 订单状态流转日志（t_order_status_log）— 保留≥3年，故障排查第一依据 */
@Getter @Setter
@Entity @Table(name = "t_order_status_log")
public class OrderStatusLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    @Column(name = "operator_type", nullable = false)
    private String operatorType;   // USER / RIDER / SYSTEM / ADMIN

    @Column(name = "operator_id")
    private Long operatorId;

    private String remark;

    @Column(name = "op_time", insertable = false, updatable = false)
    private LocalDateTime opTime;
}
