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
    private final SupplierManagementInterceptor supplierManagementInterceptor; // Thêm biến này

    public WebConfig(
            AdminBranchInterceptor adminBranchInterceptor,
            AccountManagementInterceptor accountManagementInterceptor,
            SupplierManagementInterceptor supplierManagementInterceptor // Thêm vào đây
    ) {
        this.adminBranchInterceptor = adminBranchInterceptor;
        this.accountManagementInterceptor = accountManagementInterceptor;
        this.supplierManagementInterceptor = supplierManagementInterceptor; // Gán giá trị
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(
                        "classpath:/static/",
                        "classpath:/public/",
                        "classpath:/resources/",
                        "classpath:/META-INF/resources/"
                );

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

        // Chỉ ADMIN được quản lý chi nhánh.
        registry.addInterceptor(adminBranchInterceptor)
                .addPathPatterns("/admin/branches/**")
                .excludePathPatterns(staticFilesExclusions);

        // Giữ nguyên phân quyền quản lý tài khoản hiện tại.
        registry.addInterceptor(accountManagementInterceptor)
                .addPathPatterns("/accounts/**")
                .excludePathPatterns(staticFilesExclusions);

        // Bảo vệ API quản lý nhà cung cấp dùng chung.
        registry.addInterceptor(supplierManagementInterceptor)
                .addPathPatterns(
                        "/api/suppliers",
                        "/api/suppliers/**"
                )
                .excludePathPatterns(staticFilesExclusions);
    }
}