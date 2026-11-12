package com.feilv.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 消息域（对应功能设计文档 8.5）：三通道统一触达，演示以日志模拟，生产对接短信网关/订阅消息/厂商推送 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    /** 模拟短信下发（生产：短信网关，到达率≥99%） */
    public void sendSms(String mobile, String content) {
        log.info("[短信]{}:{}", mobile, content);
    }

    /** 订单状态变更通知（通道降级：订阅消息→推送→短信） */
    public void notifyStatusChange(String orderNo, String fromLabel, String toLabel, String target) {
        log.info("[通知]{} 您的订单已由[{}]变为[{}]（接收方：{}）", orderNo, fromLabel, toLabel, target);
    }

    /** 营销/提醒类消息 */
    public void notifyPush(String target, String content) {
        log.info("[推送]{}:{}", target, content);
    }
}
