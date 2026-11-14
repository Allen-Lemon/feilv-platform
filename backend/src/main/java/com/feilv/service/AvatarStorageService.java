package com.feilv.service;

import com.feilv.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 只接收小体积 JPEG/PNG，限制解码像素并重新编码，文件名由服务端生成。 */
@Service
public class AvatarStorageService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private final Path directory;

    public AvatarStorageService(@Value("${feilv.avatar-directory:./data/avatars}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw BizException.of(400, "请选择不超过5MB的PNG或JPEG头像");
        }
        try (var source = file.getInputStream(); var input = ImageIO.createImageInputStream(source)) {
            if (input == null) throw invalidImage();
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            var reader = readers.next();
            BufferedImage image;
            try {
                String format = reader.getFormatName();
                if (!"png".equalsIgnoreCase(format) && !"jpeg".equalsIgnoreCase(format)) throw invalidImage();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 16_000_000) {
                    throw BizException.of(400, "头像像素过大，请压缩后重试");
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
            if (image == null) throw invalidImage();
            double scale = Math.min(1d, 512d / Math.max(image.getWidth(), image.getHeight()));
            var normalized = new BufferedImage(Math.max(1, (int) (image.getWidth() * scale)),
                    Math.max(1, (int) (image.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
            var graphics = normalized.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image, 0, 0, normalized.getWidth(), normalized.getHeight(), null);
            } finally {
                graphics.dispose();
                image.flush();
            }
            Files.createDirectories(directory);
            String name = UUID.randomUUID() + ".png";
            Path destination = directory.resolve(name);
            try {
                if (!ImageIO.write(normalized, "png", destination.toFile())) throw new IOException("PNG writer unavailable");
            } catch (IOException e) {
                Files.deleteIfExists(destination);
                throw e;
            } finally {
                normalized.flush();
            }
            return "/api/v1/avatars/" + name;
        } catch (IOException | IllegalArgumentException e) {
            throw BizException.of(400, "头像处理失败，请选择有效的PNG或JPEG图片重试");
        }
    }

    public Path resolve(String name) {
        if (name == null || !name.matches("[a-f0-9-]{36}\\.png")) throw BizException.of(404, "头像不存在");
        return directory.resolve(name);
    }

    /** 仅清理本次上传、尚未成功关联用户的文件。 */
    public void discard(String storedPath) {
        String name = storedPath.substring(storedPath.lastIndexOf('/') + 1);
        try { Files.deleteIfExists(resolve(name)); }
        catch (IOException ignored) { /* 不覆盖原始业务异常；可由运维清理未引用文件。 */ }
    }

    private static BizException invalidImage() {
        return BizException.of(400, "头像仅支持有效的PNG或JPEG图片");
    }
}
