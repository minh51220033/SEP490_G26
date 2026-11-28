package com.cvdcms.controller;

import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * [Phân hệ]: Tài chính & Kế toán - Điều hướng theo phân quyền
 * [Vai trò]: Controller điều hướng người dùng khi bấm vào cổng vào chung `/finance`, `/finance/customer` hoặc `/finance/clinic` tới màn hình tài chính phù hợp nhất với quyền hiện có.
 */
@Controller
public class FinanceRedirectController {

    private final UserRepository userRepository;

    /**
     * [Hàm hỗ trợ]: Khởi tạo controller với `UserRepository` để truy vấn dự phòng vai trò và mã quyền khi session chưa lưu đầy đủ.
     */
    public FinanceRedirectController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * [Chức năng]: Điều hướng từ `/finance` hoặc `/finance/customer` đến màn hình thu ngân/tài chính đầu tiên mà người dùng có quyền truy cập.
     * [Mục đích]: Tránh lỗi 403 khi nhân sự chỉ được cấp một phần quyền tài chính; kiểm tra quyền theo thứ tự ưu tiên nhóm Khách hàng (Tổng quan -> Hóa đơn -> Phiếu thu -> Công nợ -> Tạm ứng -> Hoàn tiền) rồi fallback sang nhóm tài chính Phòng khám (Chi phí -> Bảng lương -> Tạm ứng lương -> Hoa hồng -> Chia cổ tức).
     */
    @GetMapping({"/finance", "/finance/customer"})
    public String redirectFinance(HttpServletRequest request, HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User user)) {
            return "redirect:/login";
        }

        boolean isAdmin = false;
        Object sessIsAdmin = session.getAttribute("isAdmin");
        if (sessIsAdmin instanceof Boolean b) {
            isAdmin = b;
        } else if (user.getUserId() != null) {
            isAdmin = userRepository.hasActiveAdminRole(user.getUserId());
        }

        List<String> roleNames = extractList(session.getAttribute("roleNames"));
        if (roleNames.isEmpty() && user.getUserId() != null) {
            roleNames = userRepository.findActiveRoleNamesByUserId(user.getUserId());
        }

        List<String> perms = extractList(session.getAttribute("permissionCodes"));
        if (perms.isEmpty() && user.getUserId() != null) {
            perms = userRepository.findActivePermissionCodesByUserId(user.getUserId());
        }

        if (!isAdmin) {
            isAdmin = roleNames.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r) || "ROLE_ADMIN".equalsIgnoreCase(r));
        }

        // 1. Nhóm Khách hàng (Thu ngân):
        if (isAdmin || perms.contains("FINANCE_DASHBOARD")) {
            return "redirect:/payments/dashboard";
        }
        if (perms.contains("INVOICE_CREATE") || perms.contains("INVOICE_VIEW")) {
            return "redirect:/invoices";
        }
        if (perms.contains("PAYMENT_COLLECT")) {
            return "redirect:/payments";
        }
        if (perms.contains("FINANCE_DEBT")) {
            return "redirect:/payments/debts";
        }
        if (perms.contains("FINANCE_DEPOSIT")) {
            return "redirect:/payments/deposits";
        }
        if (perms.contains("REFUND_MANAGE")) {
            return "redirect:/payments/refunds";
        }

        // 2. Nhóm Phòng khám (fallback theo quyền từng tab):
        if (perms.contains("FINANCE_EXPENSES")) {
            return "redirect:/expenses";
        }
        if (perms.contains("FINANCE_PAYROLL")) {
            return "redirect:/payroll";
        }
        if (perms.contains("FINANCE_PAYROLL_ADVANCES")) {
            return "redirect:/payroll/advances";
        }
        if (perms.contains("FINANCE_COMMISSION_CONFIG")) {
            return "redirect:/payroll/commission-config";
        }
        if (perms.contains("FINANCE_OWNER_DISTRIBUTIONS")) {
            return "redirect:/payroll/owner-distributions";
        }

        return "redirect:/expenses";
    }

    /**
     * [Chức năng]: Điều hướng từ `/finance/clinic` tới tab quản trị tài chính nội bộ phòng khám phù hợp với quyền của người đăng nhập.
     * [Mục đích]: Chuyển Admin hoặc nhân sự có quyền tương ứng đến màn hình Chi phí (`/expenses`), Bảng lương (`/payroll`), Tạm ứng lương, Cấu hình hoa hồng hoặc Phân bổ lợi nhuận chủ sở hữu.
     */
    @GetMapping("/finance/clinic")
    public String redirectFinanceClinic(HttpServletRequest request, HttpSession session) {
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User user)) {
            return "redirect:/login";
        }
        boolean isAdmin = Boolean.TRUE.equals(session.getAttribute("isAdmin"));
        List<String> roleNames = extractList(session.getAttribute("roleNames"));
        if (roleNames.isEmpty() && user.getUserId() != null) {
            roleNames = userRepository.findActiveRoleNamesByUserId(user.getUserId());
        }
        if (!isAdmin) {
            isAdmin = roleNames.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r) || "ROLE_ADMIN".equalsIgnoreCase(r));
        }
        boolean isKeToan = roleNames.stream().anyMatch(r ->
                "KE_TOAN".equalsIgnoreCase(r)
                        || "ROLE_KE_TOAN".equalsIgnoreCase(r)
                        || "ACCOUNTANT".equalsIgnoreCase(r)
                        || "ROLE_ACCOUNTANT".equalsIgnoreCase(r));
        List<String> perms = extractList(session.getAttribute("permissionCodes"));
        if (perms.isEmpty() && user.getUserId() != null) {
            perms = userRepository.findActivePermissionCodesByUserId(user.getUserId());
        }

        if (isAdmin || isKeToan || perms.contains("FINANCE_EXPENSES")) {
            return "redirect:/expenses";
        }
        if (perms.contains("SUPPLIER_MANAGE") || perms.contains("FINANCE_DASHBOARD")) {
            return "redirect:/supplier-payments";
        }
        if (perms.contains("FINANCE_PAYROLL")) {
            return "redirect:/payroll";
        }
        if (perms.contains("FINANCE_PAYROLL_ADVANCES")) {
            return "redirect:/payroll/advances";
        }
        if (perms.contains("FINANCE_COMMISSION_CONFIG")) {
            return "redirect:/payroll/commission-config";
        }
        if (perms.contains("FINANCE_OWNER_DISTRIBUTIONS")) {
            return "redirect:/payroll/owner-distributions";
        }
        return "redirect:/expenses";
    }

    /**
     * [Hàm hỗ trợ]: Chuyển đổi an toàn thuộc tính dạng `Collection` trong `HttpSession` sang `List<String>` bỏ qua phần tử `null`.
     */
    private List<String> extractList(Object obj) {
        if (obj instanceof Collection<?> col) {
            return col.stream().filter(o -> o != null).map(Object::toString).toList();
        }
        return Collections.emptyList();
    }
}
