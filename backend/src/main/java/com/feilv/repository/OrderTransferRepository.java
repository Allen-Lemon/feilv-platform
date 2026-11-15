package com.feilv.repository;

import com.feilv.domain.OrderTransfer;
import com.feilv.domain.OrderTransfer.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 所有写入由订单行锁串行化，禁止先锁转派/用户再锁订单。 */
public interface OrderTransferRepository extends JpaRepository<OrderTransfer, Long> {
    @Query("select t.orderId from OrderTransfer t where t.id = :id")
    Optional<Long> findOrderId(@Param("id") long id);

    // Dispatch可能处于MySQL REPEATABLE_READ；锁后当前读不能漏掉刚提交的PENDING。
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    List<OrderTransfer> findByOrderIdAndStatus(long orderId, Status status);
    List<OrderTransfer> findByOrderIdOrderByIdDesc(long orderId);

    @Query("select t from OrderTransfer t where (:riderId is null or "
            + "(:direction = 'INCOMING' and t.toRiderId = :riderId) or "
            + "(:direction = 'OUTGOING' and t.fromRiderId = :riderId)) "
            + "and (:status is null or t.status = :status) and (:orderId is null or t.orderId = :orderId)")
    Page<OrderTransfer> search(@Param("riderId") Long riderId, @Param("direction") String direction,
                               @Param("status") Status status, @Param("orderId") Long orderId, Pageable pageable);

    @Query("select distinct t.orderId from OrderTransfer t where t.status = :pending "
            + "and (:riderId is null or t.fromRiderId = :riderId or t.toRiderId = :riderId) "
            + "and (:orderId is null or t.orderId = :orderId) order by t.orderId")
    List<Long> pendingOrderIds(@Param("riderId") Long riderId, @Param("orderId") Long orderId,
                               @Param("pending") Status pending);
}
