package com.feilv.repository;

import com.feilv.domain.ComplaintDeduction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ComplaintDeductionRepository extends JpaRepository<ComplaintDeduction, Long> {
    List<ComplaintDeduction> findByOrderIdIn(Collection<Long> orderIds);

    // 当前读，避免MySQL REPEATABLE_READ在等待订单锁前建立的快照漏看已提交扣款。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ComplaintDeduction d where d.orderId = :orderId")
    List<ComplaintDeduction> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Query("select d.riderId, sum(d.amountFen) from ComplaintDeduction d where d.channel = 'MOCK' group by d.riderId")
    List<Object[]> sumByRider();
}
