package com.cvdcms.config;

import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Bảo vệ các đường dẫn quản lý tài khoản /accounts/**.
 *
 * Chỉ cho phép tài khoản:
 * - Đã đăng nhập.
 * - Đang hoạt động trong database.
 * - Có vai trò ADMIN hoặc LE_TAN đang hoạt động.
 *
 * Quyền chi tiết của từng thao tác sẽ được kiểm tra riêng.
 */
@Component
public class AccountManagementInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    public AccountManagementInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {

        // Bước 1: Lấy session hiện tại, không tự tạo session mới.
        HttpSession session = request.getSession(false);

        // Chưa đăng nhập hoặc session không chứa User hợp lệ.
        if (session == null
                || !(session.getAttribute("loggedInUser") instanceof User)) {

            response.sendRedirect(request.getContextPath() + "/login");
            return false;
        }

        User sessionUser = (User) session.getAttribute("loggedInUser");

        // Bước 2: Kiểm tra ID tài khoản trong session.
        if (sessionUser.getUserId() == null) {
            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Thông tin tài khoản không hợp lệ."
            );
            return false;
        }

        // Bước 3: Kiểm tra quyền từ database ở mỗi request.
        // Việc này giúp phát hiện khi tài khoản bị khóa hoặc bị thu hồi vai trò.
        boolean hasPermission =
                userRepository.hasActiveAccountManagementRole(
                        sessionUser.getUserId()
                );

        if (!hasPermission) {
            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Bạn không có quyền quản lý tài khoản."
            );
            return false;
        }

        // Đủ điều kiện đi tiếp đến Controller.
        return true;
    }
}