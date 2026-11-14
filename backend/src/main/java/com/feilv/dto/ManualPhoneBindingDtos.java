package com.feilv.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 手动绑号只接受手机号和绑定验证码，不接受任何客户端身份字段。 */
public final class ManualPhoneBindingDtos {
    private ManualPhoneBindingDtos() {}

    public record SmsCodeReq(
            @JsonDeserialize(using = StrictStringDeserializer.class)
            @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}", message = "请输入有效的11位手机号") String mobile) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("请求包含不支持的字段"); }
        @Override public String toString() { return "SmsCodeReq[mobile=REDACTED]"; }
    }

    public record BindPhoneReq(
            @JsonDeserialize(using = StrictStringDeserializer.class)
            @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}", message = "请输入有效的11位手机号") String mobile,
            @JsonDeserialize(using = StrictStringDeserializer.class)
            @NotBlank @Pattern(regexp = "[0-9]{6}", message = "请输入6位短信验证码") String code) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("请求包含不支持的字段"); }
        @Override public String toString() { return "BindPhoneReq[mobile=REDACTED, code=REDACTED]"; }
    }

    /** 只在此接口拒绝 Jackson 默认的数字/布尔到字符串强制转换，不改变其他 API。 */
    public static final class StrictStringDeserializer extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getText();
        }
    }

    public record SmsCodeVO(int retryAfterSeconds, boolean mock, String demoCode) {
        @Override public String toString() {
            return "SmsCodeVO[retryAfterSeconds=" + retryAfterSeconds + ", mock=" + mock + ", demoCode=REDACTED]";
        }
    }
}
