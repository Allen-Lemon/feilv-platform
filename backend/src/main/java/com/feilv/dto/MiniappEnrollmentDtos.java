package com.feilv.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.feilv.common.BizException;

import java.util.Set;

/** The enrollment body deliberately has no account selector, role, status or capability fields. */
public final class MiniappEnrollmentDtos {
    private static final Set<String> FIELDS = Set.of("name", "plateNo", "phoneCode", "mobile", "agreed");

    private MiniappEnrollmentDtos() {}

    public record EnrollmentReq(String name, String plateNo, String phoneCode, Boolean agreed, String mobile) {
        public EnrollmentReq(String name, String plateNo, String phoneCode, Boolean agreed) {
            this(name, plateNo, phoneCode, agreed, null);
        }
    }

    public static EnrollmentReq parse(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        var names = body.fieldNames();
        while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid();
        // A present null still counts: neither proof, both proofs, null and coerced values are invalid.
        if (body.has("phoneCode") == body.has("mobile")) throw invalid();
        var proof = body.get(body.has("mobile") ? "mobile" : "phoneCode");
        if (!body.path("name").isTextual() || !proof.isTextual() || proof.textValue().isBlank()
                || !body.path("agreed").isBoolean()) throw invalid();
        var plate = body.get("plateNo");
        if (plate != null && !plate.isNull() && (!plate.isTextual()
                || plate.textValue().strip().codePointCount(0, plate.textValue().strip().length()) > 16)) throw invalid();
        return new EnrollmentReq(body.get("name").textValue(),
                plate == null || plate.isNull() ? null : plate.textValue(),
                body.has("phoneCode") ? proof.textValue() : null, body.get("agreed").booleanValue(),
                body.has("mobile") ? proof.textValue() : null);
    }

    private static BizException invalid() {
        return BizException.of(400, "入驻仅接受姓名、车牌、单一手机号凭证及协议确认");
    }
}
