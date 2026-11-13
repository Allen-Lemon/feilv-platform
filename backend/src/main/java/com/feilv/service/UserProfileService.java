package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserProfileService {
    private final UserRepository users;
    private final WechatIdentityRepository identities;
    private final WechatConfigService settings;
    private final WechatPhoneClient phoneClient;
    private final TransactionTemplate transaction;

    public UserProfileService(UserRepository users, WechatIdentityRepository identities,
                              WechatConfigService settings, WechatPhoneClient phoneClient,
                              PlatformTransactionManager manager) {
        this.users = users;
        this.identities = identities;
        this.settings = settings;
        this.phoneClient = phoneClient;
        this.transaction = new TransactionTemplate(manager);
    }

    public ProfileVO me(long userId) {
        return view(active(users.findById(userId).orElse(null)));
    }

    public ProfileVO updateNickname(long userId, String value) {
        String nickname = value == null ? "" : value.strip();
        if (nickname.isBlank() || nickname.codePointCount(0, nickname.length()) > 32
                || nickname.codePoints().anyMatch(Character::isISOControl)) {
            throw BizException.of(400, "昵称须为1至32个字符，且不能包含控制字符");
        }
        return transaction.execute(status -> {
            User user = active(users.findByIdForUpdate(userId).orElse(null));
            user.setNickname(nickname);
            return view(users.saveAndFlush(user));
        });
    }

    public ProfileVO bindWechatPhone(long userId, String code) {
        if (code == null || code.isBlank() || code.length() > 256) {
            throw BizException.of(400, "请重新点击按钮授权手机号");
        }
        active(users.findById(userId).orElse(null));
        var credentials = settings.effectiveLogin();
        requireIdentity(userId, credentials.appId());
        // 微信调用在写事务外进行；手机号仅接受微信服务端兑换结果。
        var phone = phoneClient.exchange(code, credentials);
        if (phone == null || !credentials.appId().equals(phone.appId())
                || phone.mobile() == null || !phone.mobile().matches("^1[3-9]\\d{9}$")) {
            throw BizException.of(502, "微信手机号响应异常，请重新授权");
        }
        try {
            return transaction.execute(status -> {
                User user = active(users.findByIdForUpdate(userId).orElse(null));
                var latest = settings.effectiveLogin();
                if (!credentials.appId().equals(latest.appId()) || !credentials.secret().equals(latest.secret())) {
                    throw BizException.of(409, "微信配置已变化，请重新授权手机号");
                }
                requireIdentity(userId, credentials.appId());
                if (phone.mobile().equals(user.getMobile())) return view(user);
                if (user.getMobile() != null && !user.getMobile().isBlank()) {
                    throw BizException.of(409, "当前账户已绑定手机号，暂不支持直接更换");
                }
                if (users.findByMobile(phone.mobile()).isPresent()) throw phoneConflict();
                user.setMobile(phone.mobile());
                return view(users.saveAndFlush(user));
            });
        } catch (DataIntegrityViolationException e) {
            // 回滚完成后转换唯一约束竞争；不回显数据库异常或手机号。
            throw phoneConflict();
        }
    }

    private void requireIdentity(long userId, String appId) {
        if (!identities.existsByUserIdAndAppId(userId, appId)) {
            throw BizException.of(403, "请先使用当前小程序的微信身份登录，再授权绑定手机号");
        }
    }

    private User active(User user) {
        if (user == null) throw BizException.of(401, "账户不存在，请重新登录");
        if (user.getStatus() != 1) throw BizException.of(403, "账户已冻结，请联系客服");
        if (!"USER".equals(user.getRole()) && !"RIDER".equals(user.getRole())) {
            throw BizException.of(403, "此账户不能使用小程序资料接口");
        }
        return user;
    }

    private static BizException phoneConflict() {
        return BizException.of(409, "该手机号已绑定其他账户，无法自动合并，请联系客服");
    }

    private ProfileVO view(User user) {
        String mobile = user.getMobile();
        boolean bound = mobile != null && !mobile.isBlank();
        String masked = !bound ? "" : mobile.matches("^1[3-9]\\d{9}$")
                ? mobile.substring(0, 3) + "****" + mobile.substring(7) : "已绑定";
        return new ProfileVO(user.getId(), user.getNickname() == null ? "" : user.getNickname(),
                user.getRole(), masked, bound);
    }
}
