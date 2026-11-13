package com.feilv.controller;

import com.feilv.common.GlobalExceptionHandler;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.service.UserService;
import com.feilv.service.WechatLoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {
    private DataSource dataSource;
    private UserService users;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        dataSource = mock(DataSource.class);
        users = mock(UserService.class);
        mvc = MockMvcBuilders.standaloneSetup(new HealthController(dataSource),
                        new AuthController(users, mock(WechatLoginService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AuthInterceptor()).build();
    }

    @Test
    void anonymousGetChecksDatabaseAndClosesConnectionWithoutSmsSideEffects() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"));
        verify(connection).close();
        verifyNoInteractions(users);
    }

    @Test
    void databaseFailureReturnsUnavailableWithoutLeakingDetails() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("internal-db-address-and-password"));
        mvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.msg").value("服务暂未就绪"))
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(users);
    }

    @Test
    void invalidConnectionReturnsUnavailableAndIsClosed() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);
        mvc.perform(get("/health")).andExpect(status().isServiceUnavailable());
        verify(connection).close();
    }

    @Test
    void getSmsCodeReturns405WithAllowHeaderAndDoesNotSendSms() throws Exception {
        mvc.perform(get("/api/v1/auth/sms-code"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value(405));
        verifyNoInteractions(users, dataSource);
    }

    @Test
    void getAdminLoginReturns405AndPostLoginStillWorks() throws Exception {
        mvc.perform(get("/api/v1/auth/admin-login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")));
        verifyNoInteractions(users);
        when(users.adminLogin("operator", "test-password"))
                .thenReturn(new LoginVO("test-token", 1L, "ADMIN", "操作员"));
        mvc.perform(post("/api/v1/auth/admin-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operator\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(users).adminLogin("operator", "test-password");
    }
}
