package com.feilv.controller;

import com.feilv.common.*;
import com.feilv.dto.WechatConfigDtos.*;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.service.*;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WechatConfigControllerTest {
    @Test void authenticationAndRealAuthorizationRequiredAndResponseHasNoSecret() throws Exception {
        var service = mock(WechatConfigService.class); var access = mock(WechatConfigAccess.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WechatConfigController(service, access))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(TestAuth.interceptor()).build();
        mvc.perform(get("/api/v1/admin/system/wechat")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/system/wechat").header("Authorization", "Bearer " + JwtUtil.create(2L, "USER")))
                .andExpect(status().isForbidden());
        doThrow(BizException.of(403, "无微信配置管理权限")).when(access).requireManage();
        mvc.perform(get("/api/v1/admin/system/wechat").header("Authorization", "Bearer " + JwtUtil.create(1L, "ADMIN")))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
        doNothing().when(access).requireManage();
        when(service.saveLogin(any())).thenReturn(new LoginView(true, "wx1234567890abcdef", true, "DATABASE", 0L, null));
        mvc.perform(put("/api/v1/admin/system/wechat/login").header("Authorization", "Bearer " + JwtUtil.create(1L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"appId\":\"wx1234567890abcdef\",\"appSecret\":\"0123456789abcdef0123456789abcdef\"}"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.appSecretConfigured").value(true))
                .andExpect(jsonPath("$.data.appSecret").doesNotExist());
        mvc.perform(put("/api/v1/admin/system/wechat/login").header("Authorization", "Bearer " + JwtUtil.create(1L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(400));
        verify(service, times(1)).saveLogin(any());
    }
}
