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

        // 3. Kiểm tra vai trò hoặc quyền hạn phân hệ Tài chính từ database
        boolean isAdmin = userRepository.hasActiveAdminRole(userId);
        if (!isAdmin && session != null) {
            if (Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
                isAdmin = true;
            } else {
                Object rolesObj = session.getAttribute("roleNames");
                if (rolesObj instanceof java.util.Collection<?> col) {
                    isAdmin = col.stream()
                            .filter(java.util.Objects::nonNull)
                            .map(Object::toString)
                            .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.trim()) || "ROLE_ADMIN".equalsIgnoreCase(r.trim()));
                }
            }
        }
        boolean hasFinancePermission = userRepository.hasActivePermission(userId, "INVOICE_VIEW")
                || userRepository.hasActivePermission(userId, "INVOICE_CREATE")
                || userRepository.hasActivePermission(userId, "PAYMENT_COLLECT")
                || userRepository.hasActivePermission(userId, "REFUND_MANAGE")
                || userRepository.hasActivePermission(userId, "FINANCE_DASHBOARD")
                || userRepository.hasActivePermission(userId, "FINANCE_DEBT")
                || userRepository.hasActivePermission(userId, "FINANCE_DEPOSIT")
                || userRepository.hasActivePermission(userId, "FINANCE_EXPENSES")
                || userRepository.hasActivePermission(userId, "FINANCE_PAYROLL")
                || userRepository.hasActivePermission(userId, "FINANCE_PAYROLL_ADVANCES")
                || userRepository.hasActivePermission(userId, "FINANCE_COMMISSION_CONFIG")
                || userRepository.hasActivePermission(userId, "FINANCE_OWNER_DISTRIBUTIONS");
        boolean isKeToan = false;
        if (session != null) {
            Object rolesObj = session.getAttribute("roleNames");
            if (rolesObj instanceof java.util.Collection<?> col) {
                isKeToan = col.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(Object::toString)
                        .anyMatch(r -> {
                            String role = r != null ? r.trim().toUpperCase() : "";
                            return role.equals("KE_TOAN") || role.equals("ROLE_KE_TOAN")
                                    || role.equals("ACCOUNTANT") || role.equals("ROLE_ACCOUNTANT");
                        });
            }
        }
        if (!isKeToan) {
            List<String> userRoles = userRepository.findActiveRoleNamesByUserId(userId);
            if (userRoles != null) {
                isKeToan = userRoles.stream().anyMatch(r -> {
                    String role = r != null ? r.trim().toUpperCase() : "";
                    return role.equals("KE_TOAN") || role.equals("ROLE_KE_TOAN")
                            || role.equals("ACCOUNTANT") || role.equals("ROLE_ACCOUNTANT");
                });
            }
        }
        boolean isFinanceRole = isAdmin || isKeToan || hasFinancePermission || userRepository.hasActiveFinanceRole(userId);

        if (!isFinanceRole) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Bạn không có quyền truy cập phân hệ Tài chính & Kế toán.");
            return false;
        }

        // 4. Admin và Kế toán có toàn quyền xem và quản lý tài chính toàn bộ hệ thống
        if (isAdmin || isKeToan) {
            request.setAttribute("isFinanceAdmin", Boolean.TRUE);
            return true;
        } else {
            List<Long> activeBranchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
            if ((activeBranchIds == null || activeBranchIds.isEmpty())
                    && dbUser.getBranch() != null && dbUser.getBranch().getBranchId() != null) {
                activeBranchIds = List.of(dbUser.getBranch().getBranchId());
            }
            if (activeBranchIds == null || activeBranchIds.isEmpty()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tài khoản nhân viên chưa được phân công chi nhánh làm việc hợp lệ.");
                return false;
            }
            request.setAttribute("isFinanceAdmin", Boolean.FALSE);
            request.setAttribute("currentUserActiveBranchIds", activeBranchIds);

            // Chặn kế toán can thiệp cấu hình hoa hồng hoặc duyệt/từ chối tạm ứng nếu chưa được cấp quyền
            if ((uri.contains("/payroll/commission-config") || uri.contains("/payroll/config"))
                    && !userRepository.hasActivePermission(userId, "FINANCE_COMMISSION_CONFIG")) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Chỉ Quản trị viên hoặc nhân sự được cấp quyền mới có thể chỉnh sửa cấu hình hoa hồng.");
                return false;
            }
            if (uri.matches(".*/payroll/advances/\\d+/(approve|reject).*")
                    && !userRepository.hasActivePermission(userId, "FINANCE_PAYROLL_ADVANCES")) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Chỉ Quản trị viên hoặc nhân sự được cấp quyền mới có thể duyệt tạm ứng lương.");
                return false;
            }

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

            // Kiểm tra branchId trên Path variable dạng /branches/{branchId}
            java.util.regex.Matcher branchPathMatcher = java.util.regex.Pattern.compile(".*/branches/(\\d+).*").matcher(uri);
            if (branchPathMatcher.matches()) {
                try {
                    Long pathBranchId = Long.parseLong(branchPathMatcher.group(1));
                    if (!activeBranchIds.contains(pathBranchId)) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Bạn không có quyền truy cập dữ liệu tại chi nhánh ID: " + pathBranchId);
                        return false;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        return true;
    }
}
