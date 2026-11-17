package com.feilv.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.feilv.common.BizException;

import java.util.Set;

/** The enrollment body deliberately has no account selector, role, status or capability fields. */
public final class MiniappEnrollmentDtos {
    private static final Set<String> FIELDS = Set.of("name", "plateNo", "phoneCode", "agreed");

    private MiniappEnrollmentDtos() {}

    public record EnrollmentReq(String name, String plateNo, String phoneCode, Boolean agreed) {}

    public static EnrollmentReq parse(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        var names = body.fieldNames();
        while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid();
        if (!body.path("name").isTextual() || !body.path("phoneCode").isTextual()
                || !body.path("agreed").isBoolean()) throw invalid();
        var plate = body.get("plateNo");
        if (plate != null && !plate.isNull() && (!plate.isTextual()
                || plate.textValue().strip().codePointCount(0, plate.textValue().strip().length()) > 16)) throw invalid();
        return new EnrollmentReq(body.get("name").textValue(),
                plate == null || plate.isNull() ? null : plate.textValue(),
                body.get("phoneCode").textValue(), body.get("agreed").booleanValue());
    }

    private static BizException invalid() {
        return BizException.of(400, "入驻仅接受姓名、车牌、微信手机号授权及协议确认");
    }
}
