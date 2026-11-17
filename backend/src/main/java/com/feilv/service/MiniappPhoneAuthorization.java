package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;

/** Select phone credentials only from the authenticated miniapp source, never a request parameter. */
final class MiniappPhoneAuthorization {
    private MiniappPhoneAuthorization() {}

    static Snapshot resolve(long userId, WechatConfigService settings) {
        var current = UserContext.get();
        if (current != null && current.id() != userId) {
            throw BizException.of(403, "不能操作其他账户的手机号");
        }
        // Legacy direct Java service fixtures have no signed source. HTTP authentication rejects
        // such sessions before controllers run; this branch is not an HTTP compatibility mode.
        if (current == null || current.source() == null) {
            if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() != null
                    || current != null && current.appId() != null) throw invalidSession();
            return new Snapshot(null, null, settings.effectiveLogin());
        }
        if (!("USER".equals(current.source()) || "RIDER".equals(current.source()))
                || current.appId() == null || current.appId().isBlank()) {
            throw invalidSession();
        }
        var credentials = settings.effectiveLogin(current.source());
        if (!current.appId().equals(credentials.appId())) throw invalidSession();
        return new Snapshot(current.source(), current.appId(), credentials);
    }

    record Snapshot(String source, String signedAppId, WechatConfigService.LoginCredentials credentials) {
        void recheck(WechatConfigService settings) {
            var latest = source == null ? settings.effectiveLogin() : settings.effectiveLogin(source);
            if (!credentials.appId().equals(latest.appId()) || !credentials.secret().equals(latest.secret())
                    || signedAppId != null && !signedAppId.equals(latest.appId())) {
                throw BizException.of(409, "微信配置已变化，请重新授权手机号");
            }
        }
    }

    private static BizException invalidSession() {
        return BizException.of(401, "小程序登录配置已变化，请重新登录");
    }
}
