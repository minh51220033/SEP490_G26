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
 * Spring MVC {@link HandlerInterceptor} bảo vệ các API Quản lý Nhà cung cấp ({@code /api/suppliers/**}).
 *
 * <p><b>Chức năng:</b> Chặn mọi request trước khi vào {@link com.cvdcms.controller.SupplierController},
 * xác thực phiên đăng nhập trong {@link HttpSession} và kiểm tra quyền hạn thời gian thực từ CSDL
 * (trả về JSON kèm mã HTTP {@code 401}/{@code 403}, không redirect sang trang HTML {@code /login}).</p>
 *
 * <p><b>Được đăng ký tại:</b> Cấu hình WebMvc ({@code WebConfig}) áp dụng cho đường dẫn {@code /api/suppliers/**}.</p>
 */
@Component
public class SupplierManagementInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    public SupplierManagementInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Kiểm tra phiên đăng nhập và quyền quản lý nhà cung cấp trước khi thực thi controller.
     *
     * <p><b>Xử lý từng bước:</b>
     * <ol>
     *   <li>Lấy {@link HttpSession} hiện tại (không tạo mới); nếu thiếu hoặc không chứa {@code loggedInUser}
     *   thuộc kiểu {@link User} → trả HTTP {@code 401 Unauthorized} dạng JSON và dừng request.</li>
     *   <li>Nếu {@code sessionUser.getUserId() == null} → trả HTTP {@code 403 Forbidden}.</li>
     *   <li>Truy vấn trực tiếp CSDL qua {@link UserRepository} để xác minh người dùng có ít nhất một trong:
     *   role quản lý nhà cung cấp đang hoạt động ({@code hasActiveSupplierManagementRole}),
     *   quyền {@code SUPPLIER_MANAGE}, hoặc quyền {@code LABO_SUPPLIERS}. Nếu không → trả HTTP {@code 403 Forbidden}.</li>
     * </ol>
     * </p>
     *
     * @param request  HTTP request đến
     * @param response HTTP response để ghi lỗi JSON nếu bị chặn
     * @param handler  handler đích
     * @return {@code true} nếu hợp lệ và cho phép tiếp tục; {@code false} nếu đã chặn và ghi response lỗi
     * @throws IOException nếu có lỗi khi ghi JSON ra {@code response.getWriter()}
     */
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
                userRepository.hasActiveSupplierManagementRole(sessionUser.getUserId())
                || userRepository.hasActivePermission(sessionUser.getUserId(), "SUPPLIER_MANAGE")
                || userRepository.hasActivePermission(sessionUser.getUserId(), "LABO_SUPPLIERS");

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