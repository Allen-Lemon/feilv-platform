package com.feilv.controller;

import com.feilv.common.*;
import com.feilv.dto.WechatConfigDtos.*;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.service.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WechatConfigControllerTest {
    @Test void explicitUserAndRiderRoutesDispatchWithoutChangingLegacyAliasOrValidationDefault() throws Exception {
        var service = mock(WechatConfigService.class); var access = mock(WechatConfigAccess.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WechatConfigController(service, access))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var rider = new LoginView(true, "wxabcdef1234567890", true, "DATABASE", 9L, null);
        when(service.saveLogin(eq("RIDER"), any())).thenReturn(rider);
        when(service.saveLogin(eq("USER"), any())).thenReturn(new LoginView(false, "", false, "DATABASE", 3L, null));
        for (String client : new String[]{"USER", "RIDER"}) {
            mvc.perform(put("/api/v1/admin/system/wechat/login/" + client)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"appId\":\"\",\"appSecret\":\"\",\"version\":9}"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.appSecret").doesNotExist());
            verify(service).saveLogin(eq(client), argThat(req -> req.version() == 9L && req.appSecret().isEmpty()));
        }
        when(service.validate(anyString(), nullable(String.class))).thenReturn(new ValidationView(true, java.util.List.of("offline"), "WECHAT_CODE2SESSION"));
        mvc.perform(post("/api/v1/admin/system/wechat/validate").contentType(MediaType.APPLICATION_JSON).content("{\"section\":\"LOGIN\"}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).validate("LOGIN", null);
        for (String client : new String[]{"USER", "RIDER"}) {
            mvc.perform(post("/api/v1/admin/system/wechat/validate").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"section\":\"LOGIN\",\"clientType\":\"" + client + "\"}"))
                    .andExpect(jsonPath("$.data.valid").value(true));
            verify(service).validate("LOGIN", client);
        }
        verify(access, times(5)).requireManage();
    }
    @Test void unknownClientsPaymentClientMisuseAndDatabaseConcurrencyErrorsReturnSafeErrors() throws Exception {
        var service = mock(WechatConfigService.class); var access = mock(WechatConfigAccess.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WechatConfigController(service, access))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.saveLogin(eq("UNKNOWN"), any())).thenThrow(BizException.of(400, "客户端类型仅支持USER或RIDER"));
        mvc.perform(put("/api/v1/admin/system/wechat/login/UNKNOWN").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.code").value(400)).andExpect(header().string("Cache-Control", "no-store"));
        when(service.validate("PAYMENT", "RIDER")).thenThrow(BizException.of(400, "支付配置不能指定clientType"));
        mvc.perform(post("/api/v1/admin/system/wechat/validate").contentType(MediaType.APPLICATION_JSON).content("{\"section\":\"PAYMENT\",\"clientType\":\"RIDER\"}"))
                .andExpect(jsonPath("$.code").value(400));
        when(service.saveLogin(eq("RIDER"), any())).thenThrow(new org.springframework.dao.CannotSerializeTransactionException("test-only-conflict"));
        mvc.perform(put("/api/v1/admin/system/wechat/login/RIDER").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.code").value(409));
    }
    @Test void authenticationAndRealAuthorizationRequiredAndResponseHasNoSecret() throws Exception {
        var service = mock(WechatConfigService.class); var access = mock(WechatConfigAccess.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WechatConfigController(service, access))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(TestAuth.interceptor()).build();
        mvc.perform(get("/api/v1/admin/system/wechat")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/system/wechat").header("Authorization", "Bearer " + TestAuth.userToken(2L, "USER")))
                .andExpect(status().isForbidden());
        doThrow(BizException.of(403, "无微信配置管理权限")).when(access).requireManage();
        mvc.perform(get("/api/v1/admin/system/wechat").header("Authorization", "Bearer " + TestAuth.adminToken(1L)))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
        doNothing().when(access).requireManage();
        when(service.saveLogin(any())).thenReturn(new LoginView(true, "wx1234567890abcdef", true, "DATABASE", 0L, null));
        mvc.perform(put("/api/v1/admin/system/wechat/login").header("Authorization", "Bearer " + TestAuth.adminToken(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"appId\":\"wx1234567890abcdef\",\"appSecret\":\"0123456789abcdef0123456789abcdef\"}"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.appSecretConfigured").value(true))
                .andExpect(jsonPath("$.data.appSecret").doesNotExist());
        mvc.perform(put("/api/v1/admin/system/wechat/login").header("Authorization", "Bearer " + TestAuth.adminToken(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(400));
        verify(service, times(1)).saveLogin(any());
    }
}
