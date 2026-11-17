package com.feilv.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class CustomTaskDtos {
    private CustomTaskDtos() {}
    public record TagReq(@NotBlank @Size(max = 64) String name, @NotNull Boolean enabled,
                         @NotNull @Min(0) @Max(100000) Integer sort, @Min(0) Long version,
                         @NotNull @Size(min = 1, max = 5) List<@NotNull @Pattern(regexp = "ONSITE|PART_TIME|ONLINE|RIDE|DRIVING") String> taskModes) {
        /** 仅保留Java源兼容；未明确分类的请求仍会被保存校验拒绝。 */
        public TagReq(String name, Boolean enabled, Integer sort, Long version) { this(name, enabled, sort, version, null); }
    }
    public record TagVO(long id, String name, boolean enabled, int sort, long version, List<String> taskModes) {
        public TagVO(long id, String name, boolean enabled, int sort, long version) { this(id, name, enabled, sort, version, List.of()); }
    }
    public record TagSnapshot(long id, String name) {}
    public record QuoteReq(@NotNull @Min(1) @Max(100000000)
                           @JsonDeserialize(using = ComplaintDtos.StrictFenDeserializer.class) Integer priceFen) {
        public QuoteReq(Integer distanceM, Integer weightKg) { this(null); }
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支持的计价参数: " + name); }
    }
    public record QuoteVO(int totalFen, List<ApiDtos.FeeItem> items, int riderShareFen, int platformShareFen) {}
    public record PublishReq(String title, String content,
                             @Size(max = 5) List<@NotNull @Positive Long> tagIds,
                             @NotBlank @Pattern(regexp = "ONLINE|ONSITE|PART_TIME|RIDE|DRIVING") String taskMode,
                             @NotNull @Min(1) @Max(100000000)
                             @JsonDeserialize(using = ComplaintDtos.StrictFenDeserializer.class) Integer priceFen,
                             @NotBlank String contact, @NotBlank String phone,
                             String locationAddress, String serviceTime) {
        /** 仅保留Java源兼容；旧配送发布体缺少必需的新字段，不能再发布。 */
        public PublishReq(String title, String content, List<Long> tagIds, ApiDtos.OrderCreateReq order) {
            this(title, content, tagIds, null, null, null, null, null, null);
        }
        public PublishReq(String title, String content, List<Long> tagIds, String taskMode, Integer priceFen, String contact, String phone) {
            this(title, content, tagIds, taskMode, priceFen, contact, phone, null, null);
        }
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支持的发布参数: " + name); }
    }
    public record AdminPublishReq(@NotNull @Positive Long userId, String title, String content,
                                  @Size(max = 5) List<@NotNull @Positive Long> tagIds,
                                  @NotBlank @Pattern(regexp = "ONLINE|ONSITE|PART_TIME|RIDE|DRIVING") String taskMode,
                                  @NotNull @Min(1) @Max(100000000)
                                  @JsonDeserialize(using = ComplaintDtos.StrictFenDeserializer.class) Integer priceFen,
                                  @NotBlank String contact, @NotBlank String phone,
                                  String locationAddress, String serviceTime) {
        public AdminPublishReq(Long userId, String title, String content, List<Long> tagIds, ApiDtos.OrderCreateReq order) {
            this(userId, title, content, tagIds, null, null, null, null, null, null);
        }
        public PublishReq publication() { return new PublishReq(title, content, tagIds, taskMode, priceFen, contact, phone, locationAddress, serviceTime); }
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支持的发布参数: " + name); }
    }
    public record TaskVO(long id, long orderId, String orderNo, String status, String statusLabel,
                         long userId, String title, String content, List<TagSnapshot> tags,
                         String publishSource, long createdBy, LocalDateTime createdTime,
                         int amountFen, String fromAddress, String toAddress,
                         String taskMode, Integer priceFen, String contact, String phone,
                         String locationAddress, String serviceTime, int riderShareFen, int platformShareFen) {
        public TaskVO(long id, long orderId, String orderNo, String status, String statusLabel,
                      long userId, String title, String content, List<TagSnapshot> tags,
                      String publishSource, long createdBy, LocalDateTime createdTime,
                      int amountFen, String fromAddress, String toAddress) {
            this(id, orderId, orderNo, status, statusLabel, userId, title, content, tags, publishSource, createdBy,
                    createdTime, amountFen, fromAddress, toAddress, "DELIVERY", amountFen, null, null, null, null, 0, 0);
        }
    }
}
