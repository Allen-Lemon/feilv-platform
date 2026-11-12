package com.feilv.repository;

import com.feilv.domain.BanWord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BanWordRepository extends JpaRepository<BanWord, Long> {
    List<BanWord> findByEnabled(int enabled);
}
