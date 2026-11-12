package com.feilv.repository;

import com.feilv.domain.AdminMenu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminMenuRepository extends JpaRepository<AdminMenu, Long> {
    List<AdminMenu> findByEnabledOrderBySortAscIdAsc(int enabled);
}
