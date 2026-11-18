package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.Rider;
import com.feilv.domain.User;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.dto.MiniappEnrollmentDtos.EnrollmentReq;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Enroll the authenticated platform account, never an account selected by a supplied mobile. */
@Service
public class MiniappEnrollmentService {
    private final UserRepository users;
    private final RiderRepository riders;
    private final WechatIdentityRepository identities;
    private final WechatConfigService settings;
    private final WechatPhoneClient phones;
    private final AuthTokenService tokens;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final DevelopmentPhonePolicy development;

    public MiniappEnrollmentService(UserRepository users, RiderRepository riders,
                                    WechatIdentityRepository identities, WechatConfigService settings,
                                    WechatPhoneClient phones, AuthTokenService tokens,
                                    EntityManager entityManager, PlatformTransactionManager manager) {
        this(users, riders, identities, settings, phones, tokens, entityManager, manager,
                new DevelopmentPhonePolicy(new org.springframework.core.env.StandardEnvironment(), false));
    }

    @Autowired
    public MiniappEnrollmentService(UserRepository users, RiderRepository riders,
                                    WechatIdentityRepository identities, WechatConfigService settings,
                                    WechatPhoneClient phones, AuthTokenService tokens,
                                    EntityManager entityManager, PlatformTransactionManager manager,
                                    DevelopmentPhonePolicy development) {
        this.users = users;
        this.riders = riders;
        this.identities = identities;
        this.settings = settings;
        this.phones = phones;
        this.tokens = tokens;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(manager);
        this.development = development;
    }

    // Even if an internal caller has a write transaction, the WeChat exchange must not join it.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LoginVO enroll(EnrollmentReq request) {
        var current = UserContext.get();
        if (current == null) throw BizException.of(401, "请先登录骑手小程序");
        if (!"RIDER".equals(current.source()) || !("USER".equals(current.role()) || "RIDER".equals(current.role()))
                || current.appId() == null || current.appId().isBlank()) {
            throw BizException.of(403, "请从骑手小程序提交入驻");
        }
        if (request == null || !Boolean.TRUE.equals(request.agreed())) {
            throw BizException.of(400, "请先阅读并同意骑手入驻协议");
        }
        String name = text(request.name(), 32, true, "姓名");
        String plate = text(request.plateNo(), 16, false, "车牌");
        if ((request.phoneCode() != null) == (request.mobile() != null)) {
            throw BizException.of(400, "请提供单一手机号凭证");
        }
        boolean manual = request.mobile() != null;
        if (manual) {
            development.requireEnabled();
            if (!request.mobile().matches("1[3-9][0-9]{9}")) throw BizException.of(400, "请输入有效的11位手机号");
        } else if (request.phoneCode().isBlank() || request.phoneCode().length() > 256) {
            throw BizException.of(400, "请重新授权微信手机号");
        }
        var authorization = MiniappPhoneAuthorization.resolve(current.id(), settings);
        var credentials = authorization.credentials();
        var before = transaction.execute(status -> {
            User user = lockedUser(current.id());
            Rider rider = lockedRider(current.id());
            preflight(user, current.id(), current.appId(), authorization, manual, request.mobile());
            if (rider != null) return new Preparation(null, existing(user, rider, current.appId()));
            requireNewEnrollment(user);
            if (user.getMobile() == null || !user.getMobile().matches("1[3-9][0-9]{9}")) {
                throw BizException.of(409, "请先为当前账户绑定手机号");
            }
            return new Preparation(user.getMobile(), null);
        });
        // An ACTIVE retry returns before touching the one-use WeChat authorization code.
        if (before.login() != null) return before.login();
        String verifiedMobile;
        if (manual) {
            verifiedMobile = request.mobile();
        } else {
            var phone = phones.exchange(request.phoneCode(), credentials);
            if (phone == null || !credentials.appId().equals(phone.appId())
                    || phone.mobile() == null || !phone.mobile().matches("1[3-9][0-9]{9}")) {
                throw BizException.of(502, "微信手机号响应异常，请重新授权");
            }
            verifiedMobile = phone.mobile();
            if (!before.mobile().equals(verifiedMobile)) {
                throw BizException.of(409, "微信授权手机号与当前账户不一致，请先使用当前账户手机号授权");
            }
        }
        return transaction.execute(status -> {
            User user = lockedUser(current.id());
            Rider rider = lockedRider(current.id());
            preflight(user, current.id(), current.appId(), authorization, manual, request.mobile());
            if (rider != null) return existing(user, rider, current.appId());
            requireNewEnrollment(user);
            if (!Objects.equals(before.mobile(), user.getMobile()) || !verifiedMobile.equals(user.getMobile())) {
                throw BizException.of(409, "当前账户手机号已变化，请重新授权");
            }
            Rider created = new Rider();
            created.setUserId(user.getId());
            created.setName(name);
            created.setPlateNo(plate);
            created.setStatus("ACTIVE");
            created.setRideEnabled(false);
            created.setDrivingEnabled(false);
            riders.saveAndFlush(created);
            promote(user);
            return tokens.login(user, "RIDER", current.appId());
        });
    }

    /** Run under the user/rider locks, including ACTIVE retries and the final mutation. */
    private void preflight(User user, long userId, String appId, MiniappPhoneAuthorization.Snapshot authorization,
                           boolean manual, String mobile) {
        if (manual) development.requireEnabled();
        authorization.recheck(settings);
        requireIdentity(userId, appId);
        if (manual && !mobile.equals(user.getMobile())) {
            throw BizException.of(409, "开发手机号必须与当前账户已绑定手机号一致，请先完成手机号绑定或换绑");
        }
    }

    /** Shared lock ordering for both initial and final checks: user, then existing rider. */
    private User lockedUser(long userId) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> BizException.of(401, "账户不存在，请重新登录"));
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.getStatus() != 1 || !("USER".equals(user.getRole()) || "RIDER".equals(user.getRole()))) {
            throw BizException.of(403, "账户不可用，不能入驻骑手");
        }
        return user;
    }

    private Rider lockedRider(long userId) {
        var found = riders.findByUserIdForUpdate(userId);
        if (found.isEmpty()) return null;
        Rider rider = found.get();
        entityManager.refresh(rider, LockModeType.PESSIMISTIC_WRITE);
        return rider;
    }

    private LoginVO existing(User user, Rider rider, String appId) {
        if (!"ACTIVE".equals(rider.getStatus())) throw BizException.of(403, "骑手档案已冻结或不可用，请联系客服");
        promote(user);
        // Do not overwrite name, plate, credit, online state, or granted capabilities on retry.
        return tokens.login(user, "RIDER", appId);
    }

    private void requireNewEnrollment(User user) {
        if (!"USER".equals(user.getRole())) {
            // Historical anomalies require controlled recovery, not a public archive-repair path.
            throw BizException.of(409, "骑手档案缺失，请联系平台核实，不能重复创建");
        }
    }

    private void promote(User user) {
        if ("USER".equals(user.getRole())) {
            user.setRole("RIDER");
            users.saveAndFlush(user);
        }
    }

    private void requireIdentity(long userId, String appId) {
        if (!identities.existsByUserIdAndAppId(userId, appId)) {
            throw BizException.of(403, "请先使用骑手小程序的微信身份登录");
        }
    }

    private static String text(String value, int max, boolean required, String label) {
        String result = value == null ? "" : value.strip();
        if ((required && result.isBlank()) || result.codePointCount(0, result.length()) > max
                || result.codePoints().anyMatch(Character::isISOControl)) {
            throw BizException.of(400, label + "格式不正确");
        }
        return result;
    }

    private record Preparation(String mobile, LoginVO login) {}
}
