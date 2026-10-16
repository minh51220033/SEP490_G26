
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
 *
 * Quyền Admin được kiểm tra lại từ database cho mỗi request,
 * không chỉ dựa vào danh sách role lưu trong session lúc đăng nhập.
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

        // Không tạo session mới khi request chưa có session đăng nhập.
        HttpSession session = request.getSession(false);

        // Chưa đăng nhập hoặc session không chứa đối tượng User hợp lệ.
        if (session == null ||
                !(session.getAttribute("loggedInUser") instanceof User)) {

            response.sendRedirect(
                    request.getContextPath() + "/login"
            );
            return false;
        }

        // Lấy tài khoản đã được lưu khi đăng nhập.
        User sessionUser = (User) session.getAttribute("loggedInUser");

        // Kiểm tra ID để tránh truy vấn với tài khoản không hợp lệ.
        if (sessionUser.getUserId() == null) {
            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Thông tin tài khoản không hợp lệ."
            );
            return false;
        }

        // Đọc quyền hiện tại từ database.
        // Nếu tài khoản bị khóa hoặc đã bị gỡ quyền Admin,
        // truy vấn sẽ trả về false.
        boolean isActiveAdmin =
                userRepository.hasActiveAdminRole(sessionUser.getUserId());

        if (!isActiveAdmin) {
            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Tài khoản không có quyền quản lý chi nhánh."
            );
            return false;
        }

        // Chỉ cho phép tiếp tục khi tài khoản và role Admin còn hoạt động.
        return true;
    }
}
