package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "t_complaint_action")
public class ComplaintAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "complaint_id", nullable = false)
    private Long complaintId;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(length = 255)
    private String detail;

    @Column(name = "created_time", updatable = false)
    private LocalDateTime createdTime;

    @PrePersist
    void initializeTime() {
        if (createdTime == null) createdTime = LocalDateTime.now();
    }
}
