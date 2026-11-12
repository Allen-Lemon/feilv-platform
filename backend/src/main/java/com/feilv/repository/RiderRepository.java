package com.feilv.repository;

import com.feilv.domain.Rider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiderRepository extends JpaRepository<Rider, Long> {
    Optional<Rider> findByUserId(Long userId);

    /** 候选运力池：在线（抢单/听单）且未冻结的骑手 */
    List<Rider> findByStatusAndOnlineStatusGreaterThan(String status, int onlineStatus);
}
