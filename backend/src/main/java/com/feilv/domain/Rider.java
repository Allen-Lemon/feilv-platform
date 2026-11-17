package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 骑手档案（t_rider）— 信用分与在线状态为核心运营字段 */
@Getter @Setter
@Entity @Table(name = "t_rider")
// 上下线、信用更新只写脏字段，不能用读到的旧值覆盖并发撤销的授权。
@org.hibernate.annotations.DynamicUpdate
public class Rider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private String name;

    /** 生产须加密存储（AES），此处演示明文占位 */
    @Column(name = "id_card_enc")
    private String idCardEnc;

    @Column(name = "vehicle_type")
    private String vehicleType = "E-BIKE";

    @Column(name = "plate_no")
    private String plateNo;

    @Column(nullable = false)
    private String status = "ACTIVE"; // ACTIVE / FROZEN

    /** 0下线 1抢单 2听单（功能设计文档 8.2 调度引擎） */
    @Column(name = "online_status")
    private int onlineStatus = 0;

    /** 平台接单权限，独立后台授权；不代表已完成驾驶资质审核。 */
    @Column(name = "ride_enabled", nullable = false)
    private boolean rideEnabled = false;

    @Column(name = "driving_enabled", nullable = false)
    private boolean drivingEnabled = false;

    /** 信用分，初始100，事件驱动加减（详细设计表5-1） */
    @Column(name = "credit_score", nullable = false)
    private int creditScore = 100;

    @Column(name = "created_time", insertable = false, updatable = false)
    private LocalDateTime createdTime;
}
