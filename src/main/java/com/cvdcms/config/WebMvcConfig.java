package com.cvdcms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Đăng ký các interceptor bảo vệ đường dẫn và cấu hình tài nguyên tĩnh trong ứng dụng.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminBranchInterceptor adminBranchInterceptor;
    private final AccountManagementInterceptor accountManagementInterceptor;

    public WebMvcConfig(
            AdminBranchInterceptor adminBranchInterceptor,
            AccountManagementInterceptor accountManagementInterceptor
    ) {
        this.adminBranchInterceptor = adminBranchInterceptor;
        this.accountManagementInterceptor = accountManagementInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 1. Cấu hình ánh xạ thư mục uploads lưu trữ ngoài ổ cứng (cho avatar, file đính kèm...)
        Path uploadDir = Paths.get("uploads").toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadDir.toUri().toString());

        // 2. Cấu hình rõ ràng cho các thư mục static mặc định (đề phòng trường hợp Spring Boot không tự nhận)
        registry.addResourceHandler("/css/**").addResourceLocations("classpath:/static/css/");
        registry.addResourceHandler("/js/**").addResourceLocations("classpath:/static/js/");
        registry.addResourceHandler("/images/**").addResourceLocations("classpath:/static/images/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // Danh sách các đường dẫn tài nguyên tĩnh cần loại trừ khỏi tất cả các interceptor
        String[] staticFilesExclusions = {
                "/css/**",
                "/js/**",
                "/images/**",
                "/uploads/**",
                "/favicon.ico"
        };

        // Chỉ tài khoản Admin hợp lệ mới được quản lý chi nhánh.
        registry.addInterceptor(adminBranchInterceptor)
                .addPathPatterns("/admin/branches/**")
                .excludePathPatterns(staticFilesExclusions);

        // Chỉ tài khoản Admin hoặc Lễ tân hợp lệ mới được vào khu vực quản lý tài khoản.
        registry.addInterceptor(accountManagementInterceptor)
                .addPathPatterns("/accounts/**")
                .excludePathPatterns(staticFilesExclusions);
    }
}