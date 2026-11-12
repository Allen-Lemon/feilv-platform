package com.feilv.repository;

import com.feilv.domain.OpLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpLogRepository extends JpaRepository<OpLog, Long> {
}
