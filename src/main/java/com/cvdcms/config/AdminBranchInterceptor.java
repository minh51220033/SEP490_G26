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
 * Bảo vệ các URL quản lý chi nhánh.
 */
@Component
public class AdminBranchInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    public AdminBranchInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws IOException {

        HttpSession session = request.getSession(false);

        if (session == null ||
                !(session.getAttribute("loggedInUser") instanceof User)) {

            response.sendRedirect(
                    request.getContextPath() + "/login"
            );
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

        boolean isAllowed = userRepository.hasActiveAdminRole(sessionUser.getUserId())
                || userRepository.hasActivePermission(sessionUser.getUserId(), "BRANCH_MANAGE");

        if (!isAllowed) {
            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Tài khoản không có quyền quản lý chi nhánh."
            );
            return false;
        }

        return true;
    }
}
