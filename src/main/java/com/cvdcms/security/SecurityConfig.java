
package com.cvdcms.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cấu hình Spring Security cho ứng dụng CV-DCMS.
 *
 * Trong giai đoạn hiện tại, UserController tự xử lý đăng nhập
 * và lưu tài khoản vào HttpSession với tên "loggedInUser".
 *
 * Do đó, chưa chuyển sang cơ chế formLogin của Spring Security
 * để tránh làm thay đổi luồng đăng nhập đang hoạt động.
 *
 * Quyền quản lý chi nhánh được kiểm tra tại AdminBranchInterceptor.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http)
            throws Exception {

        http
                // Tạm giữ CSRF ở trạng thái tắt để tương thích với
                // các biểu mẫu POST hiện tại.
                // Đây là giải pháp chuyển tiếp, chưa phù hợp cho production.
                .csrf(csrf -> csrf.disable())

                .authorizeHttpRequests(auth -> auth

                        // Cho phép Spring Security chuyển request đến MVC.
                        // AdminBranchInterceptor sẽ kiểm tra session và role
                        // trước khi BranchController xử lý request.
                        .requestMatchers("/admin/branches/**").permitAll()

                        // Các trang phục vụ đăng nhập và tài nguyên công khai.
                        .requestMatchers(
                                "/",
                                "/login",
                                "/register",
                                "/forgot-password",
                                "/verify-otp",
                                "/reset-password",
                                "/css/**",
                                "/js/**",
                                "/images/**"
                        ).permitAll()

                        // Giữ nguyên hành vi truy cập hiện tại cho những
                        // chức năng khác trong lúc chưa chuyển đổi bảo mật.
                        .anyRequest().permitAll()
                );

        return http.build();
    }

    /**
     * Bộ mã hóa mật khẩu BCrypt.
     * Giữ nguyên để tương thích với UserController đang dùng
     * passwordEncoder.matches(...) và passwordEncoder.encode(...).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
