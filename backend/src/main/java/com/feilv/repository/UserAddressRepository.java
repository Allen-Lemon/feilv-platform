package com.feilv.repository;

import com.feilv.domain.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {
    List<UserAddress> findByUserIdOrderByIsDefaultDescIdDesc(Long userId);
    long countByUserId(Long userId);
}
