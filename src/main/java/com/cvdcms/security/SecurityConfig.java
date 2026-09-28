package com.cvdcms.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                // Tạm thời tắt CSRF để các form POST không bị 403
                .csrf(csrf -> csrf.disable())

                .authorizeHttpRequests(auth -> auth

                        // Trang quản lý tài khoản:
                        // Tạm thời cho phép Security đi qua,
                        // quyền ADMIN / LE_TAN sẽ kiểm tra ở AccountController
                        .requestMatchers("/accounts/**").permitAll()

                        // Các trang công khai
                        .requestMatchers(
                                "/",
                                "/login",
                                "/register",
                                "/forgot-password",
                                "/verify-otp",
                                "/css/**",
                                "/js/**"
                        ).permitAll()

                        // Các URL còn lại
                        .anyRequest().permitAll()
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}