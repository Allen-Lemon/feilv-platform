package com.feilv.repository;

import com.feilv.domain.RiderTrack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiderTrackRepository extends JpaRepository<RiderTrack, Long> {
    /** 取最近轨迹点（用户端轮询展示，刷新≤5秒） */
    List<RiderTrack> findTop50ByOrderIdOrderByIdDesc(Long orderId);
}
