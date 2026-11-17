package com.feilv.repository;

import com.feilv.domain.MiniappLinkCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MiniappLinkCodeRepository extends JpaRepository<MiniappLinkCode, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MiniappLinkCode c where c.tokenHash = :hash")
    Optional<MiniappLinkCode> findForUpdate(@Param("hash") String hash);
    List<MiniappLinkCode> findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff);
}
