package com.cvdcms.config;

import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class SupplierManagementInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    public SupplierManagementInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {

        // API trả về JSON/status HTTP, không chuyển hướng sang trang đăng nhập.
        HttpSession session = request.getSession(false);

        // Chưa đăng nhập hoặc session không có User hợp lệ.
        if (session == null
                || !(session.getAttribute("loggedInUser") instanceof User)) {

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"message\":\"Vui lòng đăng nhập để sử dụng chức năng này.\"}"
            );
            return false;
        }

        User sessionUser = (User) session.getAttribute("loggedInUser");

        // Kiểm tra ID tài khoản.
        if (sessionUser.getUserId() == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"message\":\"Thông tin tài khoản không hợp lệ.\"}"
            );
            return false;
        }

        // Kiểm tra quyền mới nhất từ database ở mỗi request.
        boolean hasPermission =
                userRepository.hasActiveSupplierManagementRole(
                        sessionUser.getUserId()
                );

        if (!hasPermission) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"message\":\"Bạn không có quyền quản lý nhà cung cấp.\"}"
            );
            return false;
        }

        return true;
    }
}