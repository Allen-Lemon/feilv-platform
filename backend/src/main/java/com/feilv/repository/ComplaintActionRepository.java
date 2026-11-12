package com.feilv.repository;

import com.feilv.domain.ComplaintAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplaintActionRepository extends JpaRepository<ComplaintAction, Long> {
    List<ComplaintAction> findByComplaintIdOrderByIdDesc(Long complaintId);
}
