package com.feilv.repository;

import com.feilv.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    List<Merchant> findByStatusOrderByIdDesc(String status);
}
