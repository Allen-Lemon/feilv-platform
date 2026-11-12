package com.feilv.repository;

import com.feilv.domain.OrderStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderStatusLogRepository extends JpaRepository<OrderStatusLog, Long> {
    List<OrderStatusLog> findByOrderIdOrderByIdAsc(Long orderId);
}
