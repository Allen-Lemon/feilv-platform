package com.feilv.controller;

import com.feilv.common.*;
import com.feilv.repository.*;
import com.feilv.service.*;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.feilv.interceptor.AuthInterceptor;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SystemAdminAuthorizationTest {
    @Test void adminWithoutSuperRoleCannotGrantItselfWechatSettings() throws Exception {
        var access = mock(WechatConfigAccess.class);
        var userRoles = mock(AdminUserRoleRepository.class);
        var roleMenus = mock(AdminRoleMenuRepository.class);
        var controller = new SystemAdminController(mock(AdminMenuRepository.class), mock(AdminRoleRepository.class),
                mock(AdminAccountRepository.class), userRoles, roleMenus, mock(UserRepository.class), mock(AuditService.class), access);
        doThrow(BizException.of(403, "仅超级管理员可维护账号、角色和授权")).when(access).requireSystemAdmin();
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(TestAuth.interceptor()).build();
        mvc.perform(post("/api/v1/admin/system/users/1/roles").header("Authorization", "Bearer " + JwtUtil.create(1L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleIds\":[1]}"))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/v1/admin/system/roles/1/menus").header("Authorization", "Bearer " + JwtUtil.create(1L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"menuIds\":[1]}"))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(userRoles, roleMenus);
    }
}
