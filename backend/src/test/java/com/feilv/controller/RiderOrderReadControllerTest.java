package com.feilv.controller;

import com.feilv.common.GlobalExceptionHandler;
import com.feilv.common.UserContext;
import com.feilv.dto.ApiDtos.OrderVO;
import com.feilv.service.RiderOrderReadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Controller contract only; the service independently enforces source, live rider and carrier. */
class RiderOrderReadControllerTest {
    private final RiderOrderReadService reads = mock(RiderOrderReadService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new RiderOrderReadController(reads))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContext.set(new UserContext.CurrentUser(42L, "RIDER", "RIDER", "rider-app"));
        when(reads.detailForRider(42L, 99L)).thenReturn(new OrderVO(99L, "O99", "BUY", "ACCEPTED", "已接单",
                "起点", "终点", "物品", 2000, 1800, 7L, "1234", null, null, null, null, false, "DELIVERY", 3500));
    }

    @AfterEach
    void clearContext() { UserContext.clear(); }

    @Test
    void narrowDetailKeepsOrderFieldsAndUsesOnlyCurrentAccount() throws Exception {
        mvc.perform(get("/api/v1/rider/orders/99").param("userId", "500"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.id").value(99))
                .andExpect(jsonPath("$.data.owner").value(false))
                .andExpect(jsonPath("$.data.goodsBudgetFen").value(3500))
                .andExpect(jsonPath("$.data.realFeeFen").value(1800))
                .andExpect(jsonPath("$.data.fromPhone").doesNotExist());
        mvc.perform(get("/api/v1/rider/orders/99/custom-task").param("userId", "500"))
                .andExpect(jsonPath("$.code").value(0));
        verify(reads).detailForRider(42L, 99L);
        verify(reads).taskForRider(42L, 99L);
        verifyNoMoreInteractions(reads);
    }

    @Test
    void missingCurrentAccountNeverCallsReadService() throws Exception {
        UserContext.clear();
        mvc.perform(get("/api/v1/rider/orders/99")).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/v1/rider/orders/99/custom-task")).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(reads);
    }
}
