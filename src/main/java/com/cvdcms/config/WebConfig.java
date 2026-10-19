package com.cvdcms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminBranchInterceptor adminBranchInterceptor;
    private final AccountManagementInterceptor accountManagementInterceptor;

    public WebConfig(
            AdminBranchInterceptor adminBranchInterceptor,
            AccountManagementInterceptor accountManagementInterceptor
    ) {
        this.adminBranchInterceptor = adminBranchInterceptor;
        this.accountManagementInterceptor = accountManagementInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Ép hệ thống nhận toàn bộ file tĩnh từ thư mục static chuẩn
        registry.addResourceHandler("/**")
                .addResourceLocations(
                        "classpath:/static/",
                        "classpath:/public/",
                        "classpath:/resources/",
                        "classpath:/META-INF/resources/"
                );

        // Ánh xạ thư mục uploads bên ngoài ổ cứng
        Path uploadDir = Paths.get("uploads").toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadDir.toUri().toString());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String[] staticFilesExclusions = {
                "/css/**",
                "/js/**",
                "/images/**",
                "/uploads/**",
                "/favicon.ico"
        };

        registry.addInterceptor(adminBranchInterceptor)
                .addPathPatterns("/admin/branches/**")
                .excludePathPatterns(staticFilesExclusions);

        registry.addInterceptor(accountManagementInterceptor)
                .addPathPatterns("/accounts/**")
                .excludePathPatterns(staticFilesExclusions);
    }
}