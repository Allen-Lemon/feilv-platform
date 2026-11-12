package com.feilv.repository;

import com.feilv.domain.Complaint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    List<Complaint> findByUserIdOrderByIdDesc(Long userId);

    List<Complaint> findByStatusOrderByIdDesc(String status);
}
