package com.cvdcms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Đăng ký các interceptor dùng để bảo vệ đường dẫn trong ứng dụng.
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
    public void addInterceptors(InterceptorRegistry registry) {

        // Chỉ tài khoản Admin hợp lệ mới được quản lý chi nhánh.
        registry.addInterceptor(adminBranchInterceptor)
                .addPathPatterns("/admin/branches/**");

        // Chỉ tài khoản Admin hoặc Lễ tân hợp lệ mới được vào khu vực
        // quản lý tài khoản. Quyền của từng thao tác sẽ được kiểm tra thêm.
        registry.addInterceptor(accountManagementInterceptor)
                .addPathPatterns("/accounts/**");
    }
}