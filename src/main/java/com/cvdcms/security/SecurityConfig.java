package com.cvdcms.security;

import com.cvdcms.entity.Role;
import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.util.ArrayList;
import java.util.List;

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
                                "/ws-chat/**",
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
                        // 1. Tài nguyên tĩnh công khai (Public static resources)
                        // Chỉ cho phép các thư mục an toàn không chứa dữ liệu y tế/tài chính cá nhân
                        .requestMatchers(
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/favicon.ico",
                                "/uploads/avatars/**",
                                "/uploads/news/**",
                                "/uploads/services/**",
                                "/uploads/images/**"
                        ).permitAll()

                        // 2. Các trang xác thực & tài khoản công khai
                        .requestMatchers(
                                "/",
                                "/home",
                                "/portal",
                                "/login",
                                "/register",
                                "/forgot-password",
                                "/verify-otp",
                                "/reset-password",
                                "/logout"
                        ).permitAll()

                        // 2b. Các trang thông tin công khai cho khách vãng lai (Guest chỉ có quyền XEM GET;
                        // các thao tác Đặt lịch, Bình luận POST, Thích POST bắt buộc phải đăng nhập)
                        .requestMatchers(HttpMethod.GET,
                                "/about",
                                "/contact",
                                "/branches",
                                "/privacy",
                                "/terms",
                                "/services",
                                "/services/**",
                                "/doctors",
                                "/doctors/**",
                                "/news",
                                "/news/*",
                                "/api/chat/context"
                        ).permitAll()

                        // 2c. Endpoint nạp ảnh NanoPix & Đặt lịch khám nhanh công khai từ Drawer (Kiểm tra lịch trực & Đặt lịch)
                        .requestMatchers(
                                "/api/xray/ingest",
                                "/api/public/booking",
                                "/api/public/booking/**"
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
                                "/ws-chat/**",
                                "/chat-websocket/**"
                        ).permitAll()

                        // 5. Toàn bộ các route còn lại (bao gồm /uploads/nanopix/**, /uploads/clinical_photos/**, /uploads/invoices/**, /uploads/signatures/**, /uploads/labo/**, /portal/appointments/**, POST /news/*/comments) yêu cầu đăng nhập hợp lệ
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

    /**
     * Khai báo UserDetailsService liên kết trực tiếp với UserRepository của hệ thống CV-DCMS.
     * Giải quyết tận gốc cảnh báo: UserDetailsServiceAutoConfiguration : Using generated security password
     * và ngăn chặn việc Spring Boot tự động khởi tạo tài khoản in-memory "user" mặc định trong cả dev lẫn production.
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy người dùng: " + username));

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            if (user.getRoles() != null) {
                for (Role role : user.getRoles()) {
                    if (role != null && role.getRoleName() != null) {
                        String rName = role.getRoleName().trim();
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + rName));
                        authorities.add(new SimpleGrantedAuthority(rName));
                    }
                }
            }
            if (authorities.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            }

            boolean active = Boolean.TRUE.equals(user.getIsActive());
            return org.springframework.security.core.userdetails.User.builder()
                    .username(user.getUsername())
                    .password(user.getPassword() != null ? user.getPassword() : "")
                    .disabled(!active)
                    .authorities(authorities)
                    .build();
        };
    }
}
