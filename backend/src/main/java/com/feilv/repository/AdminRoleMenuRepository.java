package com.feilv.repository;

import com.feilv.domain.AdminRoleMenu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminRoleMenuRepository extends JpaRepository<AdminRoleMenu, Long> {
    List<AdminRoleMenu> findByRoleId(Long roleId);
}
