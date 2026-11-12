package com.feilv.repository;

import com.feilv.domain.PayRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PayRecordRepository extends JpaRepository<PayRecord, Long> {
    List<PayRecord> findByOrderId(Long orderId);

    List<PayRecord> findByPayTimeBetween(LocalDateTime start, LocalDateTime end);

    /** 账务域：骑手分账汇总（对应功能设计文档 7.3 结算） */
    @org.springframework.data.jpa.repository.Query(
        "SELECT o.riderId AS riderId, SUM(p.shareRider) AS total "
            + "FROM PayRecord p JOIN OrderMain o ON o.id = p.orderId "
            + "WHERE o.riderId IS NOT NULL GROUP BY o.riderId")
    List<Object[]> sumRiderShareGroupByRider();
}
