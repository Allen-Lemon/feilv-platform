package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "t_op_log")
public class OpLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 255)
    private String detail;

    @Column(name = "op_time", insertable = false, updatable = false)
    private LocalDateTime opTime;
}
