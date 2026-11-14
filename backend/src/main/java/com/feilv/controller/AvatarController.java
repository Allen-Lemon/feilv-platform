package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.dto.UserProfileDtos.ProfileVO;
import com.feilv.service.AvatarStorageService;
import com.feilv.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@RestController
@RequiredArgsConstructor
public class AvatarController {
    private final UserProfileService profiles;
    private final AvatarStorageService storage;

    @PostMapping(value = "/api/v1/user/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ProfileVO> upload(@RequestParam("file") MultipartFile file) {
        long userId = UserContext.userId();
        profiles.me(userId);
        String storedPath = storage.store(file);
        try {
            return Result.ok(profiles.updateAvatar(userId, storedPath));
        } catch (RuntimeException e) {
            storage.discard(storedPath);
            throw e;
        }
    }

    /** 头像为公开展示资料；UUID不可枚举，不公开目录或原始文件名。 */
    @GetMapping("/api/v1/avatars/{name:[a-f0-9-]{36}\\.png}")
    public ResponseEntity<Resource> image(@PathVariable String name) {
        var path = storage.resolve(name);
        if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
    }
}
