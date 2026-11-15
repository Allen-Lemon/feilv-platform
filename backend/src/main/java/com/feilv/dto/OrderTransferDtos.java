package com.feilv.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

public final class OrderTransferDtos {
    private OrderTransferDtos() {}

    public record TransferReq(
            @NotNull @Positive @JsonDeserialize(using = WalletDtos.StrictLongDeserializer.class) Long targetRiderId,
            @NotBlank @Size(min = 1, max = 255) String reason) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不接受额外字段"); }
    }
    public record RespondReq(@NotBlank @Pattern(regexp = "ACCEPTED|REJECTED") String decision,
                             @Size(max = 255) String reason) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不接受额外字段"); }
    }
    public record CancelReq(@Size(max = 255) String reason) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不接受额外字段"); }
    }

    /** 不含地址、联系人、电话或取件码；历史涉及骑手只保留审计访问权。 */
    public record TransferVO(Long id, Long orderId, String orderNo, Long fromRiderId, String fromRiderName,
                             Long toRiderId, String toRiderName, String status, String reason, String responseReason,
                             LocalDateTime createdTime, LocalDateTime handledTime, String taskMode, String title) {}
}
