package com.cvdcms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadWebConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("1. Khởi tạo đầy đủ 8 thư mục lưu trữ hợp lệ khi ứng dụng khởi động")
    void initStorageDirectories_createsRequiredDirectories() {
        UploadWebConfig config = new UploadWebConfig();
        ReflectionTestUtils.setField(config, "uploadDir", tempDir.toString());

        config.initStorageDirectories();

        String[] expectedDirs = {
                "avatars", "news", "services", "images",
                "nanopix", "clinical_photos", "invoices", "signatures", "labo"
        };

        for (String dirName : expectedDirs) {
            Path subDir = tempDir.resolve(dirName);
            assertTrue(Files.exists(subDir), "Thư mục phải tồn tại: " + dirName);
            assertTrue(Files.isDirectory(subDir), "Phải là thư mục: " + dirName);
            assertTrue(Files.isWritable(subDir), "Thư mục phải có quyền ghi: " + dirName);
        }
    }

    @Test
    @DisplayName("2. Đăng ký ResourceHandler chỉ cho 3 danh mục công khai (avatars, news, services)")
    void addResourceHandlers_registersOnlyPublicCategories() {
        UploadWebConfig config = new UploadWebConfig();
        ReflectionTestUtils.setField(config, "uploadDir", tempDir.toString());

        ResourceHandlerRegistry registry = mock(ResourceHandlerRegistry.class, RETURNS_DEEP_STUBS);
        config.addResourceHandlers(registry);

        verify(registry).addResourceHandler("/uploads/avatars/**");
        verify(registry).addResourceHandler("/uploads/news/**");
        verify(registry).addResourceHandler("/uploads/services/**");
        verify(registry).addResourceHandler("/uploads/images/**");
        verifyNoMoreInteractions(registry);
    }
}
