package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.WechatConfig;
import com.feilv.dto.WechatConfigDtos.*;
import com.feilv.repository.WechatConfigRepository;
import com.feilv.util.ConfigSecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class WechatConfigService {
    private final WechatConfigRepository repository;
    private final ConfigSecretCipher cipher;
    private final AuditService audit;
    private final String envAppId;
    private final String envSecret;
    public WechatConfigService(WechatConfigRepository repository, ConfigSecretCipher cipher, AuditService audit,
                               @Value("${feilv.wechat.app-id:}") String envAppId,
                               @Value("${feilv.wechat.app-secret:}") String envSecret) {
        this.repository = repository; this.cipher = cipher; this.audit = audit;
        this.envAppId = text(envAppId); this.envSecret = text(envSecret);
    }

    /** 内部机密对象不序列化到接口，也不允许日志打印明文。 */
    public static final class LoginCredentials {
        private final String appId;
        private final String secret;
        public LoginCredentials(String appId, String secret) { this.appId = appId; this.secret = secret; }
        public String appId() { return appId; }
        public String secret() { return secret; }
        @Override public String toString() { return "LoginCredentials[redacted]"; }
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        return new Overview(cipher.ready(), loginView(repository.findById("LOGIN").orElse(null)),
                paymentView(repository.findById("PAYMENT").orElse(null)), "MOCK");
    }
    private LoginView loginView(WechatConfig c) {
        if (c == null) return new LoginView(!envAppId.isBlank() && !envSecret.isBlank(), envAppId, !envSecret.isBlank(), "ENVIRONMENT", null, null);
        return new LoginView(c.isEnabled(), text(c.getAppId()), has(c.getAppSecretEncrypted()), "DATABASE", c.getVersion(), c.getUpdatedTime());
    }
    private PaymentView paymentView(WechatConfig c) {
        if (c == null) return new PaymentView(false, "", "", "", "", "", false, false, false, "", "MOCK", null, null);
        return new PaymentView(c.isEnabled(), text(c.getAppId()), text(c.getMchId()), text(c.getMerchantSerialNo()), text(c.getNotifyUrl()),
                text(c.getRefundNotifyUrl()), has(c.getApiV3KeyEncrypted()), has(c.getMerchantPrivateKeyEncrypted()), has(c.getPlatformPublicKeyEncrypted()),
                text(c.getPlatformPublicKeyId()), "MOCK", c.getVersion(), c.getUpdatedTime());
    }

    @Transactional
    public LoginView saveLogin(LoginRequest req) {
        cipher.requireReady();
        WechatConfig c = editable("LOGIN", req.version());
        String incomingSecret = text(req.appSecret());
        if (c.getVersion() == null && incomingSecret.isEmpty() && !req.clearAppSecret()
                && Objects.equals(envAppId, text(req.appId())) && !envSecret.isBlank()) {
            // 首次从环境变量接管，留空语义依然是保留当前生效密钥；迁入后只读数据库。
            incomingSecret = envSecret;
        }
        if (!incomingSecret.isEmpty() && !incomingSecret.matches("[0-9a-fA-F]{32}")) {
            throw BizException.of(400, "小程序AppSecret必须为32位十六进制字符");
        }
        boolean changedApp = !Objects.equals(text(c.getAppId()), text(req.appId()));
        c.setEnabled(Boolean.TRUE.equals(req.enabled())); c.setAppId(text(req.appId()));
        if (changedApp && incomingSecret.isEmpty() && has(c.getAppSecretEncrypted()) && !req.clearAppSecret()) {
            throw BizException.of(400, "修改AppID时必须同时提供新AppSecret或明确清除旧密钥");
        }
        c.setAppSecretEncrypted(updateSecret(c.getAppSecretEncrypted(), incomingSecret, req.clearAppSecret(), "LOGIN.appSecret"));
        rejectIfInvalid(loginProblems(c));
        if (!has(c.getAppSecretEncrypted()) && c.isEnabled()) throw BizException.of(400, "启用微信登录必须填写AppSecret");
        stamp(c); repository.saveAndFlush(c);
        audit.record("WECHAT_LOGIN_CONFIG_SAVE", "section=LOGIN, enabled=" + c.isEnabled() + ", version=" + c.getVersion());
        return loginView(c);
    }

    @Transactional
    public PaymentView savePayment(PaymentRequest req) {
        cipher.requireReady();
        WechatConfig c = editable("PAYMENT", req.version());
        boolean merchantChanged = has(c.getMchId()) && !Objects.equals(c.getMchId(), text(req.mchId()));
        boolean certificateChanged = has(c.getMerchantSerialNo()) && !Objects.equals(c.getMerchantSerialNo(), text(req.merchantSerialNo()));
        boolean publicIdChanged = has(c.getPlatformPublicKeyId()) && !Objects.equals(c.getPlatformPublicKeyId(), text(req.platformPublicKeyId()));
        if (merchantChanged && has(c.getApiV3KeyEncrypted()) && text(req.apiV3Key()).isEmpty() && !req.clearApiV3Key()) {
            throw BizException.of(400, "更换商户号时必须更换或明确清除API v3密钥");
        }
        if ((merchantChanged || certificateChanged) && has(c.getMerchantPrivateKeyEncrypted()) && text(req.merchantPrivateKey()).isEmpty() && !req.clearMerchantPrivateKey()) {
            throw BizException.of(400, "更换商户或证书序列号时必须更换或明确清除商户私钥");
        }
        if (publicIdChanged && has(c.getPlatformPublicKeyEncrypted()) && text(req.platformPublicKey()).isEmpty() && !req.clearPlatformPublicKey()) {
            throw BizException.of(400, "更换平台公钥ID时必须更换或明确清除平台公钥");
        }
        c.setEnabled(Boolean.TRUE.equals(req.enabled())); c.setAppId(text(req.appId())); c.setMchId(text(req.mchId()));
        c.setMerchantSerialNo(text(req.merchantSerialNo())); c.setNotifyUrl(text(req.notifyUrl())); c.setRefundNotifyUrl(text(req.refundNotifyUrl()));
        c.setPlatformPublicKeyId(text(req.platformPublicKeyId()));
        String apiKey = text(req.apiV3Key());
        if (!apiKey.isEmpty() && !apiKey.matches("[\\x21-\\x7E]{32}")) throw BizException.of(400, "API v3密钥必须为32位ASCII可见字符");
        if (!text(req.merchantPrivateKey()).isEmpty()) validatePem(req.merchantPrivateKey(), true);
        if (!text(req.platformPublicKey()).isEmpty()) validatePem(req.platformPublicKey(), false);
        c.setApiV3KeyEncrypted(updateSecret(c.getApiV3KeyEncrypted(), apiKey, req.clearApiV3Key(), "PAYMENT.apiV3Key"));
        c.setMerchantPrivateKeyEncrypted(updateSecret(c.getMerchantPrivateKeyEncrypted(), req.merchantPrivateKey(), req.clearMerchantPrivateKey(), "PAYMENT.merchantPrivateKey"));
        c.setPlatformPublicKeyEncrypted(updateSecret(c.getPlatformPublicKeyEncrypted(), req.platformPublicKey(), req.clearPlatformPublicKey(), "PAYMENT.platformPublicKey"));
        rejectIfInvalid(paymentProblems(c));
        stamp(c); repository.saveAndFlush(c);
        audit.record("WECHAT_PAYMENT_CONFIG_SAVE", "section=PAYMENT, enabled=" + c.isEnabled() + ", runtime=MOCK, version=" + c.getVersion());
        return paymentView(c);
    }

    @Transactional(readOnly = true)
    public LoginCredentials effectiveLogin() {
        WechatConfig c = repository.findById("LOGIN").orElse(null);
        if (c == null) {
            if (envAppId.isBlank() || envSecret.isBlank()) throw BizException.of(503, "微信登录未配置，请在后台微信配置或后端环境变量中设置");
            return new LoginCredentials(envAppId, envSecret);
        }
        if (!c.isEnabled()) throw BizException.of(503, "微信登录已由管理员关闭");
        rejectIfInvalid(loginProblems(c));
        return new LoginCredentials(c.getAppId(), cipher.decrypt(c.getAppSecretEncrypted(), "LOGIN.appSecret"));
    }

    @Transactional
    public ValidationView validate(String section) {
        List<String> problems = new ArrayList<>();
        if ("LOGIN".equals(section)) {
            WechatConfig c = repository.findById("LOGIN").orElse(null);
            if (c == null) {
                if (!envAppId.matches("wx[0-9a-fA-F]{16}")) problems.add("环境AppID未配置或格式不正确");
                if (envSecret.isBlank()) problems.add("环境AppSecret未配置");
            } else {
                if (!c.isEnabled()) problems.add("微信登录当前已关闭");
                problems.addAll(loginProblems(c));
                if (!has(c.getAppSecretEncrypted())) problems.add("AppSecret未配置");
                else cipher.decrypt(c.getAppSecretEncrypted(), "LOGIN.appSecret");
            }
        } else if ("PAYMENT".equals(section)) {
            WechatConfig c = repository.findById("PAYMENT").orElse(null);
            if (c == null) problems.add("微信支付参数尚未保存");
            else {
                if (!c.isEnabled()) problems.add("支付参数未标记为启用");
                problems.addAll(paymentProblems(c));
                for (String field : List.of("appId", "mchId", "merchantSerialNo", "notifyUrl", "platformPublicKeyId")) {
                    String value = switch (field) { case "appId" -> c.getAppId(); case "mchId" -> c.getMchId(); case "merchantSerialNo" -> c.getMerchantSerialNo(); case "notifyUrl" -> c.getNotifyUrl(); default -> c.getPlatformPublicKeyId(); };
                    if (!has(value)) problems.add(field + "未配置");
                }
                if (!has(c.getApiV3KeyEncrypted())) problems.add("API v3密钥未配置"); else cipher.decrypt(c.getApiV3KeyEncrypted(), "PAYMENT.apiV3Key");
                if (!has(c.getMerchantPrivateKeyEncrypted())) problems.add("商户私钥未配置"); else validatePem(cipher.decrypt(c.getMerchantPrivateKeyEncrypted(), "PAYMENT.merchantPrivateKey"), true);
                if (!has(c.getPlatformPublicKeyEncrypted())) problems.add("微信支付平台公钥未配置"); else validatePem(cipher.decrypt(c.getPlatformPublicKeyEncrypted(), "PAYMENT.platformPublicKey"), false);
            }
        } else throw BizException.of(400, "配置类型仅支持LOGIN或PAYMENT");
        audit.record("WECHAT_CONFIG_VALIDATE", "section=" + section + ", valid=" + problems.isEmpty());
        List<String> messages = new ArrayList<>(new LinkedHashSet<>(problems));
        messages.add("仅检查已保存参数格式与完整性，不联网验证微信凭据或商户权限，不发起支付");
        if ("PAYMENT".equals(section)) messages.add("当前支付运行模式仍为MOCK；真实预支付、回调验签和退款尚未接入");
        return new ValidationView(problems.isEmpty(), messages, "PAYMENT".equals(section) ? "MOCK" : "WECHAT_CODE2SESSION");
    }

    private WechatConfig editable(String section, Long expected) {
        WechatConfig c = repository.findById(section).orElse(null);
        if (c == null) {
            if (expected != null) throw BizException.of(409, "配置版本已变化，请刷新后重试");
            c = new WechatConfig(); c.setSection(section);
        } else if (!Objects.equals(c.getVersion(), expected)) throw BizException.of(409, "配置已被其他管理员修改，请刷新后重试");
        return c;
    }
    private void stamp(WechatConfig c) { c.setUpdatedBy(UserContext.userId()); c.setUpdatedTime(LocalDateTime.now()); }
    private String updateSecret(String old, String incoming, boolean clear, String context) {
        String value = text(incoming);
        if (clear && !value.isEmpty()) throw BizException.of(400, "不能同时填写新密钥并清除密钥");
        if (clear) return null;
        if (value.isEmpty()) return old;
        if (value.contains("****") || value.contains("••••")) throw BizException.of(400, "请输入真实密钥，不要提交脱敏占位符");
        return cipher.encrypt(value, context);
    }
    private List<String> loginProblems(WechatConfig c) {
        List<String> p = new ArrayList<>();
        if ((c.isEnabled() || has(c.getAppId())) && !text(c.getAppId()).matches("wx[0-9a-fA-F]{16}")) p.add("AppID必须为wx开头的16位十六进制标识");
        if (c.isEnabled() && !has(c.getAppSecretEncrypted())) p.add("AppSecret未配置");
        return p;
    }
    private List<String> paymentProblems(WechatConfig c) {
        List<String> p = new ArrayList<>(); boolean required = c.isEnabled();
        if ((required || has(c.getAppId())) && !text(c.getAppId()).matches("wx[0-9a-fA-F]{16}")) p.add("支付AppID格式不正确");
        if ((required || has(c.getMchId())) && !text(c.getMchId()).matches("[0-9]{8,32}")) p.add("商户号必须为8至32位数字");
        if ((required || has(c.getMerchantSerialNo())) && !text(c.getMerchantSerialNo()).matches("[0-9a-fA-F]{16,64}")) p.add("商户证书序列号必须为16至64位十六进制字符");
        if ((required || has(c.getNotifyUrl())) && !httpsUrl(c.getNotifyUrl())) p.add("支付通知URL必须为无凭据的有效HTTPS地址");
        if (has(c.getRefundNotifyUrl()) && !httpsUrl(c.getRefundNotifyUrl())) p.add("退款通知URL必须为有效HTTPS地址");
        if ((required || has(c.getPlatformPublicKeyId())) && !text(c.getPlatformPublicKeyId()).matches("PUB_KEY_ID_[A-Za-z0-9_]{1,100}")) p.add("平台公钥ID必须以PUB_KEY_ID_开头");
        if (required && !has(c.getApiV3KeyEncrypted())) p.add("API v3密钥未配置");
        if (required && !has(c.getMerchantPrivateKeyEncrypted())) p.add("商户私钥未配置");
        if (required && !has(c.getPlatformPublicKeyEncrypted())) p.add("平台公钥未配置");
        return p;
    }
    private static boolean httpsUrl(String value) {
        try { URI u = URI.create(value); return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && u.getUserInfo() == null && u.getFragment() == null && (u.getPort() == -1 || (u.getPort() > 0 && u.getPort() <= 65535)); }
        catch (Exception e) { return false; }
    }
    private static void validatePem(String value, boolean privateKey) {
        try {
            String type = privateKey ? "PRIVATE KEY" : "PUBLIC KEY";
            String pem = text(value);
            if (!pem.startsWith("-----BEGIN " + type + "-----") || !pem.endsWith("-----END " + type + "-----")) throw new IllegalArgumentException();
            byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN " + type + "-----", "").replace("-----END " + type + "-----", "").replaceAll("\\s", ""));
            var key = privateKey ? KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der)) : KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            if (((RSAKey) key).getModulus().bitLength() < 2048) throw new IllegalArgumentException();
        } catch (Exception e) { throw BizException.of(400, privateKey ? "商户私钥必须为至少2048位RSA PKCS#8 PEM私钥" : "平台公钥必须为至少2048位RSA PUBLIC KEY PEM公钥"); }
    }
    private static void rejectIfInvalid(List<String> problems) { if (!problems.isEmpty()) throw BizException.of(400, String.join("；", problems)); }
    private static boolean has(String s) { return s != null && !s.isBlank(); }
    private static String text(String s) { return s == null ? "" : s.trim(); }
}
