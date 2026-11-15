package com.feilv.repository;

import com.feilv.domain.WalletAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface WalletAccountRepository extends JpaRepository<WalletAccount, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from WalletAccount a where a.userId = :userId")
    Optional<WalletAccount> findByUserIdForUpdate(@Param("userId") long userId);
}
