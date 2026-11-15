package com.feilv.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

public final class ComplaintDtos {
    private ComplaintDtos() {}

    public record ComplaintReviewReq(@NotBlank String decision,
                                     @NotBlank @Size(max = 255) String reason,
                                     @NotNull @Min(0) @JsonDeserialize(using = StrictFenDeserializer.class) Integer amountFen) {}

    /** 旧handle仅兼容零元受理/办结；两种金额字段都严格解析，不能截断或绕过审核扣款。 */
    public record ComplaintHandleReq(String status, @NotBlank @Size(max = 255) String result,
                                     @Min(0) @JsonDeserialize(using = StrictFenDeserializer.class) Integer compensationFen,
                                     @Min(0) @JsonDeserialize(using = StrictFenDeserializer.class) Integer amountFen) {}

    public record ComplaintActionVO(String action, String detail, Long operatorId, LocalDateTime createdTime) {}

    public record ComplaintVO(Long id, Long orderId, String orderNo, Long userId, Long riderId,
                              String type, String description, String status, String result,
                              int compensationFen, long deductedFen, long orderDeductedFen,
                              long maxDeductibleFen, String paymentChannel, LocalDateTime createdTime,
                              LocalDateTime handledTime, Long handledBy, List<ComplaintActionVO> actions) {}

    /** Jackson默认会将1.5截成1；金额必须是JSON整数，禁止字符串、浮点、小数和溢出。 */
    public static class StrictFenDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }
}
