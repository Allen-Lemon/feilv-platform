package com.feilv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 德江县"飞毛腿"城市同城跑腿平台后端服务。
 * 包结构对应《功能设计文档》第三章：user(用户域)/order(订单域)/dispatch(调度域)
 * pay(支付结算域)/message(消息域)/risk(评价风控域)/settle(账务域)。
 */
@EnableScheduling
@SpringBootApplication
public class FeilvApplication {
    public static void main(String[] args) {
        SpringApplication.run(FeilvApplication.class, args);
    }
}
