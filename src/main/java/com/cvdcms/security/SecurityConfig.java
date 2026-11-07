package com.cvdcms.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final SessionAuthenticationFilter sessionAuthFilter;

    public SecurityConfig(SessionAuthenticationFilter sessionAuthFilter) {
        this.sessionAuthFilter = sessionAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Tích hợp Filter đồng bộ session User vào SecurityContext
                .addFilterBefore(sessionAuthFilter, UsernamePasswordAuthenticationFilter.class)

                // Tắt hoàn toàn hộp thoại HTTP Basic Auth (Sign in popup của trình duyệt)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())

                // Bật CSRF protection với CookieCsrfTokenRepository chuẩn (HttpOnly = false để client JS đọc được token nếu cần)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // Bỏ qua CSRF cho màn hình TV công cộng, WebSocket và các API nội bộ
                        .ignoringRequestMatchers(
                                "/reception/queue/tv/**",
                                "/patients/**",
                                "/portal/patients/**",
                                "/portal/appointments/api/**",
                                "/ws/**",
                                "/chat-websocket/**"
                        )
                        .ignoringRequestMatchers(request -> {
                            String path = request.getRequestURI().substring(request.getContextPath().length());
                            return path.startsWith("/api/") && !path.equals("/api/reception/check-ins")
                                    && !path.startsWith("/api/reception/check-ins/");
                        })
                )
                // Keep the browser's unload permission policy used by the shared layout.
                .headers(headers -> headers
                        .permissionsPolicy(permissions -> permissions
                                .policy("unload=*")
                        )
                )

                // Deny-by-default: Mặc định mọi request đều phải xác thực, chỉ public đúng các route công khai
                .authorizeHttpRequests(auth -> auth
                        // 1. Tài nguyên tĩnh
                        .requestMatchers(
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/uploads/**",
                                "/favicon.ico"
                        ).permitAll()

                        // 2. Các trang xác thực & tài khoản công khai
                        .requestMatchers(
                                "/",
                                "/login",
                                "/register",
                                "/forgot-password",
                                "/verify-otp",
                                "/reset-password",
                                "/logout"
                        ).permitAll()

                        // 3. Màn hình TV gọi số công cộng tại sảnh tiếp đón (FT10)
                        .requestMatchers(
                                "/tv-display",
                                "/public/queue/tv",
                                "/reception/queue/tv/**"
                        ).permitAll()

                        // 4. Các trang thông báo lỗi thân thiện và WebSocket
                        .requestMatchers(
                                "/friendly-error/**",
                                "/error",
                                "/access-denied",
                                "/ws/**",
                                "/chat-websocket/**"
                        ).permitAll()

                        // 5. Toàn bộ các route còn lại yêu cầu người dùng phải đăng nhập hợp lệ
                        .anyRequest().authenticated()
                )

                // Nếu chưa đăng nhập, chuyển hướng người dùng về trang đăng nhập /login
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
