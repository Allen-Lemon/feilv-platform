package com.feilv.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.GlobalExceptionHandler;
import com.feilv.common.UserContext;
import com.feilv.domain.User;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.repository.UserRepository;
import com.feilv.repository.WechatIdentityRepository;
import com.feilv.service.*;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真正换绑服务 + 认证拦截器；严格输入/当前账户/隐私和默认关闭契约。 */
class PhoneChangeControllerTest {
    private static final String ROOT = "/api/v1/user";
    private static final String OLD = "13900139000", NEW = "13800138000";
    private final UserRepository users = mock(UserRepository.class);
    private final WechatIdentityRepository identities = mock(WechatIdentityRepository.class);
    private final WechatConfigService settings = mock(WechatConfigService.class);
    private final WechatPhoneClient phone = mock(WechatPhoneClient.class);
    private final MessageService messages = mock(MessageService.class);
    private final AuditService audit = mock(AuditService.class);
    private final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, User> accounts = new HashMap<>();
    private final String token = "Bearer " + JwtUtil.create(42L, "USER");
    private final WechatConfigService.LoginCredentials credentials = new WechatConfigService.LoginCredentials("http-change-app", "secret");
    private User user;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        user = account(42L, "USER", OLD); account(43L, "RIDER", "13700137000"); account(1L, "ADMIN", "13600136000");
        user.setNickname("原昵称"); user.setAvatarUrl("/original-avatar.png");
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> new SimpleTransactionStatus());
        when(users.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0))));
        when(users.findByIdForUpdate(anyLong())).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0))));
        when(users.findByMobile(anyString())).thenAnswer(call -> accounts.values().stream().filter(value -> call.getArgument(0).equals(value.getMobile())).findFirst());
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(settings.effectiveLogin()).thenReturn(credentials);
        when(identities.existsByUserIdAndAppId(anyLong(), eq(credentials.appId()))).thenReturn(true);
        when(phone.exchange("wechat-authorization", credentials)).thenReturn(new WechatPhoneClient.Phone(credentials.appId(), NEW));
        mvc = mvc(true, true);
    }

    @Test
    void allFourEndpointsRequireCurrentAuthenticationNotClientUserId() throws Exception {
        for (String authorization : new String[]{null, "Bearer invalid"}) {
            for (String path : paths()) {
                var request = post(ROOT + path).contentType(MediaType.APPLICATION_JSON).content(body(path));
                if (authorization != null) request.header("Authorization", authorization);
                mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
            }
        }
        verify(users, never()).findByIdForUpdate(anyLong()); verifyNoInteractions(messages, phone, audit);
        assertNull(UserContext.get());
    }

    @Test
    void changeSmsAuthenticatesNewNumberAndReturnsOnlyMaskedUnchangedProfile() throws Exception {
        var sent = mvc.perform(post(ROOT + "/phone/change/sms-code").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"" + NEW + "\"}"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(60)).andExpect(jsonPath("$.data.mock").value(true))
                .andExpect(jsonPath("$.data.demoCode").value(matchesPattern("[0-9]{6}")))
                .andExpect(content().string(not(containsString(NEW)))).andReturn();
        String code = json.readTree(sent.getResponse().getContentAsString()).path("data").path("demoCode").asText();
        mvc.perform(post(ROOT + "/phone/change").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("mobile", NEW, "code", code))))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(42)).andExpect(jsonPath("$.data.nickname").value("原昵称"))
                .andExpect(jsonPath("$.data.role").value("USER")).andExpect(jsonPath("$.data.mobile").value("138****8000"))
                .andExpect(jsonPath("$.data.mobileBound").value(true)).andExpect(jsonPath("$.data.avatarUrl").value("/original-avatar.png"))
                .andExpect(jsonPath("$.data.code").doesNotExist()).andExpect(jsonPath("$.data.demoCode").doesNotExist())
                .andExpect(content().string(not(containsString(NEW)))).andExpect(content().string(not(containsString(OLD))))
                .andExpect(content().string(not(containsString(code))));
        assertEquals(NEW, user.getMobile()); assertEquals("13700137000", accounts.get(43L).getMobile());
        assertNull(UserContext.get());
    }

    @Test
    void wechatAndDevelopmentUseOnlyCurrentAccountAndReturnMaskedProfile() throws Exception {
        mvc.perform(post(ROOT + "/wechat-phone/change").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wechat-authorization\"}"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mobile").value("138****8000"))
                .andExpect(content().string(not(containsString("wechat-authorization"))));
        mvc.perform(post(ROOT + "/phone/development/change").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"13500135000\"}"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(42)).andExpect(jsonPath("$.data.mobile").value("135****5000"));
        verify(audit).record("PHONE_CHANGE", "userId=42,channel=DEVELOPMENT,old=138****8000,new=135****5000");
    }

    @Test
    void riderMayChangeButMissingFrozenMismatchedRoleAndAdminAreRejected() throws Exception {
        mvc.perform(post(ROOT + "/phone/development/change").header("Authorization", "Bearer " + JwtUtil.create(43L, "RIDER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"" + NEW + "\"}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.role").value("RIDER"));
        assertAllBlocked("Bearer " + JwtUtil.create(1L, "ADMIN"), 200, 403);
        user.setStatus(0); assertAllBlocked(token, 403, 403);
        user.setStatus(1); user.setRole("RIDER"); assertAllBlocked(token, 403, 403);
        accounts.remove(42L); assertAllBlocked(token, 403, 403);
    }

    @Test
    void defaultSmsAndDevelopmentAreClosedAndUnboundMustUseFirstBinding() throws Exception {
        mvc = mvc(false, false);
        for (String path : new String[]{"/phone/change/sms-code", "/phone/change"}) {
            mvc.perform(post(ROOT + path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(path)))
                    .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value(503))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
        mvc.perform(post(ROOT + "/phone/development/change").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(body("/phone/development/change"))).andExpect(jsonPath("$.code").value(403));
        mvc = mvc(true, true); user.setMobile(null); assertAllBlocked(token, 200, 409);
        verifyNoInteractions(phone, messages, audit);
    }

    @Test
    void occupiedNumberIs409ForEveryChannelAndNoResponseLeaksAnyMobile() throws Exception {
        accounts.get(43L).setMobile(NEW); accounts.get(43L).setRole("ADMIN"); accounts.get(43L).setStatus(0);
        assertAllBlocked(token, 200, 409);
        assertEquals(OLD, user.getMobile()); verify(users, never()).saveAndFlush(any()); verifyNoInteractions(audit);
    }

    @Test
    void rejectsIdentityPurposeAndBypassFieldsForAllChangeRequests() throws Exception {
        for (String extra : new String[]{"\"userId\":43", "\"role\":\"ADMIN\"", "\"purpose\":\"PHONE_BINDING\"", "\"skipVerification\":true", "\"flag\":true"}) {
            for (String path : paths()) {
                String valid = body(path);
                mvc.perform(post(ROOT + path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                                .content(valid.substring(0, valid.length() - 1) + "," + extra + "}"))
                        .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value(400))
                        .andExpect(content().string(not(containsString(NEW))));
            }
        }
        verify(users, never()).findByIdForUpdate(anyLong()); verifyNoInteractions(messages, phone, audit);
    }

    @Test
    void strictTypedInputsRejectNumbersBooleansAndMalformedDataBeforeSideEffects() throws Exception {
        for (String path : paths()) {
            for (String invalid : new String[]{"null", "{}", "{", "{\"mobile\":13800138000}", "{\"mobile\":true}", "{\"mobile\":[]}"}) {
                mvc.perform(post(ROOT + path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(invalid))
                        .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value(400));
            }
        }
        for (String invalid : new String[]{"{\"code\":123456}", "{\"code\":true}", "{\"code\":null}", "{\"code\":\" \"}",
                "{\"code\":\"" + "x".repeat(257) + "\"}"}) {
            mvc.perform(post(ROOT + "/wechat-phone/change").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(jsonPath("$.code").value(400));
        }
        for (String invalidCode : new String[]{"123456", "true", "null", "[]", "\"12345\"", "\"abcdef\""}) {
            mvc.perform(post(ROOT + "/phone/change").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mobile\":\"" + NEW + "\",\"code\":" + invalidCode + "}"))
                    .andExpect(jsonPath("$.code").value(400));
        }
        verify(users, never()).findByIdForUpdate(anyLong()); verifyNoInteractions(phone, messages, audit);
    }

    private MockMvc mvc(boolean smsEnabled, boolean devEnabled) {
        var env = new MockEnvironment(); env.setActiveProfiles("local");
        var development = new DevelopmentPhoneBindingService(users, manager, env, devEnabled);
        var changes = new PhoneChangeService(users, identities, settings, phone, development, audit, entityManager, manager);
        var sms = new SmsPhoneBindingService(users, messages, audit, entityManager, manager, smsEnabled);
        return MockMvcBuilders.standaloneSetup(new PhoneChangeController(changes, sms)).setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AuthInterceptor(users)).build();
    }
    private User account(long id, String role, String mobile) {
        User value = new User(); value.setId(id); value.setRole(role); value.setMobile(mobile); accounts.put(id, value); return value;
    }
    private static String[] paths() { return new String[]{"/wechat-phone/change", "/phone/change/sms-code", "/phone/change", "/phone/development/change"}; }
    private static String body(String path) {
        if (path.startsWith("/wechat")) return "{\"code\":\"wechat-authorization\"}";
        return path.equals("/phone/change") ? "{\"mobile\":\"" + NEW + "\",\"code\":\"123456\"}" : "{\"mobile\":\"" + NEW + "\"}";
    }
    private void assertAllBlocked(String authorization, int http, int code) throws Exception {
        for (String path : paths()) {
            mvc.perform(post(ROOT + path).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content(body(path)))
                    .andExpect(status().is(http)).andExpect(jsonPath("$.code").value(code))
                    .andExpect(content().string(not(containsString(NEW)))).andExpect(content().string(not(containsString(OLD))));
        }
    }
}
