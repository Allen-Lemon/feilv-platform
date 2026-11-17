package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit, bidirectional identity linking. Phone and unionid are never account selectors. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MiniappIdentityLinkService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TTL_SECONDS = 180;
    private final WechatConfigService settings;
    private final WechatSessionClient sessions;
    private final WechatPhoneClient phones;
    private final WechatIdentityRepository identities;
    private final UserRepository users;
    private final RiderRepository riders;
    private final MiniappLoginTicketRepository tickets;
    private final MiniappLinkCodeRepository links;
    private final AuthTokenService tokens;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final EntityManager entityManager;
    // Bounded local abuse guard; credentials themselves remain database-backed and single use.
    private final Map<String, Window> limits = new LinkedHashMap<>();
    private long nextCleanup;
    private record Window(long minute, int count) {}

    public record LoginState(String state, LoginVO login, String loginTicket, String expiresAt) {}
    public record LinkCode(String linkCode, String expiresAt, String targetClient) {}

    public MiniappIdentityLinkService(WechatConfigService settings, WechatSessionClient sessions,
            WechatPhoneClient phones, WechatIdentityRepository identities, UserRepository users,
            RiderRepository riders, MiniappLoginTicketRepository tickets, MiniappLinkCodeRepository links,
            AuthTokenService tokens, AuditService audit, PlatformTransactionManager manager, EntityManager entityManager) {
        this.settings = settings; this.sessions = sessions; this.phones = phones;
        this.identities = identities; this.users = users; this.riders = riders;
        this.tickets = tickets; this.links = links; this.tokens = tokens; this.audit = audit;
        this.transaction = new TransactionTemplate(manager);
        this.entityManager = entityManager;
    }

    public static String client(String value) {
        if (!"USER".equals(value) && !"RIDER".equals(value)) throw BizException.of(400, "未知小程序类型");
        return value;
    }

    /** Controller also limits requests by actual remote address (not untrusted forwarding headers). */
    public void throttleRequest(String address) { limit("ip:" + digest(address == null ? "" : address), 60); }

    public LoginState login(String client, String code) {
        client(client); requireCode(code);
        limit("login-global", 600);
        var credentials = settings.effectiveLogin(client);
        var session = sessions.exchange(code, credentials); // outside any write transaction
        requireSession(session, credentials);
        limit("identity:" + digest(session.appId() + "\0" + session.openid()), 10);
        cleanup();
        return transaction.execute(status -> {
            recheck(client, credentials);
            var identity = identities.findByAppIdAndOpenid(session.appId(), session.openid()).orElse(null);
            if (identity != null) {
                User user = lockedAccount(identity.getUserId());
                return new LoginState("AUTHENTICATED", tokens.login(user, client, credentials.appId()), null, null);
            }
            String raw = randomToken();
            MiniappLoginTicket ticket = new MiniappLoginTicket();
            ticket.setTokenHash(digest(raw)); ticket.setClient(client); ticket.setAppId(session.appId());
            ticket.setOpenid(session.openid()); ticket.setUnionid(session.unionid());
            ticket.setConfigFingerprint(fingerprint(credentials)); ticket.setExpiresAt(Instant.now().plusSeconds(TTL_SECONDS));
            tickets.saveAndFlush(ticket);
            return new LoginState("IDENTITY_REQUIRED", null, raw, ticket.getExpiresAt().toString());
        });
    }

    public LinkCode issueLinkCode(String code, boolean agreed) {
        requireAgreement(agreed); requireCode(code);
        var current = UserContext.get();
        if (current == null || current.source() == null || current.appId() == null) throw invalidSession();
        String source = client(current.source());
        String target = "USER".equals(source) ? "RIDER" : "USER";
        limit("issue:" + current.id(), 5);
        var sourceConfig = settings.effectiveLogin(source);
        var targetConfig = settings.effectiveLogin(target);
        if (!current.appId().equals(sourceConfig.appId())) throw invalidSession();
        if (sourceConfig.appId().equals(targetConfig.appId())) throw BizException.of(503, "两个小程序必须使用不同AppID");
        var session = sessions.exchange(code, sourceConfig);
        requireSession(session, sourceConfig);
        cleanup();
        return transaction.execute(status -> {
            recheck(source, sourceConfig); recheck(target, targetConfig);
            User user = lockedAccount(current.id());
            if (!current.role().equals(user.getRole())) throw invalidSession();
            var identity = identities.findByAppIdAndOpenid(session.appId(), session.openid()).orElse(null);
            if (identity == null || !identity.getUserId().equals(user.getId())) {
                throw BizException.of(403, "新的微信登录凭证不属于当前账户，无法生成关联码");
            }
            String raw = randomToken();
            MiniappLinkCode link = new MiniappLinkCode();
            link.setTokenHash(digest(raw)); link.setUserId(user.getId());
            link.setSourceClient(source); link.setTargetClient(target);
            link.setSourceAppId(sourceConfig.appId()); link.setTargetAppId(targetConfig.appId());
            link.setSourceFingerprint(fingerprint(sourceConfig)); link.setTargetFingerprint(fingerprint(targetConfig));
            link.setExpiresAt(Instant.now().plusSeconds(TTL_SECONDS));
            links.saveAndFlush(link);
            audit.record("MINIAPP_LINK_CODE", "userId=" + user.getId() + ",source=" + source + ",target=" + target);
            return new LinkCode(raw, link.getExpiresAt().toString(), target);
        });
    }

    public LoginVO link(String client, String rawTicket, String rawLink, boolean agreed) {
        client(client); requireAgreement(agreed); requireToken(rawTicket); requireToken(rawLink);
        limit("ticket:" + digest(rawTicket), 5);
        limit("verify-link:" + digest(rawLink), 10);
        cleanup();
        try {
            LoginVO login = transaction.execute(status -> {
                // Fixed lock ordering: ticket -> link code -> user -> rider.
                MiniappLoginTicket ticket = lockedTicket(client, rawTicket);
                MiniappLinkCode link = links.findForUpdate(digest(rawLink)).orElse(null);
                if (link != null) entityManager.refresh(link, LockModeType.PESSIMISTIC_WRITE);
                if (link == null || link.isConsumed() || !"LINK_IDENTITY".equals(link.getPurpose())
                        || !Instant.now().isBefore(link.getExpiresAt()) || !client.equals(link.getTargetClient())
                        || !ticket.getAppId().equals(link.getTargetAppId())) {
                    ticket.setFailures(ticket.getFailures() + 1);
                    if (ticket.getFailures() >= 5) ticket.setConsumed(true);
                    tickets.saveAndFlush(ticket);
                    return null; // commit failure counter without committing any identity or consumption of link
                }
                var source = settings.effectiveLogin(link.getSourceClient());
                var target = settings.effectiveLogin(client);
                if (!link.getSourceAppId().equals(source.appId()) || !link.getTargetAppId().equals(target.appId())
                        || !link.getSourceFingerprint().equals(fingerprint(source))
                        || !link.getTargetFingerprint().equals(fingerprint(target))
                        || source.appId().equals(target.appId())
                        || link.getSourceClient().equals(client)) throw configChanged();
                User user = lockedAccount(link.getUserId());
                // Do not move or silently replace any already mapped identity, even on the same account.
                requireUnclaimed(ticket);
                appendIdentity(ticket, user.getId());
                ticket.setConsumed(true); link.setConsumed(true);
                tickets.saveAndFlush(ticket); links.saveAndFlush(link);
                audit.record("MINIAPP_IDENTITY_LINK", "userId=" + user.getId() + ",source=" + link.getSourceClient() + ",target=" + client);
                return tokens.login(user, client, ticket.getAppId());
            });
            if (login == null) throw BizException.of(400, "关联码无效、已使用或已过期，请重新获取");
            return login;
        } catch (DataIntegrityViolationException conflict) {
            // The failed transaction has rolled back before converting database uniqueness failures.
            throw identityConflict();
        }
    }

    public LoginVO createAccount(String client, String rawTicket, String phoneCode, boolean agreed) {
        client(client); requireAgreement(agreed); requireToken(rawTicket); requireCode(phoneCode);
        limit("ticket:" + digest(rawTicket), 5);
        MiniappLoginTicket snapshot = tickets.findById(digest(rawTicket)).orElseThrow(MiniappIdentityLinkService::invalidTicket);
        validateTicket(client, snapshot);
        var credentials = settings.effectiveLogin(client);
        if (!snapshot.getConfigFingerprint().equals(fingerprint(credentials))) throw configChanged();
        var phone = phones.exchange(phoneCode, credentials); // never keep a write transaction during WeChat HTTP
        if (phone == null || !credentials.appId().equals(phone.appId())
                || phone.mobile() == null || !phone.mobile().matches("1[3-9][0-9]{9}")) {
            throw BizException.of(502, "微信手机号响应异常，请重新授权");
        }
        cleanup();
        try {
            return transaction.execute(status -> {
                MiniappLoginTicket ticket = lockedTicket(client, rawTicket);
                recheck(client, credentials);
                requireUnclaimed(ticket);
                if (users.findByMobile(phone.mobile()).isPresent()) throw phoneConflict();
                User user = new User(); user.setMobile(phone.mobile()); user.setNickname("微信用户");
                user.setRole("USER"); user.setStatus(1);
                user = users.saveAndFlush(user);
                appendIdentity(ticket, user.getId());
                ticket.setConsumed(true); tickets.saveAndFlush(ticket);
                audit.record("MINIAPP_ACCOUNT_CREATE", "userId=" + user.getId() + ",client=" + client);
                return tokens.login(user, client, ticket.getAppId());
            });
        } catch (DataIntegrityViolationException conflict) {
            throw BizException.of(409, "手机号或微信身份已被使用，请重新登录或回原账号生成关联码");
        }
    }

    private MiniappLoginTicket lockedTicket(String client, String raw) {
        MiniappLoginTicket ticket = tickets.findForUpdate(digest(raw)).orElseThrow(MiniappIdentityLinkService::invalidTicket);
        entityManager.refresh(ticket, LockModeType.PESSIMISTIC_WRITE);
        validateTicket(client, ticket);
        return ticket;
    }
    private void validateTicket(String client, MiniappLoginTicket ticket) {
        if (ticket.isConsumed() || ticket.getFailures() >= 5 || !"IDENTITY_LOGIN".equals(ticket.getPurpose())
                || !client.equals(ticket.getClient()) || !Instant.now().isBefore(ticket.getExpiresAt())) throw invalidTicket();
        var config = settings.effectiveLogin(client);
        if (!ticket.getAppId().equals(config.appId()) || !ticket.getConfigFingerprint().equals(fingerprint(config))) throw configChanged();
    }
    private User lockedAccount(long userId) {
        User user = users.findByIdForUpdate(userId).orElseThrow(MiniappIdentityLinkService::invalidSession);
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.getStatus() != 1 || !("USER".equals(user.getRole()) || "RIDER".equals(user.getRole()))) {
            throw BizException.of(403, "账户不可用，无法进行小程序关联");
        }
        var rider = riders.findByUserIdForUpdate(userId).orElse(null);
        if (rider != null) entityManager.refresh(rider, LockModeType.PESSIMISTIC_WRITE);
        if ("RIDER".equals(user.getRole()) && rider == null) throw BizException.of(409, "骑手档案异常，请联系客服");
        if (rider != null && !"ACTIVE".equals(rider.getStatus())) throw BizException.of(403, "骑手账户已冻结，请联系客服");
        return user;
    }
    private void requireUnclaimed(MiniappLoginTicket ticket) {
        if (identities.findByAppIdAndOpenid(ticket.getAppId(), ticket.getOpenid()).isPresent()) throw identityConflict();
    }
    private void appendIdentity(MiniappLoginTicket ticket, long userId) {
        WechatIdentity identity = new WechatIdentity(); identity.setUserId(userId); identity.setAppId(ticket.getAppId());
        identity.setOpenid(ticket.getOpenid()); identity.setUnionid(ticket.getUnionid()); identities.saveAndFlush(identity);
    }
    private void recheck(String client, WechatConfigService.LoginCredentials snapshot) {
        var latest = settings.effectiveLogin(client);
        if (!fingerprint(snapshot).equals(fingerprint(latest))) throw configChanged();
    }
    private static void requireSession(WechatSessionClient.Session session, WechatConfigService.LoginCredentials config) {
        if (session == null || !config.appId().equals(session.appId()) || session.openid() == null
                || session.openid().isBlank() || session.openid().length() > 128
                || session.unionid() != null && session.unionid().length() > 128) {
            throw BizException.of(502, "微信登录响应异常");
        }
    }
    private static void requireAgreement(boolean agreed) {
        if (!agreed) throw BizException.of(400, "请明确同意用户协议、隐私政策及账号操作说明");
    }
    private static void requireCode(String code) {
        if (code == null || code.isBlank() || code.length() > 256) throw BizException.of(400, "请重新获取微信授权凭证");
    }
    private static void requireToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) throw invalidTicket();
    }
    private synchronized void limit(String key, int maximum) {
        long minute = System.currentTimeMillis() / 60_000;
        limits.entrySet().removeIf(e -> e.getValue().minute() != minute);
        Window previous = limits.get(key);
        if (previous == null && limits.size() >= 4096) throw BizException.of(429, "请求过多，请稍后再试");
        int count = previous == null ? 1 : previous.count() + 1;
        if (count > maximum) throw BizException.of(429, "操作频繁，请稍后再试");
        limits.put(key, new Window(minute, count));
    }
    private void cleanup() {
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (now < nextCleanup) return;
            nextCleanup = now + 60_000;
        }
        // Never scan/delete an unbounded history, and retain recent consumed rows for replay handling.
        Instant cutoff = Instant.now().minusSeconds(86_400);
        transaction.executeWithoutResult(status -> {
            tickets.deleteAll(tickets.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(cutoff));
            links.deleteAll(links.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(cutoff));
        });
    }
    private static String randomToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static String fingerprint(WechatConfigService.LoginCredentials config) { return digest(config.appId() + "\0" + config.secret()); }
    private static BizException invalidTicket() { return BizException.of(401, "登录票据无效、已使用或已过期，请重新微信登录"); }
    private static BizException invalidSession() { return BizException.of(401, "登录状态已变化，请重新登录"); }
    private static BizException configChanged() { return BizException.of(409, "微信配置已变化，请重新微信登录并授权"); }
    private static BizException identityConflict() { return BizException.of(409, "微信身份已有关联，请重新登录；不能移动已有身份"); }
    private static BizException phoneConflict() { return BizException.of(409, "该手机号已有关联账号，请回原账号生成关联码，不会自动合并"); }
}
