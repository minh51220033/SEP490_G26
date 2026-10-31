package com.cvdcms.config;

import com.cvdcms.entity.User;
import com.cvdcms.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;

/**
 * Interceptor kiểm tra quyền hạn chi tiết (22 Granular Feature Permissions) ở tầng Backend
 * và đồng bộ danh sách permissionCodes mới nhất vào HttpSession ở mỗi request để giao diện
 * Sidebar & các chức năng luôn phản ánh tức thì khi Admin thêm hoặc bỏ bớt quyền.
 */
@Component
public class FeaturePermissionInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    public FeaturePermissionInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {

        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute("loggedInUser") instanceof User sessionUser)) {
            return true;
        }

        Long userId = sessionUser.getUserId();
        if (userId == null || userId <= 0) {
            return true;
        }

        // Luôn đồng bộ danh sách mã quyền hiệu lực mới nhất từ DB vào session ở mỗi request
        List<String> activePermissionCodes = userRepository.findActivePermissionCodesByUserId(userId);
        session.setAttribute("permissionCodes", activePermissionCodes);

        // Quản trị viên hệ thống (ADMIN) có toàn quyền
        if (userRepository.hasActiveAdminRole(userId)) {
            return true;
        }

        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        String method = request.getMethod() == null ? "GET" : request.getMethod().toUpperCase();
        boolean isWriteMethod = "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method) || "PATCH".equals(method);

        List<String> requiredAnyOf = resolveRequiredPermissions(uri, isWriteMethod);
        if (requiredAnyOf == null || requiredAnyOf.isEmpty()) {
            return true;
        }

        boolean allowed = hasAnyPermission(activePermissionCodes, requiredAnyOf);
        if (!allowed) {
            denyAccess(request, response, session, uri, String.join(" / ", requiredAnyOf));
            return false;
        }

        return true;
    }

    private boolean hasAnyPermission(List<String> activeCodes, List<String> requiredAnyOf) {
        if (activeCodes == null || activeCodes.isEmpty()) {
            return false;
        }
        for (String req : requiredAnyOf) {
            for (String code : activeCodes) {
                if (code != null && code.equalsIgnoreCase(req)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Ánh xạ toàn bộ 22 quyền nghiệp vụ sang các đường dẫn Web UI và REST API tương ứng.
     * Trả về danh sách mã quyền (chỉ cần thỏa mãn 1 trong các quyền trong danh sách là được phép truy cập).
     */
    private List<String> resolveRequiredPermissions(String uri, boolean isWriteMethod) {
        if (uri == null || uri.isEmpty()) {
            return null;
        }

        // =====================================================================
        // 1. TIẾP ĐÓN, LỊCH HẸN & CSKH (RECEPTION) - 4 QUYỀN
        // =====================================================================
        if (uri.startsWith("/reception/patients") || uri.startsWith("/api/reception/patients")) {
            return List.of("PATIENT_PROFILE_MANAGE");
        }
        if (uri.startsWith("/reception/memberships")) {
            return List.of("PATIENT_PROFILE_MANAGE", "CUSTOMER_CARE_MANAGE");
        }
        if (uri.startsWith("/reception/check-in") || uri.startsWith("/api/reception/check-in")) {
            return List.of("APPOINTMENT_MANAGE");
        }
        if (uri.startsWith("/reception/appointments")) {
            if (uri.equals("/reception/appointments/new")
                    || uri.endsWith("/edit")
                    || (uri.startsWith("/reception/appointments/api") && isWriteMethod)) {
                return List.of("APPOINTMENT_MANAGE");
            }
            return List.of("APPOINTMENT_VIEW", "APPOINTMENT_MANAGE");
        }
        if (uri.startsWith("/reception/queue")) {
            return List.of("APPOINTMENT_VIEW", "APPOINTMENT_MANAGE");
        }
        if (uri.startsWith("/customer-care")
                || uri.startsWith("/api/customer-care")
                || uri.startsWith("/doctor/recalls")
                || uri.startsWith("/doctor/re-examinations")
                || uri.startsWith("/api/doctor/recalls")) {
            return List.of("CUSTOMER_CARE_MANAGE");
        }

        // =====================================================================
        // 2. KHÁM BỆNH & ĐIỀU TRỊ LÂM SÀNG (CLINICAL) - 4 QUYỀN
        // =====================================================================
        if (uri.startsWith("/doctor/daily-visits")
                || uri.startsWith("/doctor/visits")
                || uri.startsWith("/api/doctor/patients/daily-visits")) {
            return List.of("CLINICAL_EXAM_WRITE", "MEDICAL_RECORD_VIEW");
        }
        if (uri.startsWith("/api/doctor/prescriptions")) {
            if (isWriteMethod) {
                return List.of("PRESCRIPTION_WRITE");
            }
            return List.of("PRESCRIPTION_WRITE", "CLINICAL_EXAM_WRITE", "MEDICAL_RECORD_VIEW");
        }
        if (uri.startsWith("/doctor/labo")
                || uri.startsWith("/api/doctor/labo-orders")
                || (uri.startsWith("/api/doctor/treatment-plans") && isWriteMethod)) {
            return List.of("TREATMENT_PLAN_MANAGE");
        }
        if (uri.startsWith("/doctor/patient-records") || uri.startsWith("/api/doctor/patients")) {
            return List.of("MEDICAL_RECORD_VIEW");
        }
        if (uri.startsWith("/doctor/examination")
                || uri.startsWith("/doctor/diagnoses")
                || uri.startsWith("/doctor/treatment")
                || uri.startsWith("/doctor/workflows")
                || uri.startsWith("/doctor/queue")
                || uri.startsWith("/api/doctor/diagnoses")
                || uri.startsWith("/api/doctor/performed-services")
                || uri.startsWith("/api/doctor/paraclinical")
                || uri.startsWith("/api/doctor/clinical-photos")
                || (uri.startsWith("/api/doctor/visits") && isWriteMethod)) {
            return List.of("CLINICAL_EXAM_WRITE");
        }

        // =====================================================================
        // 3. TÀI CHÍNH, HÓA ĐƠN & THANH TOÁN (FINANCE) - 4 QUYỀN
        // =====================================================================
        if (uri.startsWith("/payments/refunds") || uri.startsWith("/api/payments/refunds")) {
            return List.of("REFUND_MANAGE");
        }
        if (uri.startsWith("/invoices/new")
                || ((uri.startsWith("/invoices") || uri.startsWith("/api/invoices")) && isWriteMethod)) {
            return List.of("INVOICE_CREATE");
        }
        if ((uri.startsWith("/payments") || uri.startsWith("/api/payments")) && isWriteMethod) {
            return List.of("PAYMENT_COLLECT");
        }
        if (uri.equals("/payments") || uri.startsWith("/api/payments")) {
            return List.of("PAYMENT_COLLECT", "INVOICE_VIEW");
        }
        if (uri.startsWith("/invoices")
                || uri.startsWith("/api/invoices")
                || uri.startsWith("/payments/dashboard")
                || uri.startsWith("/payments/debts")) {
            return List.of("INVOICE_VIEW", "INVOICE_CREATE", "PAYMENT_COLLECT");
        }

        // =====================================================================
        // 4. KHO VẬT TƯ & NHÀ CUNG CẤP (INVENTORY) - 3 QUYỀN
        // =====================================================================
        if (uri.startsWith("/suppliers")
                || uri.startsWith("/inventory/suppliers")
                || uri.startsWith("/api/suppliers")
                || uri.startsWith("/api/inventory/supplier-payments")) {
            return List.of("SUPPLIER_MANAGE");
        }
        if (uri.startsWith("/inventory/receipts")
                || uri.startsWith("/api/inventory/receipts")
                || uri.startsWith("/inventory/issues")
                || uri.startsWith("/api/inventory/issues")
                || ((uri.startsWith("/inventory") || uri.startsWith("/api/inventory")) && isWriteMethod)) {
            return List.of("INVENTORY_MANAGE");
        }
        if (uri.startsWith("/inventory") || uri.startsWith("/api/inventory")) {
            return List.of("INVENTORY_VIEW", "INVENTORY_MANAGE");
        }

        // =====================================================================
        // 5. NHÂN SỰ, CA TRỰC & CHẤM CÔNG (HR) - 3 QUYỀN
        // =====================================================================
        if (uri.startsWith("/schedules/my")
                || uri.startsWith("/attendance/my")
                || uri.startsWith("/attendance/check-in")
                || uri.startsWith("/attendance/check-out")) {
            return List.of("SCHEDULE_MY");
        }
        if (uri.startsWith("/admin/schedules") || uri.startsWith("/admin/attendance")) {
            return List.of("SCHEDULE_MANAGE");
        }
        if (uri.startsWith("/admin/staffs")) {
            return List.of("STAFF_MANAGE");
        }

        // =====================================================================
        // 6. QUẢN TRỊ HỆ THỐNG & DANH MỤC (SYSTEM) - 4 QUYỀN
        // =====================================================================
        if (uri.startsWith("/accounts/roles")) {
            return List.of("ROLE_MANAGE");
        }
        if (uri.startsWith("/accounts")) {
            return List.of("ACCOUNT_MANAGE");
        }
        if (uri.startsWith("/admin/branches")) {
            return List.of("BRANCH_MANAGE");
        }
        if (uri.startsWith("/admin/services")
                || uri.startsWith("/admin/medications")
                || uri.startsWith("/admin/icd10")
                || uri.startsWith("/admin/membership-tiers")
                || uri.startsWith("/admin/promotions")) {
            return List.of("CATALOG_MANAGE");
        }

        return null;
    }

    private void denyAccess(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session,
            String uri,
            String requiredPermission
    ) throws IOException {
        String message = "Tài khoản của bạn không được cấp quyền thực hiện chức năng này (" + requiredPermission + ").";
        String accept = request.getHeader("Accept");
        boolean isApi = uri.startsWith("/api/")
                || uri.contains("/api/")
                || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))
                || (accept != null && accept.contains("application/json"));

        if (isApi) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"" + message + "\",\"error\":\"" + message + "\"}");
        } else {
            if (session != null) {
                session.setAttribute("flashPermissionNotice", message);
            }
            response.sendRedirect(request.getContextPath() + "/home");
        }
    }
}
