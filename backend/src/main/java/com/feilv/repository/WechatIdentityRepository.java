package com.feilv.repository;

import com.feilv.domain.WechatIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WechatIdentityRepository extends JpaRepository<WechatIdentity, Long> {
    Optional<WechatIdentity> findByAppIdAndOpenid(String appId, String openid);
    boolean existsByUserIdAndAppId(Long userId, String appId);
}
