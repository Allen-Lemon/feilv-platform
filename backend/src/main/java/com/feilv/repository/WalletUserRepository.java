package com.feilv.repository;

import com.feilv.domain.User;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Wallet-only admin query; no changes to the shared UserRepository. */
public interface WalletUserRepository extends Repository<User, Long> {
    @Query("select u from User u where u.role in ('USER','RIDER') and (:userId is null or u.id = :userId)")
    Page<User> search(@Param("userId") Long userId, Pageable pageable);
}
