package com.feilv.repository;

import com.feilv.domain.OrderMain;
import com.feilv.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderMain, Long> {

    Optional<OrderMain> findByOrderNo(String orderNo);

    Page<OrderMain> findByUserId(Long userId, Pageable pageable);

    Page<OrderMain> findByRiderId(Long riderId, Pageable pageable);

    Page<OrderMain> findByStatus(OrderStatus status, Pageable pageable);

    Page<OrderMain> findAllByOrderByCreatedTimeDesc(Pageable pageable);

    /** 超时引擎扫描（对应详细设计3.2：待接单超10分钟自动处理） */
    List<OrderMain> findByStatusAndCreatedTimeBefore(OrderStatus status, LocalDateTime time);

    /** 已送达订单按送达时间判断默认完成，不能使用创建时间替代。 */
    List<OrderMain> findByStatusAndDeliveredTimeBefore(OrderStatus status, LocalDateTime time);

    /** 经营看板：今日订单量 */
    long countByCreatedTimeAfter(LocalDateTime time);

    /**
     * 抢单公平锁（对应详细设计5.2.2）：
     * 数据库条件更新保证同一订单仅一名骑手抢成——UPDATE 影响行数=1 即抢成，=0 即已被他人抢走。
     * 生产高并发可升级为 Redis SETNX 分布式锁 + 本条件更新双重保险。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.status = :accepted, o.riderId = :riderId, o.acceptedTime = :now " +
           "WHERE o.id = :orderId AND o.status = :waitGrab")
    int grabOrder(@Param("orderId") Long orderId,
                  @Param("riderId") Long riderId,
                  @Param("accepted") OrderStatus accepted,
                  @Param("waitGrab") OrderStatus waitGrab,
                  @Param("now") LocalDateTime now);

    /** 后台人工改派（对应功能设计文档 7.1 订单干预） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.riderId = :riderId WHERE o.id = :orderId AND o.status = :accepted")
    int reassign(@Param("orderId") Long orderId, @Param("riderId") Long riderId,
                 @Param("accepted") OrderStatus accepted);
}
