package com.feilv.repository;

import com.feilv.domain.OrderMain;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 投诉专用订单访问；不改变主订单Repository及订单流程。 */
public interface ComplaintOrderLockRepository extends Repository<OrderMain, Long> {
    Optional<OrderMain> findById(Long id);
    List<OrderMain> findAllById(Iterable<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderMain o where o.id = :id")
    Optional<OrderMain> findByIdForUpdate(@Param("id") Long id);
}
