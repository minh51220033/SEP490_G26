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
public class AdminServiceCatalogInterceptor
        implements HandlerInterceptor {

    private final UserRepository userRepository;

    public AdminServiceCatalogInterceptor(
            UserRepository userRepository
    ) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {

        HttpSession session = request.getSession(false);

        // Chưa đăng nhập: chuyển đến trang đăng nhập.
        if (session == null
                || !(session.getAttribute("loggedInUser")
                instanceof User)) {

            response.sendRedirect(
                    request.getContextPath() + "/login"
            );

            return false;
        }

        User user = (User) session.getAttribute("loggedInUser");

        if (user.getUserId() == null
                || user.getUserId() <= 0) {

            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Thông tin tài khoản không hợp lệ."
            );

            return false;
        }

        // Kiểm tra quyền hiện tại từ database.
        // Phát hiện cả tài khoản bị khóa hoặc bị thu hồi role Admin.
        boolean isActiveAdmin =
                userRepository.hasActiveAdminRole(
                        user.getUserId()
                );

        if (!isActiveAdmin) {
            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Bạn không có quyền quản lý danh mục dịch vụ."
            );

            return false;
        }

        return true;
    }
}