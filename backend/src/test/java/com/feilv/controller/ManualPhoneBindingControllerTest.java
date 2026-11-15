package com.feilv.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.GlobalExceptionHandler;
import com.feilv.common.UserContext;
import com.feilv.domain.User;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.repository.UserRepository;
import com.feilv.service.MessageService;
import com.feilv.service.SmsPhoneBindingService;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真正的绑号服务 + 生产认证拦截器，数据库/日志短信使用本机 mock。 */
class ManualPhoneBindingControllerTest {
    private static final String MOBILE = "13800138000";
    private final UserRepository users = mock(UserRepository.class);
    private final MessageService messages = mock(MessageService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, User> accounts = new HashMap<>();
    private final String token = "Bearer " + JwtUtil.create(42L, "USER");
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setup() {
        user = account(42L, "USER");
        user.setNickname("已有昵称");
        user.setAvatarUrl("/api/v1/avatars/12345678-1234-1234-1234-123456789abc.png");
        account(43L, "RIDER");
        account(1L, "ADMIN");
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> new SimpleTransactionStatus());
        when(users.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0))));
        when(users.findByIdForUpdate(anyLong())).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0))));
        when(users.findByMobile(anyString())).thenAnswer(call -> accounts.values().stream()
                .filter(value -> call.getArgument(0).equals(value.getMobile())).findFirst());
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        mvc = mvc(true);
    }

    @Test
    void bothEndpointsRequirePlatformAuthenticationNotClientIdentity() throws Exception {
        for (String authorization : new String[]{null, "Bearer invalid-token"}) {
            var send = post("/api/v1/user/phone/sms-code").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mobile\":\"13800138000\",\"userId\":42}");
            var bind = post("/api/v1/user/phone").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mobile\":\"13800138000\",\"code\":\"123456\",\"userId\":42}");
            if (authorization != null) { send.header("Authorization", authorization); bind.header("Authorization", authorization); }
            mvc.perform(send).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
            mvc.perform(bind).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        }
        verifyNoInteractions(messages);
        verify(users, never()).findByIdForUpdate(anyLong());
        assertNull(UserContext.get());
    }

    @Test
    void defaultClosedContractHas503AndNoCodeOrMockSuccessData() throws Exception {
        mvc = mvc(false);
        mvc.perform(post("/api/v1/user/phone/sms-code").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"13800138000\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.msg").value(containsString("微信授权")))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(not(containsString(MOBILE))));
        mvc.perform(post("/api/v1/user/phone").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"13800138000\",\"code\":\"123456\"}"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(503)).andExpect(jsonPath("$.data").value(nullValue()));
        verifyNoInteractions(messages);
    }

    @Test
    void mockFlowBindsOnlyAuthenticatedAccountAndReturnsCompleteMaskedProfile() throws Exception {
        String code = send(token, MOBILE);
        mvc.perform(post("/api/v1/user/phone").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("mobile", MOBILE, "code", code))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.nickname").value("已有昵称"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.mobile").value("138****8000"))
                .andExpect(jsonPath("$.data.mobileBound").value(true))
                .andExpect(jsonPath("$.data.avatarUrl").value(user.getAvatarUrl()))
                .andExpect(jsonPath("$.data.code").doesNotExist())
                .andExpect(jsonPath("$.data.demoCode").doesNotExist())
                .andExpect(content().string(not(containsString(MOBILE))));
        assertEquals(MOBILE, user.getMobile());
        assertNull(accounts.get(43L).getMobile());
        verify(users).saveAndFlush(user);
        assertNull(UserContext.get());
    }

    @Test
    void riderCanSendAndBindWithoutWechatIdentity() throws Exception {
        String riderToken = "Bearer " + JwtUtil.create(43L, "RIDER");
        String code = send(riderToken, MOBILE);
        mvc.perform(post("/api/v1/user/phone").header("Authorization", riderToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("mobile", MOBILE, "code", code))))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.userId").value(43))
                .andExpect(jsonPath("$.data.role").value("RIDER"));
        assertNull(user.getMobile());
    }

    @Test
    void rejectsClientIdentityAndPurposeFieldsRatherThanAcceptingAccountOverride() throws Exception {
        for (String extra : new String[]{"\"userId\":43", "\"role\":\"ADMIN\"", "\"purpose\":\"LOGIN\""}) {
            mvc.perform(post("/api/v1/user/phone/sms-code").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mobile\":\"13800138000\"," + extra + "}"))
                    .andExpect(jsonPath("$.code").value(400)).andExpect(content().string(not(containsString(MOBILE))));
            mvc.perform(post("/api/v1/user/phone").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mobile\":\"13800138000\",\"code\":\"123456\"," + extra + "}"))
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(messages);
        verify(users, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void rejectsMalformedMissingAndBlankRequestFieldsWithoutSmsOrDatabaseWrites() throws Exception {
        for (String body : new String[]{"{}", "null", "{", "{\"mobile\":null}", "{\"mobile\":\"\"}",
                "{\"mobile\":\" 13800138000\"}", "{\"mobile\":\"12800138000\"}", "{\"mobile\":[]}",
                "{\"mobile\":13800138000}", "{\"mobile\":true}"}) {
            mvc.perform(post("/api/v1/user/phone/sms-code").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(400));
        }
        for (String body : new String[]{"{}", "null", "{\"mobile\":\"13800138000\"}",
                "{\"mobile\":\"13800138000\",\"code\":null}", "{\"mobile\":\"13800138000\",\"code\":\"\"}",
                "{\"mobile\":\"13800138000\",\"code\":\"abcdef\"}", "{\"mobile\":\"13800138000\",\"code\":\"12345\"}",
                "{\"mobile\":\"13800138000\",\"code\":123456}", "{\"mobile\":\"13800138000\",\"code\":true}"}) {
            mvc.perform(post("/api/v1/user/phone").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(messages);
        verify(users, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void frozenMissingRoleMismatchAndAdminAccountsCannotUseEitherEndpoint() throws Exception {
        String adminToken = "Bearer " + JwtUtil.create(1L, "ADMIN");
        assertBlockedBoth(adminToken, 200, 403);
        user.setStatus(0);
        assertBlockedBoth(token, 403, 403);
        user.setStatus(1);
        user.setRole("RIDER");
        assertBlockedBoth(token, 403, 403);
        accounts.remove(42L);
        assertBlockedBoth(token, 403, 403);
        verifyNoInteractions(messages);
    }

    @Test
    void consumedAndWrongCodesAreNotPlatformSessionExpiryAndNeverEchoCode() throws Exception {
        String code = send(token, MOBILE);
        String wrong = code.equals("000000") ? "999999" : "000000";
        mvc.perform(post("/api/v1/user/phone").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("mobile", MOBILE, "code", wrong))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(4011))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(not(containsString(MOBILE))))
                .andExpect(content().string(not(containsString(code))));
        String body = json.writeValueAsString(Map.of("mobile", MOBILE, "code", code));
        mvc.perform(post("/api/v1/user/phone").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/v1/user/phone").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(4010));
    }

    @Test
    void conflictsAtBothEndpointsAre409AndDoNotExposeNumbers() throws Exception {
        user.setMobile("13900139000");
        assertBlockedBoth(token, 200, 409);
        user.setMobile(null);
        accounts.get(43L).setMobile(MOBILE);
        assertBlockedBoth(token, 200, 409);
        verifyNoInteractions(messages);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void cooldownResponseDoesNotExposePhoneOrCode() throws Exception {
        String code = send(token, MOBILE);
        mvc.perform(post("/api/v1/user/phone/sms-code").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"13800138000\"}"))
                .andExpect(jsonPath("$.code").value(4291)).andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(not(containsString(MOBILE))))
                .andExpect(content().string(not(containsString(code))));
    }

    private MockMvc mvc(boolean enabled) {
        var service = new SmsPhoneBindingService(users, messages, mock(com.feilv.service.AuditService.class), mock(jakarta.persistence.EntityManager.class), manager, enabled);
        return MockMvcBuilders.standaloneSetup(new ManualPhoneBindingController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new AuthInterceptor(users)).build();
    }

    private User account(long id, String role) {
        User value = new User(); value.setId(id); value.setRole(role);
        accounts.put(id, value);
        return value;
    }

    private String send(String authorization, String mobile) throws Exception {
        var response = mvc.perform(post("/api/v1/user/phone/sms-code").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("mobile", mobile))))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.retryAfterSeconds").value(60))
                .andExpect(jsonPath("$.data.mock").value(true)).andExpect(jsonPath("$.data.demoCode").value(matchesPattern("[0-9]{6}")))
                .andExpect(jsonPath("$.data.mobile").doesNotExist()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data").path("demoCode").asText();
    }

    private void assertBlockedBoth(String authorization, int httpStatus, int businessCode) throws Exception {
        for (String path : new String[]{"/api/v1/user/phone/sms-code", "/api/v1/user/phone"}) {
            String body = path.endsWith("sms-code") ? "{\"mobile\":\"13800138000\"}"
                    : "{\"mobile\":\"13800138000\",\"code\":\"123456\"}";
            mvc.perform(post(path).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(businessCode))
                    .andExpect(content().string(not(containsString(MOBILE))));
        }
    }
}
