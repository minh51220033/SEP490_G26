package com.cvdcms.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cấu hình chỉ phục vụ tĩnh các thư mục tệp tin công khai an toàn (Public Uploads).
 * Các thư mục nhạy cảm (nanopix, clinical_photos, invoices, signatures, labo)
 * KHÔNG được cấu hình phục vụ tĩnh ở đây mà được kiểm soát phiên đăng nhập và phục vụ qua SecuredUploadResourceController.
 */
@Slf4j
@Configuration
public class UploadWebConfig implements WebMvcConfigurer {

    private static final List<String> REQUIRED_STORAGE_DIRECTORIES = List.of(
            "avatars", "news", "services", "images",
            "nanopix", "clinical_photos", "invoices", "signatures", "labo"
    );

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    @PostConstruct
    public void initStorageDirectories() {
        try {
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                Files.createDirectories(root);
                log.info("Đã khởi tạo thư mục gốc upload: {}", root);
            }
            for (String subDir : REQUIRED_STORAGE_DIRECTORIES) {
                Path dir = root.resolve(subDir).normalize();
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                    log.info("Đã khởi tạo thư mục con lưu trữ: {}", dir);
                }
            }
        } catch (IOException e) {
            log.warn("Không thể tự động khởi tạo các thư mục lưu trữ: {}", e.getMessage());
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();

        // 1. Ảnh đại diện người dùng công khai
        Path avatarPath = root.resolve("avatars").normalize();
        String avatarLocation = avatarPath.toUri().toString();
        if (!avatarLocation.endsWith("/")) avatarLocation += "/";
        registry.addResourceHandler("/uploads/avatars/**")
                .addResourceLocations(avatarLocation);

        // 2. Ảnh bìa tin tức bài viết công khai
        Path newsPath = root.resolve("news").normalize();
        String newsLocation = newsPath.toUri().toString();
        if (!newsLocation.endsWith("/")) newsLocation += "/";
        registry.addResourceHandler("/uploads/news/**")
                .addResourceLocations(newsLocation);

        // 3. Ảnh minh họa danh mục dịch vụ công khai
        Path servicesPath = root.resolve("services").normalize();
        String servicesLocation = servicesPath.toUri().toString();
        if (!servicesLocation.endsWith("/")) servicesLocation += "/";
        registry.addResourceHandler("/uploads/services/**")
                .addResourceLocations(servicesLocation);

        // 4. Ảnh giao diện trang chủ và không gian phòng khám công khai
        Path imagesPath = root.resolve("images").normalize();
        String imagesLocation = imagesPath.toUri().toString();
        if (!imagesLocation.endsWith("/")) imagesLocation += "/";
        registry.addResourceHandler("/uploads/images/**")
                .addResourceLocations(imagesLocation);
    }
}
