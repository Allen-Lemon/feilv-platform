package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity @Table(name = "t_custom_task")
public class CustomTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(nullable = false, length = 64)
    private String title;
    @Column(nullable = false, length = 1000)
    private String content;
    @Column(name = "publish_source", nullable = false, length = 16)
    private String publishSource;
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
    @Column(name = "created_time", nullable = false)
    private LocalDateTime createdTime = LocalDateTime.now();
}
