package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Set;

/** 仅本地开发联调免验证码；仍需当前登录账号、用户行锁和号码唯一约束。 */
@Service
public class DevelopmentPhoneBindingService {
    private final UserRepository users;
    private final TransactionTemplate transaction;
    private final Environment environment;
    private final boolean configured;

    public DevelopmentPhoneBindingService(UserRepository users, PlatformTransactionManager manager,
                                          Environment environment,
                                          @Value("${feilv.sms.binding-dev-bypass-enabled:false}") boolean configured) {
        this.users = users;
        this.transaction = new TransactionTemplate(manager);
        this.environment = environment;
        this.configured = configured;
    }

    public boolean enabled() {
        var profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        return configured && (profiles.contains("local") || profiles.contains("development"))
                && profiles.stream().noneMatch(Set.of("prod", "production", "docker")::contains);
    }

    public ProfileVO bind(long userId, String mobile) {
        if (!enabled()) throw BizException.of(403, "当前环境不允许免验证码绑定，请使用微信授权或短信验证");
        if (mobile == null || !mobile.matches("1[3-9][0-9]{9}")) throw BizException.of(400, "请输入有效的11位手机号");
        try {
            return transaction.execute(status -> {
                User user = users.findByIdForUpdate(userId).orElseThrow(() -> BizException.of(403, "账户不存在或不可用"));
                if (user.getStatus() != 1 || !Set.of("USER", "RIDER").contains(user.getRole())) {
                    throw BizException.of(403, "此账户不能绑定手机号");
                }
                if (UserViews.mobileBound(user) && !mobile.equals(user.getMobile())) {
                    throw BizException.of(409, "当前账户已绑定手机号，暂不支持直接更换");
                }
                if (users.findByMobile(mobile).filter(other -> !other.getId().equals(user.getId())).isPresent()) throw conflict();
                if (!mobile.equals(user.getMobile())) {
                    user.setMobile(mobile);
                    users.saveAndFlush(user);
                }
                return new ProfileVO(user.getId(), UserViews.text(user.getNickname()), user.getRole(),
                        UserViews.maskedMobile(user), true, UserViews.text(user.getAvatarUrl()));
            });
        } catch (DataIntegrityViolationException e) {
            throw conflict();
        }
    }

    private static BizException conflict() {
        return BizException.of(409, "该手机号已绑定其他账户，无法自动合并，请联系客服");
    }
}
