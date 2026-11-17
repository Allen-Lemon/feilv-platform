package com.feilv.controller;

import com.feilv.common.BizException;
import com.feilv.common.GlobalExceptionHandler;
import com.feilv.config.AuthResponseCacheFilter;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.service.DemoAuthPolicy;
import com.feilv.service.MiniappIdentityLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MiniappIdentityControllerTest {
    private final MiniappIdentityLinkService identities = mock(MiniappIdentityLinkService.class);
    private final DemoAuthPolicy demo = mock(DemoAuthPolicy.class);
    private MockMvc mvc;
    private static final String TICKET = "A".repeat(43), LINK = "B".repeat(43);
    private static final String EXPIRY = "2026-11-17T03:00:00Z";
    private static final LoginVO LOGIN = new LoginVO("signed-session", 42, "USER", "微信用户");

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new MiniappIdentityController(identities, demo))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(TestAuth.interceptor())
                .addFilters(new AuthResponseCacheFilter()).build();
    }

    @Test void fixedClientLoginRequiresAgreementAndReturnsOnlyStateSpecificFields() throws Exception {
        when(identities.login("USER", "mapped-code")).thenReturn(new MiniappIdentityLinkService.LoginState("AUTHENTICATED", LOGIN, null, null));
        when(identities.login("RIDER", "unmapped-code")).thenReturn(new MiniappIdentityLinkService.LoginState("IDENTITY_REQUIRED", null, TICKET, EXPIRY));
        mvc.perform(post("/api/v1/auth/miniapps/USER/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"mapped-code\",\"agreed\":true}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.state").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.data.login.userId").value(42)).andExpect(jsonPath("$.data.login.token").value("signed-session"))
                .andExpect(jsonPath("$.data.loginTicket").doesNotExist()).andExpect(jsonPath("$.data.expiresAt").doesNotExist());
        mvc.perform(post("/api/v1/auth/miniapps/RIDER/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"unmapped-code\",\"agreed\":true}"))
                .andExpect(jsonPath("$.data.state").value("IDENTITY_REQUIRED"))
                .andExpect(jsonPath("$.data.loginTicket").value(TICKET)).andExpect(jsonPath("$.data.expiresAt").value(EXPIRY))
                .andExpect(jsonPath("$.data.login").doesNotExist()).andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
        for (String body : new String[]{"{\"code\":\"missing-agreement\"}", "{\"code\":\"missing-agreement\",\"agreed\":false}",
                "{\"code\":\"missing-agreement\",\"agreed\":\"true\"}", "{\"code\":\"missing-agreement\",\"agreed\":true,\"openid\":\"injected\"}"}) {
            mvc.perform(post("/api/v1/auth/miniapps/USER/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(400)).andExpect(header().string("Cache-Control", "no-store"));
        }
        verify(identities, times(2)).login(anyString(), anyString());
    }

    @Test void creationAndLinkAcceptOnlyWhitelistedProofFieldsAndReturnLoginVO() throws Exception {
        when(identities.createAccount("RIDER", TICKET, "phone-code", true)).thenReturn(LOGIN);
        when(identities.link("USER", TICKET, LINK, true)).thenReturn(LOGIN);
        mvc.perform(post("/api/v1/auth/miniapps/RIDER/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":\"" + TICKET + "\",\"phoneCode\":\"phone-code\",\"agreed\":true}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.token").value(LOGIN.token()))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/v1/auth/miniapps/USER/identity-link").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":\"" + TICKET + "\",\"linkCode\":\"" + LINK + "\",\"agreed\":true}"))
                .andExpect(jsonPath("$.data.userId").value(42)).andExpect(header().string("Cache-Control", "no-store"));
        for (String injected : new String[]{"userId", "mobile", "phone", "appId", "role", "openid", "unionid", "targetClient"}) {
            mvc.perform(post("/api/v1/auth/miniapps/USER/accounts").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginTicket\":\"" + TICKET + "\",\"phoneCode\":\"phone-code\",\"agreed\":true,\"" + injected + "\":\"injected\"}"))
                    .andExpect(jsonPath("$.code").value(400)).andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(post("/api/v1/auth/miniapps/USER/identity-link").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginTicket\":\"" + TICKET + "\",\"linkCode\":\"" + LINK + "\",\"agreed\":true,\"" + injected + "\":\"injected\"}"))
                    .andExpect(jsonPath("$.code").value(400));
        }
        verify(identities, times(1)).createAccount(anyString(), anyString(), anyString(), anyBoolean());
        verify(identities, times(1)).link(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test void linkCodeIsProtectedAndCannotAcceptBearerOnlyOrAccountSelection() throws Exception {
        when(identities.issueLinkCode("fresh-source-login", true)).thenReturn(new MiniappIdentityLinkService.LinkCode(LINK, EXPIRY, "RIDER"));
        mvc.perform(post("/api/v1/user/miniapp-link-codes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"fresh-source-login\",\"agreed\":true}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/v1/user/miniapp-link-codes").header("Authorization", "Bearer " + TestAuth.userToken(42, "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"fresh-source-login\",\"agreed\":true}"))
                .andExpect(jsonPath("$.data.linkCode").value(LINK)).andExpect(jsonPath("$.data.targetClient").value("RIDER"))
                .andExpect(header().string("Cache-Control", "no-store"));
        for (String body : new String[]{"{\"agreed\":true}", "{\"code\":\"fresh-source-login\",\"agreed\":false}",
                "{\"code\":\"fresh-source-login\",\"agreed\":true,\"userId\":99}",
                "{\"code\":\"fresh-source-login\",\"agreed\":true,\"targetClient\":\"USER\"}"}) {
            mvc.perform(post("/api/v1/user/miniapp-link-codes").header("Authorization", "Bearer " + TestAuth.userToken(42, "USER"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(400)).andExpect(header().string("Cache-Control", "no-store"));
        }
        verify(identities, times(1)).issueLinkCode(anyString(), anyBoolean());
    }

    @Test void explicitPublicAllowlistRejectsUnknownClientsMethodsAndKeepsErrorsUncached() throws Exception {
        mvc.perform(post("/api/v1/auth/miniapps/EVIL/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x\",\"agreed\":true}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/auth/miniapps/USER/login"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(identities, never()).login(anyString(), anyString());
        when(identities.createAccount(anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(BizException.of(409, "该手机号已有关联账号，请回原账号生成关联码"));
        mvc.perform(post("/api/v1/auth/miniapps/USER/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":\"" + TICKET + "\",\"phoneCode\":\"phone-code\",\"agreed\":true}"))
                .andExpect(jsonPath("$.code").value(409)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/v1/auth/miniapps/USER/accounts").contentType(MediaType.APPLICATION_JSON).content("{not-json}"))
                .andExpect(jsonPath("$.code").value(400)).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void optionsExposesOnlyActualDemoCapabilityAndNeverEnablesRiderSms() throws Exception {
        when(demo.enabled()).thenReturn(true);
        mvc.perform(get("/api/v1/auth/miniapps/USER/options"))
                .andExpect(jsonPath("$.data.smsLoginEnabled").value(true)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/auth/miniapps/RIDER/options"))
                .andExpect(jsonPath("$.data.smsLoginEnabled").value(false));
        when(demo.enabled()).thenReturn(false);
        mvc.perform(get("/api/v1/auth/miniapps/USER/options")).andExpect(jsonPath("$.data.smsLoginEnabled").value(false));
    }
}
