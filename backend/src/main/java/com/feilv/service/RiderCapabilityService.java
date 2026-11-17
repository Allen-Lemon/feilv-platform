package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.OrderMain;
import com.feilv.domain.Rider;
import com.feilv.dto.RiderCapabilityDtos.CapabilityReq;
import com.feilv.repository.CustomTaskRepository;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** 订单操作先锁订单，再锁骑手；后台仅锁骑手，不反向获取订单或用户锁。 */
@Service
@RequiredArgsConstructor
public class RiderCapabilityService {
    private final RiderRepository riders;
    private final UserRepository users;
    private final CustomTaskRepository tasks;
    private final EntityManager entityManager;
    private final AuditService audit;
    private final AdminMenuAccess menuAccess;

    @Transactional
    public Map<String, Object> update(long riderId, String mode, CapabilityReq req) {
        menuAccess.requireMenu("riders");
        if (!"RIDE".equals(mode) && !"DRIVING".equals(mode)) throw BizException.of(400, "接单能力类别不合法");
        if (req == null || req.enabled() == null || req.expectedEnabled() == null || req.reason() == null
                || req.reason().isBlank() || req.reason().length() > 160) throw BizException.of(400, "授权参数或原因不合法");
        Rider rider = lock(riderId);
        boolean previous = "RIDE".equals(mode) ? rider.isRideEnabled() : rider.isDrivingEnabled();
        if (previous != req.expectedEnabled()) throw BizException.of(409, "授权已被其他操作修改，请刷新后重试");
        if (previous == req.enabled()) return view(rider);
        if ("RIDE".equals(mode)) rider.setRideEnabled(req.enabled()); else rider.setDrivingEnabled(req.enabled());
        riders.saveAndFlush(rider);
        audit.record("RIDER_CAPABILITY_UPDATE", "riderId=" + riderId + ", mode=" + mode + ", from=" + previous
                + ", to=" + req.enabled() + ", reason=" + req.reason().trim());
        return view(rider);
    }

    /** 调用者必须已持有订单锁；撤权和承接/首次开始在同一骑手行锁上串行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Rider requireForOrder(OrderMain order, Rider rider) {
        if (!"CUSTOM".equals(order.getOrderType())) return rider;
        String mode = tasks.findByOrderId(order.getId()).map(t -> t.getTaskMode() == null ? "DELIVERY" : t.getTaskMode()).orElse("DELIVERY");
        if (!"RIDE".equals(mode) && !"DRIVING".equals(mode)) return rider;
        Rider current = lock(rider.getId());
        var user = users.findById(current.getUserId()).orElseThrow(() -> BizException.of(403, "骑手账户不可用"));
        entityManager.refresh(user);
        if (!"ACTIVE".equals(current.getStatus()) || user.getStatus() != 1 || !"RIDER".equals(user.getRole())) {
            throw BizException.of(403, "仅正常现役骑手可以承接服务");
        }
        if (!("RIDE".equals(mode) ? current.isRideEnabled() : current.isDrivingEnabled())) {
            throw BizException.of(403, "尚未获得" + ("RIDE".equals(mode) ? "接送" : "代驾") + "接单授权，请联系平台");
        }
        return current;
    }

    private Rider lock(long id) {
        Rider rider = riders.findByIdForUpdate(id).orElseThrow(() -> BizException.of(404, "骑手不存在"));
        entityManager.refresh(rider, LockModeType.PESSIMISTIC_WRITE);
        return rider;
    }

    public static Map<String, Object> view(Rider rider) {
        return Map.of("riderId", rider.getId(), "name", rider.getName(), "creditScore", rider.getCreditScore(),
                "onlineStatus", rider.getOnlineStatus(), "status", rider.getStatus(),
                "rideEnabled", rider.isRideEnabled(), "drivingEnabled", rider.isDrivingEnabled());
    }
}
