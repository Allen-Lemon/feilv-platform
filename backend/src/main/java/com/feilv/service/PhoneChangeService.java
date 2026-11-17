package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** 明确的已绑定账户换绑入口；只修改当前 t_user.mobile，不创建、转移或合并身份。 */
@Service
public class PhoneChangeService {
    private final UserRepository users;
    private final WechatIdentityRepository identities;
    private final WechatConfigService settings;
    private final WechatPhoneClient phoneClient;
    private final DevelopmentPhoneBindingService development;
    private final AuditService audit;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;

    public PhoneChangeService(UserRepository users, WechatIdentityRepository identities,
                              WechatConfigService settings, WechatPhoneClient phoneClient,
                              DevelopmentPhoneBindingService development, AuditService audit,
                              EntityManager entityManager, PlatformTransactionManager manager) {
        this.users = users;
        this.identities = identities;
        this.settings = settings;
        this.phoneClient = phoneClient;
        this.development = development;
        this.audit = audit;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(manager);
    }

    public ProfileVO changeWechatPhone(long userId, String code) {
        if (code == null || code.isBlank() || code.length() > 256) {
            throw BizException.of(400, "请重新点击按钮授权手机号");
        }
        // 必须在外部兑换前取值；旧响应不得覆盖兑换期间已经完成的另一笔换绑。
        String originalMobile = activeBound(users.findById(userId).orElse(null)).getMobile();
        var authorization = MiniappPhoneAuthorization.resolve(userId, settings);
        var credentials = authorization.credentials();
        requireIdentity(userId, credentials.appId());
        var phone = phoneClient.exchange(code, credentials);
        if (phone == null || !credentials.appId().equals(phone.appId())
                || phone.mobile() == null || !phone.mobile().matches("1[3-9][0-9]{9}")) {
            throw BizException.of(502, "微信手机号响应异常，请重新授权");
        }
        try {
            return transaction.execute(status -> {
                User user = lockedActiveBound(userId);
                requireSnapshot(user, originalMobile);
                authorization.recheck(settings);
                requireIdentity(userId, credentials.appId());
                requireAvailable(users, user, phone.mobile());
                return apply(users, audit, user, phone.mobile(), "WECHAT");
            });
        } catch (DataIntegrityViolationException e) {
            throw phoneConflict();
        }
    }

    public ProfileVO changeDevelopmentPhone(long userId, String mobile) {
        if (!development.enabled()) throw BizException.of(403, development.modeMessage());
        if (mobile == null || !mobile.matches("1[3-9][0-9]{9}")) {
            throw BizException.of(400, "请输入有效的11位手机号");
        }
        try {
            return transaction.execute(status -> {
                User user = lockedActiveBound(userId);
                requireAvailable(users, user, mobile);
                return apply(users, audit, user, mobile, "DEVELOPMENT");
            });
        } catch (DataIntegrityViolationException e) {
            throw phoneConflict();
        }
    }

    private User lockedActiveBound(long userId) {
        User user = users.findByIdForUpdate(userId).orElse(null);
        // 调用方事务/持久化上下文可能先读过用户；加锁查询不保证刷新一级缓存。
        if (user != null) entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return activeBound(user);
    }

    private void requireIdentity(long userId, String appId) {
        if (!identities.existsByUserIdAndAppId(userId, appId)) {
            throw BizException.of(403, "请先使用当前小程序的微信身份登录，再授权换绑手机号");
        }
    }

    static User activeBound(User user) {
        if (user == null) throw BizException.of(403, "账户不存在或不可用，请重新登录");
        if (user.getStatus() != 1) throw BizException.of(403, "账户已冻结，请联系客服");
        if (!"USER".equals(user.getRole()) && !"RIDER".equals(user.getRole())) {
            throw BizException.of(403, "此账户不能换绑手机号");
        }
        if (!UserViews.mobileBound(user)) throw BizException.of(409, "当前账户尚未绑定手机号，请先使用首次绑定接口");
        return user;
    }

    static void requireSnapshot(User user, String originalMobile) {
        if (!Objects.equals(originalMobile, user.getMobile())) {
            throw BizException.of(409, "当前手机号已变化，请重新验证新手机号");
        }
    }

    static void requireAvailable(UserRepository users, User user, String mobile) {
        if (users.findByMobile(mobile).filter(other -> !other.getId().equals(user.getId())).isPresent()) {
            throw phoneConflict();
        }
    }

    /** 调用者必须持有用户行锁且已验证凭证和号码唯一性；审计与修改同事务提交。 */
    static ProfileVO apply(UserRepository users, AuditService audit, User user, String mobile, String channel) {
        String oldMasked = UserViews.maskedMobile(user);
        if (!mobile.equals(user.getMobile())) {
            user.setMobile(mobile);
            users.saveAndFlush(user);
        }
        audit.record("PHONE_CHANGE", "userId=" + user.getId() + ",channel=" + channel
                + ",old=" + oldMasked + ",new=" + UserViews.maskedMobile(user));
        return new ProfileVO(user.getId(), UserViews.text(user.getNickname()), user.getRole(),
                UserViews.maskedMobile(user), true, UserViews.text(user.getAvatarUrl()));
    }

    static BizException phoneConflict() {
        return BizException.of(409, "该手机号已绑定其他账户，无法自动合并，请联系客服");
    }
}
