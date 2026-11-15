package com.feilv.repository;

import com.feilv.domain.CustomTaskTag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface CustomTaskTagRepository extends JpaRepository<CustomTaskTag, Long> {
    List<CustomTaskTag> findByTaskIdInOrderByIdAsc(Collection<Long> taskIds);
}
