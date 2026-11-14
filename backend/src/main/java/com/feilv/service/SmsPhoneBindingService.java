package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.dto.ManualPhoneBindingDtos.SmsCodeVO;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 独立于登录短信的一次性绑号凭证；当前仅允许显式开启的本地 mock，不代表接入真实短信。 */
@Service
public class SmsPhoneBindingService {
    private static final int COOLDOWN_SECONDS = 60;
    private static final int TTL_SECONDS = 300;
    private static final int DAILY_LIMIT = 10;
    private static final int MAX_ERRORS = 5;
    private enum Purpose { PHONE_BINDING }

    private final UserRepository users;
    private final MessageService messages;
    private final TransactionTemplate transaction;
    private final boolean mockEnabled;
    private final Clock clock;
    private final SecureRandom random;
    // 所有凭证、错误次数及两维度限流均在同一锁内访问，一次校验只允许一个线程消费。
    private final Object stateLock = new Object();
    private final Map<CredentialKey, Credential> credentials = new HashMap<>();
    private final Map<Long, SendCounter> userCounters = new HashMap<>();
    private final Map<String, SendCounter> mobileCounters = new HashMap<>();

    private record CredentialKey(long userId, String mobile, Purpose purpose) {
        @Override public String toString() { return "CredentialKey[REDACTED]"; }
    }
    private record Credential(String code, Instant expiresAt, int errors) {
        @Override public String toString() { return "Credential[REDACTED]"; }
    }
    private record SendCounter(LocalDate day, int count, Instant lastSent) {}

    @Autowired
    public SmsPhoneBindingService(UserRepository users, MessageService messages, PlatformTransactionManager manager,
                                  @Value("${feilv.sms.binding-mock-enabled:false}") boolean mockEnabled) {
        this(users, messages, manager, mockEnabled, Clock.system(ZoneId.of("Asia/Shanghai")), new SecureRandom());
    }

    SmsPhoneBindingService(UserRepository users, MessageService messages, PlatformTransactionManager manager,
                           boolean mockEnabled, Clock clock, SecureRandom random) {
        this.users = users;
        this.messages = messages;
        this.transaction = new TransactionTemplate(manager);
        this.mockEnabled = mockEnabled;
        this.clock = clock;
        this.random = random;
    }

    public SmsCodeVO sendCode(long userId, String mobile) {
        validateMobile(mobile);
        return transaction.execute(status -> {
            User user = active(users.findByIdForUpdate(userId).orElse(null));
            requireAvailableMobile(user, mobile);
            requireSmsConfigured();
            synchronized (stateLock) {
                Instant now = clock.instant();
                LocalDate today = LocalDate.now(clock);
                prune(now, today);
                checkLimit(userCounters.get(userId), now, today);
                checkLimit(mobileCounters.get(mobile), now, today);
                String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
                // MessageService 目前只写日志。即使在 mock 下也不让它记录原始手机号/验证码。
                messages.sendSms(mobile.substring(0, 3) + "****" + mobile.substring(7),
                        "【飞毛腿】手机号绑定短信模拟，验证码仅在本地联调响应中返回，有效期5分钟（不写日志）。");
                credentials.put(key(userId, mobile), new Credential(code, now.plusSeconds(TTL_SECONDS), 0));
                userCounters.put(userId, increment(userCounters.get(userId), today, now));
                mobileCounters.put(mobile, increment(mobileCounters.get(mobile), today, now));
                return new SmsCodeVO(COOLDOWN_SECONDS, true, code);
            }
        });
    }

    public ProfileVO bindPhone(long userId, String mobile, String code) {
        validateMobile(mobile);
        if (code == null || !code.matches("[0-9]{6}")) throw BizException.of(400, "请输入6位短信验证码");
        try {
            return transaction.execute(status -> {
                // 与微信绑号使用同一行锁，检查和写入不可被微信/其他手动绑定覆盖。
                User user = active(users.findByIdForUpdate(userId).orElse(null));
                requireAvailableMobile(user, mobile);
                requireSmsConfigured();
                consumeCode(userId, mobile, code);
                if (mobile.equals(user.getMobile())) return view(user);
                user.setMobile(mobile);
                return view(users.saveAndFlush(user));
            });
        } catch (DataIntegrityViolationException e) {
            // TransactionTemplate 已回滚；不泄漏数据库异常，也不恢复已消费凭证或自动合并账号。
            throw phoneConflict();
        }
    }

    private void consumeCode(long userId, String mobile, String code) {
        synchronized (stateLock) {
            Instant now = clock.instant();
            prune(now, LocalDate.now(clock));
            CredentialKey key = key(userId, mobile);
            Credential credential = credentials.get(key);
            if (credential == null) throw BizException.of(4010, "绑定验证码已过期或不存在，请重新获取");
            if (!MessageDigest.isEqual(credential.code().getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                int errors = credential.errors() + 1;
                if (errors >= MAX_ERRORS) credentials.remove(key);
                else credentials.put(key, new Credential(credential.code(), credential.expiresAt(), errors));
                throw BizException.of(4011, errors >= MAX_ERRORS ? "绑定验证码错误次数过多，请重新获取" : "绑定验证码错误");
            }
            credentials.remove(key);
        }
    }

    private void requireAvailableMobile(User user, String mobile) {
        if (UserViews.mobileBound(user) && !mobile.equals(user.getMobile())) {
            throw BizException.of(409, "当前账户已绑定手机号，暂不支持直接更换");
        }
        if (users.findByMobile(mobile).filter(owner -> !owner.getId().equals(user.getId())).isPresent()) {
            throw phoneConflict();
        }
    }

    private User active(User user) {
        if (user == null) throw BizException.of(403, "账户不存在或不可用，请重新登录");
        if (user.getStatus() != 1) throw BizException.of(403, "账户已冻结，请联系客服");
        if (!"USER".equals(user.getRole()) && !"RIDER".equals(user.getRole())) {
            throw BizException.of(403, "此账户不能绑定手机号");
        }
        return user;
    }

    private void requireSmsConfigured() {
        if (!mockEnabled) throw BizException.of(503, "短信服务尚未配置，请使用微信授权绑定手机号");
    }

    private static void validateMobile(String mobile) {
        if (mobile == null || !mobile.matches("1[3-9][0-9]{9}")) throw BizException.of(400, "请输入有效的11位手机号");
    }

    private static CredentialKey key(long userId, String mobile) {
        return new CredentialKey(userId, mobile, Purpose.PHONE_BINDING);
    }

    private static void checkLimit(SendCounter counter, Instant now, LocalDate today) {
        if (counter == null) return;
        if (counter.day().equals(today) && counter.count() >= DAILY_LIMIT) {
            throw BizException.of(4292, "今日绑定验证码发送次数已达上限");
        }
        if (now.isBefore(counter.lastSent().plusSeconds(COOLDOWN_SECONDS))) {
            throw BizException.of(4291, "操作频繁，请60秒后再试");
        }
    }

    private static SendCounter increment(SendCounter previous, LocalDate today, Instant now) {
        return new SendCounter(today, previous != null && previous.day().equals(today) ? previous.count() + 1 : 1, now);
    }

    private void prune(Instant now, LocalDate today) {
        credentials.values().removeIf(value -> !now.isBefore(value.expiresAt()));
        userCounters.values().removeIf(value -> oldCounter(value, now, today));
        mobileCounters.values().removeIf(value -> oldCounter(value, now, today));
    }

    private static boolean oldCounter(SendCounter value, Instant now, LocalDate today) {
        return !value.day().equals(today) && !now.isBefore(value.lastSent().plusSeconds(COOLDOWN_SECONDS));
    }

    private static BizException phoneConflict() {
        return BizException.of(409, "该手机号已绑定其他账户，无法自动合并，请联系客服");
    }

    private static ProfileVO view(User user) {
        return new ProfileVO(user.getId(), UserViews.text(user.getNickname()), user.getRole(),
                UserViews.maskedMobile(user), UserViews.mobileBound(user), UserViews.text(user.getAvatarUrl()));
    }
}
