package com.feilv.repository;

import com.feilv.domain.AdminRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRoleRepository extends JpaRepository<AdminRole, Long> {
    Optional<AdminRole> findByCodeAndEnabled(String code, int enabled);
}
