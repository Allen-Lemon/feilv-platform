package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 骑手轨迹（t_rider_track）— 保留≥90天；配送中5-10秒自适应上报 */
@Getter @Setter
@Entity @Table(name = "t_rider_track")
public class RiderTrack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "rider_id", nullable = false)
    private Long riderId;

    private BigDecimal lng;
    private BigDecimal lat;
    private BigDecimal speed;

    @Column(name = "report_time", insertable = false, updatable = false)
    private LocalDateTime reportTime;
}
