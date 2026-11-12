package com.feilv.enums;

import com.feilv.common.BizException;

/** 业务枚举集合：订单类型 / 支付渠道 / 信用分事项（对齐详细设计表5-1） */
public final class Enums {

    private Enums() {}

    /** 订单业务类型：跑腿送 / 跑腿买 / 跑腿办（功能设计文档 3.3） */
    public enum OrderType {
        SEND("跑腿送"), BUY("跑腿买"), ERRAND("跑腿办");

        private final String label;
        OrderType(String label) { this.label = label; }
        public String label() { return label; }

        public static OrderType of(String code) {
            try { return OrderType.valueOf(code); }
            catch (Exception e) { throw BizException.of(400, "订单类型不合法，仅支持 SEND/BUY/ERRAND"); }
        }
    }

    /** 支付渠道：生产经持牌机构（WECHAT/ALIPAY），演示采用 MOCK 直连成功 */
    public enum PayChannel { MOCK, WECHAT, ALIPAY }

    /** 信用分事项码（详细设计表5-1 默认规则） */
    public enum CreditReason {
        FIVE_STAR("五星好评", +1),
        GOOD_STAR("好评(4星)", 0),
        BAD_REVIEW("差评(1-2星)", -2),
        OVERTIME("超时送达", -2),
        COMPLAINT("投诉成立", -5),
        PUNISH("严重违规", -15);

        private final String label;
        private final int delta;
        CreditReason(String label, int delta) { this.label = label; this.delta = delta; }
        public int delta() { return delta; }
    }

    /** 投诉类型 */
    public enum ComplaintType { LOST, DAMAGE, OVERTIME, ATTITUDE, OTHER }
}
