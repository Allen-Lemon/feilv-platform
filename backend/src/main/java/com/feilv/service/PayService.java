package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.OrderMain;
import com.feilv.domain.PayRecord;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.PayRecordRepository;
import com.feilv.util.BizNoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付结算域（对应功能设计文档 3.4/8.3）：
 * 生产环境经持牌机构（微信/支付宝）收单分账，平台不设资金池；
 * 本工程以 MOCK 渠道模拟收单成功，并按分账比例记录账务明细（T+1对账预留）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayService {

    @Value("${feilv.share.rider-percent}")
    private int riderPercent;

    private final PayRecordRepository payRecordRepository;
    private final OrderService orderService;
    private final com.feilv.repository.OrderRepository orderRepository;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    /** 模拟支付成功：生成流水 + 计算分账 + 推进订单待接单 */
    @Transactional
    public PayRecord pay(long userId, long orderId) {
        OrderMain order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> BizException.of(404, "订单不存在"));
        entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (order.getUserId() != userId) throw BizException.of(403, "无权支付他人订单");
        if (order.getStatus() == OrderStatus.WAIT_GRAB) {
            return payRecordRepository.findByOrderId(orderId).stream().findFirst()
                    .orElseThrow(() -> BizException.of(500, "订单状态异常"));
        }
        if (order.getStatus() != OrderStatus.WAIT_PAY) {
            throw BizException.of(4090, "订单状态不可支付");
        }

        int amount = order.getEstimateFee();
        int shareRider = amount * riderPercent / 100;
        int sharePlatform = amount - shareRider;

        PayRecord record = new PayRecord();
        record.setPayNo(BizNoUtil.payNo());
        record.setOrderId(orderId);
        record.setChannel("MOCK"); // 生产：WECHAT/ALIPAY 统一网关
        record.setAmount(amount);
        record.setShareRider(shareRider);
        record.setSharePlatform(sharePlatform);
        record.setStatus("SUCCESS");
        payRecordRepository.save(record);

        orderService.changeStatus(order, OrderStatus.WAIT_GRAB, "USER", userId, "支付成功（模拟渠道）");
        log.info("支付成功：订单{}，实付{}分，骑手分账{}分，平台{}分",
                order.getOrderNo(), amount, shareRider, sharePlatform);
        return record;
    }

    /** 取消退款模拟：生产由持牌机构原路退回 */
    public void refundMock(OrderMain order) {
        log.info("[退款模拟]订单{}按渠道规则原路退回{}分", order.getOrderNo(), order.getEstimateFee());
    }
}
