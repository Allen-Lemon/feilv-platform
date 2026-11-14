package com.feilv.repository;

import com.feilv.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByMobile(String mobile);

    /** 后台仅可管理C端用户；!作为LIKE转义符，防止关键词中的%和_扩大查询。 */
    @Query("select u from User u where u.role in ('USER', 'RIDER') " +
            "and (:status is null or u.status = :status) " +
            "and (:mobileBound is null or " +
            "(:mobileBound = true and u.mobile is not null and trim(u.mobile) <> '') or " +
            "(:mobileBound = false and (u.mobile is null or trim(u.mobile) = ''))) " +
            "and (:nicknamePattern is null or u.nickname like :nicknamePattern escape '!' " +
            "or (:exactMobile is not null and u.mobile = :exactMobile))")
    Page<User> findAdminUsers(@Param("nicknamePattern") String nicknamePattern,
                              @Param("exactMobile") String exactMobile,
                              @Param("status") Integer status,
                              @Param("mobileBound") Boolean mobileBound,
                              Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") long id);
}
