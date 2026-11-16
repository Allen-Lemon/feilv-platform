package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@Entity @Table(name = "t_task_tag")
public class TaskTag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 32)
    private String name;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "sort_order", nullable = false)
    private int sort;
    @Column(name = "task_modes", length = 64)
    private String taskModes;
    @Version @Column(nullable = false)
    private Long version = 0L;
}
