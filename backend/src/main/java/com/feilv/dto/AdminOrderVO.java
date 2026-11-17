package com.feilv.dto;

import com.feilv.domain.User;
import com.feilv.dto.ApiDtos.OrderVO;
import com.feilv.service.UserViews;

/** 后台平铺订单视图；保留原OrderVO全部字段，C端OrderVO不增加任何用户资料。 */
public record AdminOrderVO(long id, String orderNo, String orderType, String status, String statusLabel,
                           String fromAddress, String toAddress, String goodsDesc,
                           int estimateFeeFen, int realFeeFen, Long riderId,
                           String pickupCode, String createdTime, String acceptedTime,
                           String deliveredTime, String finishTime,
                           Long userId, String userNickname, String userAvatarUrl,
                           String userMobile, boolean userMobileBound, String taskMode, Integer goodsBudgetFen,
                           String locationAddress, String serviceTime) {
    /** 保留增加地点/时间摘要前的完整构造器。 */
    public AdminOrderVO(long id, String orderNo, String orderType, String status, String statusLabel,
                        String fromAddress, String toAddress, String goodsDesc,
                        int estimateFeeFen, int realFeeFen, Long riderId,
                        String pickupCode, String createdTime, String acceptedTime,
                        String deliveredTime, String finishTime,
                        Long userId, String userNickname, String userAvatarUrl,
                        String userMobile, boolean userMobileBound, String taskMode, Integer goodsBudgetFen) {
        this(id, orderNo, orderType, status, statusLabel, fromAddress, toAddress, goodsDesc, estimateFeeFen, realFeeFen,
                riderId, pickupCode, createdTime, acceptedTime, deliveredTime, finishTime,
                userId, userNickname, userAvatarUrl, userMobile, userMobileBound, taskMode, goodsBudgetFen, null, null);
    }

    public AdminOrderVO(long id, String orderNo, String orderType, String status, String statusLabel,
                        String fromAddress, String toAddress, String goodsDesc,
                        int estimateFeeFen, int realFeeFen, Long riderId,
                        String pickupCode, String createdTime, String acceptedTime,
                        String deliveredTime, String finishTime,
                        Long userId, String userNickname, String userAvatarUrl,
                        String userMobile, boolean userMobileBound, String taskMode) {
        this(id, orderNo, orderType, status, statusLabel, fromAddress, toAddress, goodsDesc, estimateFeeFen, realFeeFen,
                riderId, pickupCode, createdTime, acceptedTime, deliveredTime, finishTime,
                userId, userNickname, userAvatarUrl, userMobile, userMobileBound, taskMode, null);
    }

    public static AdminOrderVO from(OrderVO order, Long userId, User user) {
        return from(order, userId, user, null, null);
    }

    public static AdminOrderVO from(OrderVO order, Long userId, User user, String locationAddress, String serviceTime) {
        return new AdminOrderVO(order.id(), order.orderNo(), order.orderType(), order.status(), order.statusLabel(),
                order.fromAddress(), order.toAddress(), order.goodsDesc(), order.estimateFeeFen(), order.realFeeFen(),
                order.riderId(), order.pickupCode(), order.createdTime(), order.acceptedTime(), order.deliveredTime(),
                order.finishTime(), userId, user == null ? "" : UserViews.text(user.getNickname()),
                user == null ? "" : UserViews.text(user.getAvatarUrl()), UserViews.maskedMobile(user), UserViews.mobileBound(user), order.taskMode(), order.goodsBudgetFen(), locationAddress, serviceTime);
    }
}
