package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.Complaint;
import com.feilv.domain.CreditLog;
import com.feilv.domain.OrderMain;
import com.feilv.domain.Review;
import com.feilv.domain.Rider;
import com.feilv.dto.ApiDtos.ComplaintReq;
import com.feilv.dto.ApiDtos.ReviewReq;
import com.feilv.enums.Enums.CreditReason;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.ComplaintRepository;
import com.feilv.repository.CreditLogRepository;
import com.feilv.repository.ReviewRepository;
import com.feilv.repository.RiderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 评价风控域（对应功能设计文档 4.5/5.5/8.1）：
 * 评价入库 → 信用分事件驱动加减（详细设计表5-1）→ 等级联动预警；投诉工单。
 * 禁止人工直接改分，全部凭事件码留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewRiskService {

    private final ReviewRepository reviewRepository;
    private final CreditLogRepository creditLogRepository;
    private final RiderRepository riderRepository;
    private final ComplaintRepository complaintRepository;
    private final OrderService orderService;

    /** 提交评价：订单须已完成、本人、且一单一评 */
    @Transactional
    public void submitReview(long userId, ReviewReq req) {
        OrderMain order = orderService.mustGet(req.orderId());
        if (order.getUserId() != userId) throw BizException.of(403, "无权评价他人订单");
        if (order.getStatus() != OrderStatus.FINISHED) {
            throw BizException.of(4090, "订单完成后才可评价");
        }
        if (order.getFinishTime() == null || order.getFinishTime().isBefore(java.time.LocalDateTime.now().minusDays(7))) {
            throw BizException.of(4090, "订单完成超过7天，已不能评价");
        }
        if (reviewRepository.existsByOrderId(order.getId())) {
            throw BizException.of(4093, "该订单已评价过（一单一评）");
        }
        Review review = new Review();
        review.setOrderId(order.getId());
        review.setUserId(userId);
        review.setRiderId(order.getRiderId());
        review.setStars(req.stars());
        review.setContent(req.content() == null ? "" : req.content());
        reviewRepository.save(review);

        // 信用分联动（详细设计表5-1：5星+1 / 1-2星-2）
        CreditReason reason = switch (req.stars()) {
            case 5 -> CreditReason.FIVE_STAR;
            case 1, 2 -> CreditReason.BAD_REVIEW;
            default -> CreditReason.GOOD_STAR; // 3-4星不加减分
        };
        if (reason.delta() != 0 && order.getRiderId() != null) {
            applyCredit(order.getRiderId(), reason, order.getId());
        }
    }

    /** 信用分变动：更新分数 + 留痕 + 等级联动预警（详细设计5.5.1） */
    @Transactional
    public void applyCredit(long riderId, CreditReason reason, Long refOrderId) {
        Rider rider = riderRepository.findById(riderId)
                .orElseThrow(() -> BizException.of(404, "骑手不存在"));
        int after = rider.getCreditScore() + reason.delta();
        rider.setCreditScore(after);
        riderRepository.save(rider);

        CreditLog clog = new CreditLog();
        clog.setRiderId(riderId);
        clog.setChangeValue(reason.delta());
        clog.setReasonCode(reason.name());
        clog.setRefOrderId(refOrderId);
        creditLogRepository.save(clog);

        if (after < 40) {
            log.warn("[风控]骑手{}信用分降至{}（＜40），触发停单整改（演示仅提醒）", riderId, after);
        } else if (after < 60) {
            log.warn("[风控]骑手{}信用分降至{}（＜60），限制抢单并推送复训", riderId, after);
        } else if (after < 80) {
            log.info("[调度]骑手{}信用分{}（＜80），降低派单权重", riderId, after);
        } else if (after >= 110) {
            log.info("[调度]骑手{}信用分{}（≥110），优先派单与高佣权益", riderId, after);
        }
    }

    /** 提交投诉工单（8:00-22:00客服响应≤30分钟，一般投诉48小时办结——生产由工单系统承接） */
    @Transactional
    public long complaint(long userId, ComplaintReq req) {
        OrderMain order = orderService.mustGet(req.orderId());
        if (order.getUserId() != userId) throw BizException.of(403, "无权投诉他人订单");
        Complaint c = new Complaint();
        c.setOrderId(order.getId());
        c.setUserId(userId);
        try {
            c.setType(com.feilv.enums.Enums.ComplaintType.valueOf(req.type()).name());
        } catch (IllegalArgumentException e) {
            throw BizException.of(400, "投诉类型不合法");
        }
        c.setDescription(req.description() == null ? "" : req.description());
        c.setStatus("OPEN");
        complaintRepository.save(c);
        log.info("工单#{}已生成（订单{}），待客服分派", c.getId(), order.getOrderNo());
        return c.getId();
    }

    /** 骑手信用分明细 */
    @Transactional(readOnly = true)
    public List<CreditLog> creditLogs(long riderUserId) {
        Rider rider = riderRepository.findByUserId(riderUserId)
                .orElseThrow(() -> BizException.of(403, "当前账户未注册为骑手"));
        return creditLogRepository.findByRiderIdOrderByIdDesc(rider.getId());
    }

    /** 骑手评分统计：好评率 */
    @Transactional(readOnly = true)
    public String riderStars(long riderId) {
        List<Review> list = reviewRepository.findByRiderIdOrderByIdDesc(riderId);
        if (list.isEmpty()) return "暂无评价";
        double avg = list.stream().mapToInt(Review::getStars).average().orElse(5);
        return String.format("%.1f分（%d条评价）", avg, list.size());
    }
}
