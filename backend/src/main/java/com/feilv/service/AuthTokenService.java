package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Sole application token issuer. The signing key is explicit and validated during startup. */
@Service
public class AuthTokenService {
    private final String secret;

    public AuthTokenService(@Value("${security.jwt-secret}") String secret) {
        JwtUtil.validateSecret(secret);
        this.secret = secret;
    }

    public String create(long id, String role, String audience, String appId) {
        return JwtUtil.create(id, role, audience, appId, secret);
    }

    public String createAdmin(long id) {
        return JwtUtil.createAdmin(id, secret);
    }

    public JwtUtil.Claims parse(String token) {
        return JwtUtil.parse(token, secret);
    }

    /** C-end logins never mint administrator sessions or sessions for frozen accounts. */
    public LoginVO login(User user, String client, String appId) {
        if (user == null || user.getId() == null || user.getStatus() != 1
                || !("USER".equals(user.getRole()) || "RIDER".equals(user.getRole()))) {
            throw BizException.of(403, "账户不可用，请联系客服");
        }
        return new LoginVO(create(user.getId(), user.getRole(), client, appId),
                user.getId(), user.getRole(), user.getNickname());
    }
}
