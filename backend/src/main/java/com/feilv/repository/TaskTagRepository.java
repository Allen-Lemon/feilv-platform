package com.feilv.repository;

import com.feilv.domain.TaskTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface TaskTagRepository extends JpaRepository<TaskTag, Long> {
    List<TaskTag> findAllByOrderBySortAscIdAsc();
    List<TaskTag> findByEnabledTrueOrderBySortAscIdAsc();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskTag t where t.id = :id")
    Optional<TaskTag> findByIdForUpdate(@Param("id") long id);
}
