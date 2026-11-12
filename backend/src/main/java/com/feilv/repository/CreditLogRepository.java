package com.feilv.repository;

import com.feilv.domain.CreditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditLogRepository extends JpaRepository<CreditLog, Long> {
    List<CreditLog> findByRiderIdOrderByIdDesc(Long riderId);
}
