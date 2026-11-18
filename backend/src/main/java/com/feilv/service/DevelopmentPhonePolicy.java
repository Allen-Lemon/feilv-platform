package com.feilv.service;

import com.feilv.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;

/** Shared, default-deny policy for explicitly isolated manual-phone development flows. */
@Service
public class DevelopmentPhonePolicy {
    private final Environment environment;
    private final boolean configured;

    public DevelopmentPhonePolicy(Environment environment,
                                  @Value("${feilv.sms.binding-dev-bypass-enabled:false}") boolean configured) {
        this.environment = environment;
        this.configured = configured;
    }

    public enum BypassStatus { ENABLED, DISABLED, DEVELOPMENT_PROFILE_REQUIRED, PROTECTED_PROFILE }

    public BypassStatus status() {
        var profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if (profiles.stream().anyMatch(Set.of("prod", "production", "docker")::contains)) return BypassStatus.PROTECTED_PROFILE;
        if (!configured) return BypassStatus.DISABLED;
        if (!profiles.contains("local") && !profiles.contains("development")) return BypassStatus.DEVELOPMENT_PROFILE_REQUIRED;
        return BypassStatus.ENABLED;
    }

    public boolean enabled() { return status() == BypassStatus.ENABLED; }

    public String modeMessage() {
        return switch (status()) {
            case ENABLED -> "开发免验证码绑定已开启，仅限测试账号";
            case DISABLED -> "后端未开启开发免验证码绑定，请设置SMS_BINDING_DEV_BYPASS_ENABLED=true并重启后端";
            case DEVELOPMENT_PROFILE_REQUIRED -> "开关已开启，但后端未启用local或development开发profile，请在隔离开发环境设置SPRING_PROFILES_ACTIVE=local并重启";
            case PROTECTED_PROFILE -> "当前后端包含docker/prod/production受保护profile，不允许免验证码绑定；开发测试请使用独立的local/development后端";
        };
    }

    public void requireEnabled() {
        if (!enabled()) throw BizException.of(403, modeMessage());
    }
}
