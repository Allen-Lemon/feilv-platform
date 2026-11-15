package com.feilv.repository;

import com.feilv.domain.WalletOrderSettlement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface WalletOrderSettlementRepository extends JpaRepository<WalletOrderSettlement, Long> {
    Optional<WalletOrderSettlement> findByOrderId(long orderId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WalletOrderSettlement s where s.orderId = :orderId")
    Optional<WalletOrderSettlement> findByOrderIdForUpdate(@Param("orderId") long orderId);
}
