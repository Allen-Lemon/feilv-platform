package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "t_admin_menu")
public class AdminMenu {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 128)
    private String path;

    @Column(name = "parent_id", nullable = false)
    private Long parentId = 0L;

    @Column(nullable = false)
    private int sort = 0;

    @Column(nullable = false)
    private int enabled = 1;

    @Column(name = "created_time", insertable = false, updatable = false)
    private LocalDateTime createdTime;
}
