package com.feilv.service;

import com.feilv.common.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AvatarStorageServiceTest {
    @TempDir Path directory;

    @Test void validatesContentIgnoresClaimedFilenameAndReencodesToSmallPng() throws Exception {
        var storage = new AvatarStorageService(directory.toString());
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
        String url = storage.store(new MockMultipartFile("file", "../../malicious.html", "text/html", bytes.toByteArray()));
        assertTrue(url.matches("/api/v1/avatars/[a-f0-9-]{36}\\.png"));
        Path saved = storage.resolve(url.substring(url.lastIndexOf('/') + 1));
        assertTrue(saved.startsWith(directory));
        var image = ImageIO.read(saved.toFile());
        assertEquals(512, image.getWidth()); assertEquals(409, image.getHeight());
        storage.discard(url); assertFalse(Files.exists(saved));
    }

    @Test void rejectsFakeImagesUnsupportedImagesEmptyOversizeAndTraversal() throws Exception {
        var storage = new AvatarStorageService(directory.toString());
        assertThrows(BizException.class, () -> storage.store(new MockMultipartFile("file", "x.png", "image/png", "<script>alert(1)</script>".getBytes())));
        assertThrows(BizException.class, () -> storage.store(new MockMultipartFile("file", new byte[0])));
        assertThrows(BizException.class, () -> storage.store(new MockMultipartFile("file", new byte[5 * 1024 * 1024 + 1])));
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "gif", bytes);
        assertThrows(BizException.class, () -> storage.store(new MockMultipartFile("file", bytes.toByteArray())));
        assertThrows(BizException.class, () -> storage.resolve("../private.png"));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
