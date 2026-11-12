package com.feilv.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/** API 请求/响应 DTO（record 精简实现，字段对齐详细设计第十章接口） */
public final class ApiDtos {

    private ApiDtos() {}

    // ---------- 认证 ----------
    public record SmsCodeReq(@NotBlank String mobile) {}

    public record LoginReq(@NotBlank String mobile, @NotBlank String code) {}

    public record LoginVO(String token, long userId, String role, String nickname) {}

    public record WechatLoginReq(@NotBlank @jakarta.validation.constraints.Size(max = 256) String code,
                                  @NotNull Boolean agreed) {}

    public record AdminLoginReq(@NotBlank String username, @NotBlank String password) {}

    public record AdminMenuVO(long id, String code, String name, String path, long parentId, int sort) {}

    public record AdminProfileVO(long userId, String username, String nickname, List<String> roles,
                                 List<AdminMenuVO> menus) {}

    // ---------- 下单（对应详细设计 4.2）----------
    public record QuoteReq(@NotBlank String orderType,
                           @NotNull Integer distanceM,
                           @NotNull @Min(1) @Max(100) Integer weightKg) {}

    public record FeeItem(String feeType, String label, int amountFen) {}

    public record QuoteVO(int totalFen, List<FeeItem> items) {}

    public record OrderCreateReq(@NotBlank String orderType,
                                 @NotBlank String fromContact,
                                 @NotBlank String fromPhone,
                                 @NotBlank String fromAddress,
                                 BigDecimal fromLng, BigDecimal fromLat,
                                 @NotBlank String toContact,
                                 @NotBlank String toPhone,
                                 @NotBlank String toAddress,
                                 BigDecimal toLng, BigDecimal toLat,
                                 @NotNull Integer distanceM,
                                 @NotNull Integer weightKg,
                                 String goodsDesc,
                                 String remark) {}

    public record OrderVO(long id, String orderNo, String orderType, String status, String statusLabel,
                          String fromAddress, String toAddress, String goodsDesc,
                          int estimateFeeFen, int realFeeFen, Long riderId,
                          String pickupCode, String createdTime, String acceptedTime,
                          String deliveredTime, String finishTime) {}

    // ---------- 骑手（对应详细设计 5.1/5.2）----------
    public record RiderRegisterReq(@NotBlank String mobile,
                                   @NotBlank String name,
                                   @NotBlank String code,
                                   String plateNo) {}

    public record RiderVO(long riderId, String name, int creditScore, int onlineStatus, String status) {}

    public record GrabVO(long orderId, String orderNo, String orderType, String fromAddress,
                        String toAddress, int distanceM, int rewardFen, String createdTime) {}

    // ---------- 轨迹 ----------
    public record TrackPointReq(@NotNull Long orderId, BigDecimal lng, BigDecimal lat, BigDecimal speed) {}

    public record TrackPoint(BigDecimal lng, BigDecimal lat, String time) {}

    // ---------- 评价（对应详细设计 4.5/5.5）----------
    public record ReviewReq(@NotNull Long orderId,
                             @NotNull @Min(1) @Max(5) Integer stars,
                             String content) {}

    // ---------- 投诉 ----------
    public record ComplaintReq(@NotNull Long orderId,
                               @NotBlank String type,
                               String description) {}

    // ---------- 后台 ----------
    public record StatsVO(long userCount, long riderCount, long orderCountToday,
                          long orderCountTotal, long incomeTodayFen) {}

    public record CancelVO(String reason) {}
}
