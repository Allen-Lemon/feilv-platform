package com.feilv.service;

import com.feilv.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;

/** Public demo credentials must never be enabled merely by using the default profile. */
@Component
public class DemoAuthPolicy {
    private final boolean enabled;
    public DemoAuthPolicy(@Value("${auth.demo-sms-enabled:false}") boolean requested, Environment environment) {
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        enabled = requested && profiles.stream().anyMatch(Set.of("dev", "development", "local")::contains)
                && profiles.stream().noneMatch(Set.of("prod", "production", "docker")::contains);
    }
    public boolean enabled() { return enabled; }
    public void requireEnabled() {
        if (!enabled) throw BizException.of(403, "演示短信登录已关闭，请使用微信登录");
    }
}
