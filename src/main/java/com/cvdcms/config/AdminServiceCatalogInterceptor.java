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
 * [Phân hệ]: Danh mục dịch vụ (Quản trị)
 * [Vai trò]: Interceptor kiểm tra phiên đăng nhập và phân quyền truy cập các đường dẫn quản trị danh mục dịch vụ trước khi vào Controller.
 */
@Component
public class AdminServiceCatalogInterceptor
                implements HandlerInterceptor {

        private final UserRepository userRepository;

        public AdminServiceCatalogInterceptor(
                        UserRepository userRepository) {
                this.userRepository = userRepository;
        }

        /**
         * [Chức năng]: Chặn và xác thực quyền truy cập của người dùng trước khi xử lý các request quản lý danh mục dịch vụ.
         * [Mục đích]: Chuyển hướng về trang đăng nhập nếu chưa có phiên hợp lệ, trả về mã 403 nếu tài khoản không hợp lệ hoặc không có vai trò Admin hay các quyền quản lý danh mục (CATALOG_MANAGE, CATALOG_SERVICES, CATALOG_SERVICE_CATEGORIES).
         */
        @Override
        public boolean preHandle(
                        HttpServletRequest request,
                        HttpServletResponse response,
                        Object handler) throws IOException {

                HttpSession session = request.getSession(false);

                if (session == null
                                || !(session.getAttribute("loggedInUser") instanceof User)) {

                        response.sendRedirect(
                                        request.getContextPath() + "/login");

                        return false;
                }

                User user = (User) session.getAttribute("loggedInUser");

                if (user.getUserId() == null
                                || user.getUserId() <= 0) {

                        response.sendError(
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "Thông tin tài khoản không hợp lệ.");

                        return false;
                }

                boolean isAllowed = userRepository.hasActiveAdminRole(user.getUserId())
                                || userRepository.hasActivePermission(user.getUserId(), "CATALOG_MANAGE")
                                || userRepository.hasActivePermission(user.getUserId(), "CATALOG_SERVICES")
                                || userRepository.hasActivePermission(user.getUserId(), "CATALOG_SERVICE_CATEGORIES");

                if (!isAllowed) {
                        response.sendError(
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "Bạn không có quyền quản lý danh mục dịch vụ.");

                        return false;
                }

                return true;
        }
}
