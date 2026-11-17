package com.feilv.controller;

import com.feilv.common.BizException;
import com.feilv.common.GlobalExceptionHandler;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.service.AvatarStorageService;
import com.feilv.service.UserProfileService;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AvatarControllerTest {
    @TempDir Path directory;
    private final String url = "/api/v1/avatars/12345678-1234-1234-1234-123456789abc.png";

    @Test void authenticatedUploadOnlyUpdatesCurrentUserAndReturnsPersistentUrl() throws Exception {
        var profiles = mock(UserProfileService.class); var storage = mock(AvatarStorageService.class);
        var file = new MockMultipartFile("file", "temp.png", "image/png", new byte[]{1, 2});
        when(storage.store(any())).thenReturn(url);
        when(profiles.updateAvatar(42L, url)).thenReturn(new ProfileVO(42, "昵称", "USER", "", false, url));
        var mvc = MockMvcBuilders.standaloneSetup(new AvatarController(profiles, storage))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(TestAuth.interceptor()).build();
        mvc.perform(multipart("/api/v1/user/avatar").file(file)).andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles, storage);
        mvc.perform(multipart("/api/v1/user/avatar").file(file).param("userId", "99")
                        .header("Authorization", "Bearer " + TestAuth.userToken(42L, "USER")))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.avatarUrl").value(url));
        verify(profiles).me(42L); verify(profiles).updateAvatar(42L, url);
    }

    @Test void failedProfileWriteDiscardsOnlyTheJustUploadedFile() throws Exception {
        var profiles = mock(UserProfileService.class); var storage = mock(AvatarStorageService.class);
        when(storage.store(any())).thenReturn(url);
        when(profiles.updateAvatar(42L, url)).thenThrow(BizException.of(403, "账户冻结"));
        var mvc = MockMvcBuilders.standaloneSetup(new AvatarController(profiles, storage))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(TestAuth.interceptor()).build();
        mvc.perform(multipart("/api/v1/user/avatar").file(new MockMultipartFile("file", new byte[]{1}))
                        .header("Authorization", "Bearer " + TestAuth.userToken(42L, "USER")))
                .andExpect(jsonPath("$.code").value(403));
        verify(storage).discard(url);
    }

    @Test void publicImageRouteServesOnlyStoredPngWithoutBearerToken() throws Exception {
        var storage = new AvatarStorageService(directory.toString());
        var data = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", data);
        String stored = storage.store(new MockMultipartFile("file", data.toByteArray()));
        var mvc = MockMvcBuilders.standaloneSetup(new AvatarController(mock(UserProfileService.class), storage))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(TestAuth.interceptor()).build();
        mvc.perform(get(stored)).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get(url)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/avatars/private.txt")).andExpect(status().isNotFound());
    }
}
