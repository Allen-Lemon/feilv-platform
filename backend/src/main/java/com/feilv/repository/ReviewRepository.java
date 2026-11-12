package com.feilv.repository;

import com.feilv.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    boolean existsByOrderId(Long orderId);   // 一单一评

    List<Review> findByRiderIdOrderByIdDesc(Long riderId);
}
