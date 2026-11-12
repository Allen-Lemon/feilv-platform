package com.feilv.enums;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单状态机（对应功能设计文档 3.2 / 详细设计表3-1）。
 * 对外状态7个 + 已送达内部细分；全部状态变更必须经 OrderService.changeStatus() 走本机校验。
 */
public enum OrderStatus {

    WAIT_PAY("待支付"),
    WAIT_GRAB("待接单"),
    ACCEPTED("已接单"),
    PICKING("取件中"),
    DELIVERING("配送中"),
    DELIVERED("已送达"),
    FINISHED("已完成"),
    CANCELED("已取消");

    private final String label;
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new ConcurrentHashMap<>();

    static {
        ALLOWED.put(WAIT_PAY, EnumSet.of(WAIT_GRAB, CANCELED));   // 支付成功推进 / 15分钟未付关单
        ALLOWED.put(WAIT_GRAB, EnumSet.of(ACCEPTED, CANCELED));   // 抢单或派单成功 / 超时关单退款
        ALLOWED.put(ACCEPTED, EnumSet.of(PICKING, CANCELED));     // 取件核销 / 接单后取消按规则计费
        ALLOWED.put(PICKING, EnumSet.of(DELIVERING, CANCELED));   // 确认取件 / 取件后取消
        ALLOWED.put(DELIVERING, EnumSet.of(DELIVERED));           // 送达确认
        ALLOWED.put(DELIVERED, EnumSet.of(FINISHED));             // 用户确认 / 15分钟默认完成
        // FINISHED/CANCELED 为终态，无可流转
    }

    OrderStatus(String label) { this.label = label; }

    public String label() { return label; }

    /** 状态流转合法性校验：非法流转一律拒绝 */
    public boolean canTo(OrderStatus to) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(to);
    }
}
