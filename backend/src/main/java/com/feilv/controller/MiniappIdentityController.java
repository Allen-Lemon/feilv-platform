package com.feilv.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.feilv.common.BizException;
import com.feilv.common.Result;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.service.DemoAuthPolicy;
import com.feilv.service.DevelopmentPhonePolicy;
import com.feilv.service.MiniappIdentityLinkService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Narrow fixed-client protocol: no account, role or AppID selectors; manual phone is a separate proof mode. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MiniappIdentityController {
    private final MiniappIdentityLinkService identities;
    private final DemoAuthPolicy demo;
    private final DevelopmentPhonePolicy development;

    @PostMapping("/auth/miniapps/{client}/login")
    public Result<Map<String, Object>> login(@PathVariable String client, @RequestBody JsonNode body, HttpServletRequest request) {
        only(body, "code", "agreed");
        agreed(body);
        identities.throttleRequest(request.getRemoteAddr());
        var result = identities.login(client, text(body, "code", 256));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", result.state());
        if (result.login() != null) data.put("login", result.login());
        else { data.put("loginTicket", result.loginTicket()); data.put("expiresAt", result.expiresAt()); }
        return Result.ok(data);
    }

    @GetMapping("/auth/miniapps/{client}/options")
    public Result<Map<String, Boolean>> options(@PathVariable String client) {
        MiniappIdentityLinkService.client(client);
        return Result.ok(Map.of("smsLoginEnabled", "USER".equals(client) && demo.enabled(),
                "developmentManualPhoneEnabled", "RIDER".equals(client) && development.enabled()));
    }

    @PostMapping("/auth/miniapps/{client}/accounts")
    public Result<LoginVO> account(@PathVariable String client, @RequestBody JsonNode body, HttpServletRequest request) {
        only(body, "loginTicket", "phoneCode", "mobile", "agreed");
        // has(), not hasNonNull(): even a null alternative counts as a supplied proof field.
        if (body.has("phoneCode") == body.has("mobile")) throw invalid();
        String ticket = text(body, "loginTicket", 43);
        boolean agreed = agreed(body);
        String proof = text(body, body.has("mobile") ? "mobile" : "phoneCode", body.has("mobile") ? 11 : 256);
        identities.throttleRequest(request.getRemoteAddr());
        return Result.ok(body.has("mobile")
                ? identities.createDevelopmentAccount(client, ticket, proof, agreed)
                : identities.createAccount(client, ticket, proof, agreed));
    }

    @PostMapping("/auth/miniapps/{client}/identity-link")
    public Result<LoginVO> link(@PathVariable String client, @RequestBody JsonNode body, HttpServletRequest request) {
        only(body, "loginTicket", "linkCode", "agreed");
        identities.throttleRequest(request.getRemoteAddr());
        return Result.ok(identities.link(client, text(body, "loginTicket", 43), text(body, "linkCode", 43), agreed(body)));
    }

    @PostMapping("/user/miniapp-link-codes")
    public Result<MiniappIdentityLinkService.LinkCode> linkCode(@RequestBody JsonNode body, HttpServletRequest request) {
        only(body, "code", "agreed");
        identities.throttleRequest(request.getRemoteAddr());
        return Result.ok(identities.issueLinkCode(text(body, "code", 256), agreed(body)));
    }

    private static void only(JsonNode body, String... fields) {
        if (body == null || !body.isObject()) throw invalid();
        Set<String> allowed = Set.of(fields);
        body.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw invalid(); });
    }
    private static String text(JsonNode body, String field, int maximum) {
        var value = body.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maximum) throw invalid();
        return value.textValue();
    }
    private static boolean agreed(JsonNode body) {
        var value = body.get("agreed");
        if (value == null || !value.isBoolean() || !value.booleanValue()) throw BizException.of(400, "请明确同意协议及账号操作说明");
        return true;
    }
    private static BizException invalid() { return BizException.of(400, "请求字段无效，请使用当前版本小程序"); }
}
