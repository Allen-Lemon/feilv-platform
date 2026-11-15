package com.feilv.repository;

import com.feilv.domain.WalletWithdrawal;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface WalletWithdrawalRepository extends JpaRepository<WalletWithdrawal, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WalletWithdrawal w where w.userId = :userId and w.requestId = :requestId")
    Optional<WalletWithdrawal> findRequestForUpdate(@Param("userId") long userId, @Param("requestId") String requestId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WalletWithdrawal w where w.id = :id")
    Optional<WalletWithdrawal> findByIdForUpdate(@Param("id") long id);
    @Query("select w.userId from WalletWithdrawal w where w.id = :id")
    Optional<Long> findUserId(@Param("id") long id);
    Page<WalletWithdrawal> findByUserId(long userId, Pageable pageable);
    @Query("select w from WalletWithdrawal w where (:userId is null or w.userId = :userId) and (:status is null or w.status = :status)")
    Page<WalletWithdrawal> search(@Param("userId") Long userId, @Param("status") String status, Pageable pageable);
}
