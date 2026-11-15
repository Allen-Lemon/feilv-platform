package com.feilv.repository;

import com.feilv.domain.WalletComplaintReceipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface WalletComplaintReceiptRepository extends JpaRepository<WalletComplaintReceipt, Long> {
    Optional<WalletComplaintReceipt> findByComplaintId(long complaintId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WalletComplaintReceipt r where r.complaintId = :complaintId")
    Optional<WalletComplaintReceipt> findByComplaintIdForUpdate(@Param("complaintId") long complaintId);
}
