package com.cvdcms.config;

import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;

@Component
public class FinanceAccessInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    public FinanceAccessInterceptor(
            UserRepository userRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository
    ) {
        this.userRepository = userRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {

        String uri = request.getRequestURI();
        boolean isApi = uri.contains("/api/") || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));

        HttpSession session = request.getSession(false);

        // 1. Kiểm tra đăng nhập
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User sessionUser)) {
            if (isApi) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Vui lòng đăng nhập để truy cập tài nguyên tài chính.");
            } else {
                response.sendRedirect(request.getContextPath() + "/login");
            }
            return false;
        }

        Long userId = sessionUser.getUserId();
        if (userId == null || userId <= 0) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Thông tin tài khoản không hợp lệ.");
            return false;
        }

        // 2. Kiểm tra tài khoản còn hoạt động trong Database
        User dbUser = userRepository.findById(userId).orElse(null);
        if (dbUser == null || !Boolean.TRUE.equals(dbUser.getIsActive()) || !"ACTIVE".equalsIgnoreCase(dbUser.getStatus())) {
            if (isApi) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Tài khoản không tồn tại hoặc đã bị khóa.");
            } else {
                response.sendRedirect(request.getContextPath() + "/login");
            }
            return false;
        }

        // 3. Kiểm tra vai trò chính xác từ database: Chỉ chấp nhận ADMIN hoặc KE_TOAN
        boolean isAdmin = userRepository.hasActiveAdminRole(userId);
        boolean isFinanceRole = isAdmin || userRepository.hasActiveFinanceRole(userId);

        if (!isFinanceRole) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Bạn không có quyền truy cập phân hệ Tài chính & Kế toán.");
            return false;
        }

        // 4. Kiểm tra phân công chi nhánh cho non-admin (Kế toán viên)
        if (isAdmin) {
            request.setAttribute("isFinanceAdmin", Boolean.TRUE);
        } else {
            request.setAttribute("isFinanceAdmin", Boolean.FALSE);
            List<Long> activeBranchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
            if (activeBranchIds.isEmpty()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tài khoản kế toán chưa được phân công chi nhánh hoạt động hợp lệ.");
                return false;
            }
            request.setAttribute("currentUserActiveBranchIds", activeBranchIds);

            // Kiểm tra tham số branchId nếu có trên URL hoặc form
            String branchIdParam = request.getParameter("branchId");
            if (branchIdParam != null && !branchIdParam.trim().isEmpty()) {
                try {
                    Long requestedBranchId = Long.parseLong(branchIdParam.trim());
                    if (!activeBranchIds.contains(requestedBranchId)) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Bạn không có quyền truy cập dữ liệu tại chi nhánh ID: " + requestedBranchId);
                        return false;
                    }
                } catch (NumberFormatException ignored) {
                    // Để controller validate định dạng
                }
            }
        }

        return true;
    }
}
