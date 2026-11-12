package com.feilv.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/** 单号生成（对应详细设计8.1：订单号全局唯一、可读、对外不可猜测） */
public final class BizNoUtil {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private BizNoUtil() {}

    /** 订单号：F + yyyyMMddHHmmss + 3位随机 */
    public static String orderNo() {
        return "F" + LocalDateTime.now().format(FMT) + threeRandom();
    }

    /** 支付单号：P + yyyyMMddHHmmss + 3位随机 */
    public static String payNo() {
        return "P" + LocalDateTime.now().format(FMT) + threeRandom();
    }

    /** 取件码：4位数字 */
    public static String pickupCode() {
        return String.valueOf(1000 + ThreadLocalRandom.current().nextInt(9000));
    }

    private static String threeRandom() {
        return String.valueOf(100 + ThreadLocalRandom.current().nextInt(900));
    }
}
