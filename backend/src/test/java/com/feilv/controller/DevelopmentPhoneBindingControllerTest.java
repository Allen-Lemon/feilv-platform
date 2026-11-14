package com.feilv.controller;

import com.feilv.common.GlobalExceptionHandler;
import com.feilv.domain.User;
import com.feilv.repository.UserRepository;
import com.feilv.service.DevelopmentPhoneBindingService;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DevelopmentPhoneBindingControllerTest {
    @Test void optionsAndDevelopmentBindingRequireAuthenticationAndIgnoreNoEnvironmentGuards() throws Exception {
        var users = mock(UserRepository.class); var manager = mock(PlatformTransactionManager.class);
        User user = new User(); user.setId(42L); user.setNickname("开发测试");
        when(users.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> new SimpleTransactionStatus());
        var env = new MockEnvironment(); env.setActiveProfiles("local");
        var binding = new DevelopmentPhoneBindingService(users, manager, env, true);
        var mvc = MockMvcBuilders.standaloneSetup(new DevelopmentPhoneBindingController(binding))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(TestAuth.interceptor()).build();
        String token = "Bearer " + JwtUtil.create(42L, "USER");
        mvc.perform(get("/api/v1/user/phone/options")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/user/phone/development").contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"13800138000\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/user/phone/options").header("Authorization", token)).andExpect(jsonPath("$.data.developmentBypass").value(true))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/v1/user/phone/development").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"13800138000\",\"userId\":99}"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/v1/user/phone/development").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"13800138000\"}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.mobile").value("138****8000")).andExpect(header().string("Cache-Control", "no-store"));
        env.setActiveProfiles("local", "prod");
        mvc.perform(get("/api/v1/user/phone/options").header("Authorization", token)).andExpect(jsonPath("$.data.developmentBypass").value(false));
        mvc.perform(post("/api/v1/user/phone/development").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"13800138000\"}"))
                .andExpect(jsonPath("$.code").value(403));
    }
}
