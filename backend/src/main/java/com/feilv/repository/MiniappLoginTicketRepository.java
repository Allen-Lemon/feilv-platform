package com.feilv.repository;

import com.feilv.domain.MiniappLoginTicket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MiniappLoginTicketRepository extends JpaRepository<MiniappLoginTicket, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MiniappLoginTicket t where t.tokenHash = :hash")
    Optional<MiniappLoginTicket> findForUpdate(@Param("hash") String hash);
    List<MiniappLoginTicket> findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff);
}
