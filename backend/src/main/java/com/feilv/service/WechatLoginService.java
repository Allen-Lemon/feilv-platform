package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.domain.WechatIdentity;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class WechatLoginService {
    private final WechatSessionClient client;
    private final WechatIdentityRepository identities;
    private final UserRepository users;
    private final TransactionTemplate transaction;
    private final AuthTokenService tokens;

    public WechatLoginService(WechatSessionClient client, WechatIdentityRepository identities,
                               UserRepository users, PlatformTransactionManager manager, AuthTokenService tokens) {
        this.client = client;
        this.identities = identities;
        this.users = users;
        this.transaction = new TransactionTemplate(manager);
        this.tokens = tokens;
    }

    public LoginVO login(String code, boolean agreed) {
        if (!agreed) throw BizException.of(400, "请先同意用户协议和隐私政策");
        WechatSessionClient.Session session = client.exchange(code);
        // 外部调用不占用数据库事务。唯一约束处理两个首次登录请求并发的情况。
        try {
            return transaction.execute(status -> loginInTransaction(session));
        } catch (DataIntegrityViolationException e) {
            return transaction.execute(status -> {
                WechatIdentity identity = identities.findByAppIdAndOpenid(session.appId(), session.openid())
                        .orElseThrow(() -> BizException.of(409, "微信登录冲突，请重试"));
                return tokenFor(identity.getUserId(), session.appId());
            });
        }
    }

    private LoginVO loginInTransaction(WechatSessionClient.Session session) {
        WechatIdentity identity = identities.findByAppIdAndOpenid(session.appId(), session.openid()).orElse(null);
        if (identity == null) {
            User user = new User();
            user.setNickname("微信用户");
            user.setRole("USER");
            user.setStatus(1);
            user = users.saveAndFlush(user);
            identity = new WechatIdentity();
            identity.setAppId(session.appId());
            identity.setOpenid(session.openid());
            identity.setUnionid(session.unionid());
            identity.setUserId(user.getId());
            identities.saveAndFlush(identity);
        } else if (session.unionid() != null) {
            identity.setUnionid(session.unionid());
            identities.save(identity);
        }
        return tokenFor(identity.getUserId(), session.appId());
    }

    private LoginVO tokenFor(long userId, String appId) {
        User user = users.findById(userId).orElseThrow(() -> BizException.of(401, "微信关联账号不存在"));
        if (user.getStatus() != 1) throw BizException.of(403, "账户已冻结，请联系客服");
        // 微信登录仅面向C端，不能作为管理员登录的替代通道。
        if (!"USER".equals(user.getRole()) && !"RIDER".equals(user.getRole())) {
            throw BizException.of(403, "此账号不能使用小程序登录");
        }
        return tokens.login(user, "USER", appId);
    }
}
