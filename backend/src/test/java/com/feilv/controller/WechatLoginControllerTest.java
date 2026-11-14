package com.feilv.controller;

import com.feilv.common.GlobalExceptionHandler;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.service.UserService;
import com.feilv.service.WechatLoginService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WechatLoginControllerTest {
    @Test
    void wechatCodeEndpointIsPublicButProfileRemainsProtected() throws Exception {
        WechatLoginService login = mock(WechatLoginService.class);
        when(login.login("verified-code", true)).thenReturn(new LoginVO("platform-token", 42L, "USER", "微信用户"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(mock(UserService.class), login))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(TestAuth.interceptor()).build();
        mvc.perform(post("/api/v1/auth/wechat-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"verified-code\",\"agreed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("platform-token"));
        mvc.perform(post("/api/v1/auth/wechat-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"agreed\":true}"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/v1/auth/wechat-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"verified-code\"}"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/v1/auth/admin-profile")).andExpect(status().isUnauthorized());
        verify(login, times(1)).login(anyString(), anyBoolean());
    }
}
