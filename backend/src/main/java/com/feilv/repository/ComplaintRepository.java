package com.feilv.repository;

import com.feilv.domain.Complaint;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    List<Complaint> findByUserIdOrderByIdDesc(Long userId);
    List<Complaint> findByStatusOrderByIdDesc(String status);
    List<Complaint> findByOrderIdOrderByIdDesc(Long orderId);

    @Query("select c from Complaint c where (:status is null or c.status = :status) "
            + "and (:orderId is null or c.orderId = :orderId)")
    Page<Complaint> search(@Param("status") String status, @Param("orderId") Long orderId, Pageable pageable);

    @Query("select c.orderId from Complaint c where c.id = :id")
    Optional<Long> findOrderId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Complaint c where c.id = :id")
    Optional<Complaint> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Complaint c where c.orderId = :orderId and c.status in ('OPEN', 'PROCESSING')")
    List<Complaint> findActiveForUpdate(@Param("orderId") Long orderId);
}
