package com.feilv.repository;

import com.feilv.domain.CustomTask;
import com.feilv.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface CustomTaskRepository extends JpaRepository<CustomTask, Long> {
    Optional<CustomTask> findByOrderId(Long orderId);
    @Query("select t from CustomTask t join OrderMain o on o.id=t.orderId " +
            "where (:userId is null or t.userId=:userId) and (:status is null or o.status=:status)")
    Page<CustomTask> findAdminTasks(@Param("userId") Long userId, @Param("status") OrderStatus status, Pageable pageable);
}
