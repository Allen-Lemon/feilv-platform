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
            + "WHERE o.riderId IS NOT NULL AND p.channel = 'MOCK' AND p.status = 'SUCCESS' GROUP BY o.riderId")
    List<Object[]> sumRiderShareGroupByRider();

    @org.springframework.data.jpa.repository.Query("select p.orderId, sum(p.shareRider) from PayRecord p "
            + "where p.orderId in :orderIds and p.channel = 'MOCK' and p.status = 'SUCCESS' group by p.orderId")
    List<Object[]> sumSuccessfulMockShareByOrderIds(
            @org.springframework.data.repository.query.Param("orderIds") java.util.Collection<Long> orderIds);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PayRecord p where p.orderId = :orderId "
            + "and p.channel = 'MOCK' and p.status = 'SUCCESS'")
    List<PayRecord> findSuccessfulMockForUpdate(
            @org.springframework.data.repository.query.Param("orderId") Long orderId);
}
