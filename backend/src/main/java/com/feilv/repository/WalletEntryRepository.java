package com.feilv.repository;

import com.feilv.domain.WalletEntry;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletEntryRepository extends JpaRepository<WalletEntry, Long> {
    Page<WalletEntry> findByUserId(long userId, Pageable pageable);
}
