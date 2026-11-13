package com.feilv.controller;

import com.feilv.common.GlobalExceptionHandler;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.service.AddressService;
import com.feilv.service.UserProfileService;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserProfileControllerTest {
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final String token = "Bearer " + JwtUtil.create(42L, "USER");
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var profile = new ProfileVO(42L, "昵称", "USER", "138****8000", true);
        when(profiles.me(42L)).thenReturn(profile);
        when(profiles.updateNickname(eq(42L), anyString())).thenReturn(profile);
        when(profiles.bindWechatPhone(eq(42L), anyString())).thenReturn(profile);
        mvc = MockMvcBuilders.standaloneSetup(new UserController(profiles, mock(AddressService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new AuthInterceptor()).build();
    }

    @Test
    void profileAndPhoneEndpointsRequirePlatformAuthentication() throws Exception {
        mvc.perform(get("/api/v1/user/me")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/user/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"昵称\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/user/wechat-phone").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"phone-code\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test
    void updatesOnlyAuthenticatedUserAndNeverEchoesPhoneCodeOrClaimedMobile() throws Exception {
        mvc.perform(put("/api/v1/user/profile").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"昵称\",\"userId\":99,\"role\":\"ADMIN\"}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.userId").value(42));
        mvc.perform(post("/api/v1/user/wechat-phone").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"phone-code\",\"mobile\":\"13900139000\",\"userId\":99}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.mobile").value("138****8000"))
                .andExpect(jsonPath("$.data.mobileBound").value(true)).andExpect(jsonPath("$.data.code").doesNotExist());
        verify(profiles).updateNickname(42L, "昵称");
        verify(profiles).bindWechatPhone(42L, "phone-code");
    }

    @Test
    void rejectsMissingPhoneCodeAndBlankNicknameBeforeService() throws Exception {
        mvc.perform(post("/api/v1/user/wechat-phone").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"13800138000\"}"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(put("/api/v1/user/profile").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\" \"}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(profiles);
    }

    @Test
    void meKeepsExistingFieldsAndAddsBindingFlag() throws Exception {
        mvc.perform(get("/api/v1/user/me").header("Authorization", token))
                .andExpect(jsonPath("$.data.nickname").value("昵称"))
                .andExpect(jsonPath("$.data.mobile").value("138****8000"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.mobileBound").value(true));
    }
}
