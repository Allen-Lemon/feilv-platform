package com.feilv.repository;

import com.feilv.domain.Rider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiderRepository extends JpaRepository<Rider, Long> {
    Optional<Rider> findByUserId(Long userId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Rider r where r.userId = :userId")
    Optional<Rider> findByUserIdForUpdate(@org.springframework.data.repository.query.Param("userId") long userId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Rider r where r.id = :id")
    Optional<Rider> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") long id);

    /** 候选运力池：在线（抢单/听单）且未冻结的骑手 */
    List<Rider> findByStatusAndOnlineStatusGreaterThan(String status, int onlineStatus);
}
