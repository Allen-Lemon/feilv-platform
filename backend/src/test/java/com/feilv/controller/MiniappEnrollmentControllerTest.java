package com.feilv.controller;

import com.feilv.common.GlobalExceptionHandler;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.dto.MiniappEnrollmentDtos.EnrollmentReq;
import com.feilv.service.MiniappEnrollmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Body-contract tests only; signed HTTP authorization is covered by interceptor integration tests. */
class MiniappEnrollmentControllerTest {
    private final MiniappEnrollmentService enrollment = mock(MiniappEnrollmentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new MiniappEnrollmentController(enrollment))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(enrollment.enroll(any())).thenReturn(new LoginVO("rider-token", 42L, "RIDER", "原昵称"));
    }

    @Test
    void acceptsOnlyExplicitEnrollmentFieldsAndReturnsNewLogin() throws Exception {
        mvc.perform(post("/api/v1/rider/enrollment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"姓名\",\"plateNo\":\"贵D12345\",\"phoneCode\":\"phone-code\",\"agreed\":true}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.role").value("RIDER"))
                .andExpect(jsonPath("$.data.token").value("rider-token"))
                .andExpect(jsonPath("$.data.phoneCode").doesNotExist());
        verify(enrollment).enroll(new EnrollmentReq("姓名", "贵D12345", "phone-code", true));
    }

    @Test
    void rejectsEveryUnknownSelectorAndPrivilegeFieldIncludingNullValues() throws Exception {
        for (String field : new String[]{"mobile", "userId", "role", "status", "rideEnabled", "drivingEnabled", "source", "appId", "code"}) {
            mvc.perform(post("/api/v1/rider/enrollment").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"姓名\",\"phoneCode\":\"code\",\"agreed\":true,\"" + field + "\":null}"))
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(enrollment);
    }

    @Test
    void enforcesSixteenCharacterPlateBoundaryBeforeService() throws Exception {
        mvc.perform(post("/api/v1/rider/enrollment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"姓名\",\"phoneCode\":\"code\",\"agreed\":true,\"plateNo\":\"" + "A".repeat(17) + "\"}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(enrollment);
        mvc.perform(post("/api/v1/rider/enrollment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"姓名\",\"phoneCode\":\"code\",\"agreed\":true,\"plateNo\":\"" + "A".repeat(16) + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(enrollment).enroll(new EnrollmentReq("姓名", "A".repeat(16), "code", true));
    }

    @Test
    void rejectsCoercionMissingFieldsAndNonObjectBodies() throws Exception {
        for (String body : new String[]{"[]", "null", "{}",
                "{\"name\":3,\"phoneCode\":\"code\",\"agreed\":true}",
                "{\"name\":\"姓名\",\"phoneCode\":7,\"agreed\":true}",
                "{\"name\":\"姓名\",\"phoneCode\":\"code\",\"agreed\":\"true\"}",
                "{\"name\":\"姓名\",\"phoneCode\":\"code\",\"agreed\":true,\"plateNo\":[]}"}) {
            mvc.perform(post("/api/v1/rider/enrollment").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(enrollment);
    }
}
