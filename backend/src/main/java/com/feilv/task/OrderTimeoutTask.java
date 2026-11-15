package com.feilv.task;

import com.feilv.enums.OrderStatus;
import com.feilv.repository.OrderRepository;
import com.feilv.service.MessageService;
import com.feilv.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时引擎（对应功能设计文档 8.1 / 详细设计表3-1）：
 * 1) 待支付 15 分钟未支付自动关单；
 * 2) 待接单超时提醒（生产：提醒→建议加价→关单，演示仅提醒）；
 * 3) 已送达 15 分钟未确认默认完成（触发分账）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutTask {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final MessageService messageService;

    @Value("${feilv.dispatch.order-grab-timeout-min}")
    private int grabTimeoutMin;

    /** 每60秒巡检一轮（演示节奏，生产可调至30秒） */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scan() {
        // 每单由OrderService独立事务完成并入账，避免扫描批量持有多笔订单/钱包锁。
        LocalDateTime now = LocalDateTime.now();

        // 待支付15分钟 → 自动关单
        List<com.feilv.domain.OrderMain> unpaid =
                orderRepository.findByStatusAndCreatedTimeBefore(OrderStatus.WAIT_PAY, now.minusMinutes(15));
        for (var order : unpaid) {
            orderService.changeStatus(order, OrderStatus.CANCELED, "SYSTEM", null, "15分钟未支付自动关单");
            messageService.notifyPush("用户", "订单" + order.getOrderNo() + "超时未支付已自动关闭");
        }

        // 待接单超时 → 提醒（生产：提醒→建议加价→再超时关单退款）
        List<com.feilv.domain.OrderMain> waiting =
                orderRepository.findByStatusAndCreatedTimeBefore(OrderStatus.WAIT_GRAB, now.minusMinutes(grabTimeoutMin));
        for (var order : waiting) {
            log.warn("[超时引擎]订单{}待接单已超{}分钟，通知用户可加价提升接单率",
                    order.getOrderNo(), grabTimeoutMin);
            messageService.notifyPush("用户", "订单长时间无人接单，建议加价激励骑手");
        }

        // 已送达15分钟未确认 → 默认完成（详细设计表3-1）
        List<com.feilv.domain.OrderMain> delivered =
                orderRepository.findByStatusAndDeliveredTimeBefore(OrderStatus.DELIVERED, now.minusMinutes(15));
        for (var order : delivered) {
            orderService.changeStatus(order, OrderStatus.FINISHED, "SYSTEM", null, "15分钟未确认默认完成");
            messageService.notifyPush("用户", "订单已默认确认完成，欢迎评价");
        }

        if (!unpaid.isEmpty() || !waiting.isEmpty() || !delivered.isEmpty()) {
            log.info("[超时引擎]本轮处理：关单{}，待接单提醒{}，默认完成{}",
                    unpaid.size(), waiting.size(), delivered.size());
        }
    }
}
