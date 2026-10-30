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

        HttpSession session = request.getSession(false);

        if (session == null
                || !(session.getAttribute("loggedInUser") instanceof User)) {

            response.sendRedirect(request.getContextPath() + "/login");
            return false;
        }

        User sessionUser = (User) session.getAttribute("loggedInUser");

        if (sessionUser.getUserId() == null) {
            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Thông tin tài khoản không hợp lệ."
            );
            return false;
        }

        String uri = request.getRequestURI();
        boolean hasPermission = userRepository.hasActiveAdminRole(sessionUser.getUserId())
                || (uri != null && uri.contains("/accounts/roles")
                ? userRepository.hasActivePermission(sessionUser.getUserId(), "ROLE_MANAGE")
                : userRepository.hasActivePermission(sessionUser.getUserId(), "ACCOUNT_MANAGE"));

        if (!hasPermission) {
            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Bạn không có quyền quản lý tài khoản."
            );
            return false;
        }

        return true;
    }
}
