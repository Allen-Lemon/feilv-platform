package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.Rider;
import com.feilv.domain.RiderTrack;
import com.feilv.dto.ApiDtos.TrackPoint;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.RiderTrackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 轨迹服务（对应详细设计 4.4.2/5.3.5）：
 * 骑手配送中位置上报（5-10秒速度自适应，由骑手端控制调用间隔），用户端查询刷新≤5秒，保存≥90天。
 */
@Service
@RequiredArgsConstructor
public class TrackService {

    private final RiderTrackRepository trackRepository;
    private final RiderRepository riderRepository;
    private final OrderService orderService;

    /** 骑手上报轨迹：仅本人承运且配送中的订单可上报 */
    @Transactional
    public void report(long riderUserId, long orderId, BigDecimal lng, BigDecimal lat, BigDecimal speed) {
        Rider rider = riderRepository.findByUserId(riderUserId)
                .orElseThrow(() -> BizException.of(403, "当前账户未注册为骑手"));
        var order = orderService.mustGet(orderId);
        requireDelivery(order);
        if (order.getRiderId() == null || !rider.getId().equals(order.getRiderId())) {
            throw BizException.of(403, "该订单不属于当前骑手");
        }
        if (order.getStatus() != OrderStatus.DELIVERING) {
            throw BizException.of(4090, "仅配送中订单可上报轨迹");
        }
        RiderTrack t = new RiderTrack();
        t.setOrderId(orderId);
        t.setRiderId(rider.getId());
        t.setLng(lng);
        t.setLat(lat);
        t.setSpeed(speed);
        trackRepository.save(t);
    }

    /** 用户端查询轨迹（最多最近50个点，倒序存储前端自行反转） */
    @Transactional(readOnly = true)
    public List<TrackPoint> query(long orderId) {
        requireDelivery(orderService.mustGet(orderId));
        return trackRepository.findTop50ByOrderIdOrderByIdDesc(orderId).stream()
                .map(t -> new TrackPoint(t.getLng(), t.getLat(),
                        t.getReportTime() == null ? "" : t.getReportTime().toString()))
                .toList();
    }

    private void requireDelivery(com.feilv.domain.OrderMain order) {
        if (orderService.isServiceTask(order)) throw BizException.of(4090, "服务型任务不提供配送轨迹");
    }
}
