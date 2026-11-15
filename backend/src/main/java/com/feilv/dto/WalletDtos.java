package com.feilv.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;

import java.io.IOException;
import java.time.LocalDateTime;

public final class WalletDtos {
    private WalletDtos() {}

    public record WithdrawalReq(
            @NotNull @Min(1) @Max(100000000) @JsonDeserialize(using = StrictLongDeserializer.class) Long amountFen,
            @NotBlank @Size(min = 8, max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String requestId) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不接受额外字段"); }
    }
    public record WithdrawalReviewReq(@NotBlank String decision, @NotBlank @Size(max = 255) String reason) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不接受额外字段"); }
    }
    public record WalletVO(long userId, long availableFen, long frozenFen, long debtFen,
                           long totalIncomeFen, long totalWithdrawnFen, String channel, boolean withdrawAllowed) {}
    public record AdminWalletVO(long userId, long availableFen, long frozenFen, long debtFen,
                                long totalIncomeFen, long totalWithdrawnFen, String channel, boolean withdrawAllowed,
                                String nickname, String mobile, String role, int status) {}
    public record EntryVO(long id, String type, long amountFen, long availableAfterFen, long frozenAfterFen,
                          long debtAfterFen, Long orderId, Long complaintId, Long withdrawalId,
                          String remark, LocalDateTime createdTime) {}
    public record WithdrawalVO(long id, long userId, long amountFen, String status, String requestId,
                               String reason, Long reviewedBy, LocalDateTime reviewedTime,
                               LocalDateTime createdTime, String channel) {}

    /** No floating point truncation, numeric strings, boolean coercion, or out-of-range longs. */
    public static class StrictLongDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }
    }
}
