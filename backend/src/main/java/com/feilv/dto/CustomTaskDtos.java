package com.feilv.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class CustomTaskDtos {
    private CustomTaskDtos() {}
    public record TagReq(@NotBlank @Size(max = 64) String name, @NotNull Boolean enabled,
                         @NotNull @Min(0) @Max(100000) Integer sort, @Min(0) Long version) {}
    public record TagVO(long id, String name, boolean enabled, int sort, long version) {}
    public record TagSnapshot(long id, String name) {}
    public record QuoteReq(@NotNull @Min(1) @Max(200000) Integer distanceM,
                           @NotNull @Min(1) @Max(100) Integer weightKg) {}
    public record PublishReq(@NotBlank @Size(max = 128) String title,
                             @NotBlank @Size(max = 2000) String content,
                             @Size(max = 5) List<@NotNull @Positive Long> tagIds,
                             @NotNull @Valid ApiDtos.OrderCreateReq order) {}
    public record AdminPublishReq(@NotNull @Positive Long userId,
                                  @NotBlank @Size(max = 128) String title,
                                  @NotBlank @Size(max = 2000) String content,
                                  @Size(max = 5) List<@NotNull @Positive Long> tagIds,
                                  @NotNull @Valid ApiDtos.OrderCreateReq order) {}
    public record TaskVO(long id, long orderId, String orderNo, String status, String statusLabel,
                         long userId, String title, String content, List<TagSnapshot> tags,
                         String publishSource, long createdBy, LocalDateTime createdTime,
                         int amountFen, String fromAddress, String toAddress) {}
}
