package com.feilv.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.IOException;

public final class RiderCapabilityDtos {
    private RiderCapabilityDtos() {}

    public record CapabilityReq(
            @NotNull @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean enabled,
            @NotNull @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean expectedEnabled,
            @NotBlank @Size(max = 160) String reason) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不接受额外字段"); }
    }

    public static class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_TRUE) return true;
            if (parser.currentToken() == JsonToken.VALUE_FALSE) return false;
            return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
        }
    }
}
