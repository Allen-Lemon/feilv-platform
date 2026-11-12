package com.feilv.repository;

import com.feilv.domain.OrderFeeDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderFeeDetailRepository extends JpaRepository<OrderFeeDetail, Long> {
    List<OrderFeeDetail> findByOrderId(Long orderId);

    void deleteByOrderId(Long orderId);
}
