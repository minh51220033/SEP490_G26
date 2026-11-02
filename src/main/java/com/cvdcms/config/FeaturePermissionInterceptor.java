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
        // 1. TIẾP ĐÓN, LỊCH HẸN & CSKH (RECEPTION - 7 chức năng độc lập 1:1)
        // =====================================================================
        if (uri.startsWith("/reception/patients") || uri.startsWith("/api/reception/patients")) {
            return List.of("PATIENT_PROFILE_MANAGE");
        }
        if (uri.startsWith("/reception/memberships") || uri.startsWith("/api/reception/memberships")) {
            return List.of("RECEPTION_MEMBERSHIPS");
        }
        if (uri.startsWith("/reception/appointments") || uri.startsWith("/api/reception/appointments")) {
            return List.of("APPOINTMENT_MANAGE");
        }
        if (uri.startsWith("/reception/check-in") || uri.startsWith("/api/reception/check-in")) {
            return List.of("RECEPTION_CHECKIN");
        }
        if (uri.startsWith("/reception/queue") || uri.startsWith("/api/reception/queue")) {
            return List.of("APPOINTMENT_VIEW");
        }
        if (uri.startsWith("/reception/dispatch") || uri.startsWith("/api/reception/dispatch")) {
            return List.of("RECEPTION_DISPATCH", "CLINICAL_DISPATCH");
        }
        if (uri.startsWith("/customer-care")
                || uri.startsWith("/api/customer-care")
                || uri.startsWith("/doctor/recalls")
                || uri.startsWith("/doctor/re-examinations")
                || uri.startsWith("/api/doctor/recalls")) {
            return List.of("CUSTOMER_CARE_MANAGE");
        }

        // =====================================================================
        // 2. KHÁM BỆNH & ĐIỀU TRỊ LÂM SÀNG (CLINICAL - 9 chức năng độc lập 1:1)
        // =====================================================================
        if (uri.startsWith("/doctor/daily-visits")
                || uri.startsWith("/doctor/visits")
                || uri.startsWith("/api/doctor/patients/daily-visits")) {
            return List.of("CLINICAL_DAILY_VISITS");
        }
        if (uri.startsWith("/doctor/workflows") || uri.startsWith("/api/doctor/workflows")) {
            return List.of("CLINICAL_WORKFLOWS");
        }
        if (uri.startsWith("/api/doctor/prescriptions")) {
            return List.of("PRESCRIPTION_WRITE");
        }
        if (uri.startsWith("/doctor/labo") || uri.startsWith("/api/doctor/labo-orders")) {
            return List.of("CLINICAL_LABO");
        }
        if (uri.startsWith("/api/doctor/treatment-plans")) {
            return List.of("TREATMENT_PLAN_MANAGE");
        }
        if (uri.startsWith("/doctor/patient-records") || uri.startsWith("/api/doctor/patients")) {
            return List.of("MEDICAL_RECORD_VIEW");
        }
        if (uri.startsWith("/records")) {
            return List.of("MEDICAL_RECORD_VIEW", "PATIENT_PROFILE_MANAGE");
        }
        if (uri.startsWith("/xray") || uri.startsWith("/api/xray")) {
            return List.of("CLINICAL_XRAY");
        }
        if (uri.startsWith("/api/doctor/clinical-photos") || uri.startsWith("/doctor/clinical-photos")) {
            return List.of("CLINICAL_PHOTOS");
        }
        if (uri.startsWith("/doctor/examination")
                || uri.startsWith("/doctor/diagnoses")
                || uri.startsWith("/doctor/treatment")
                || uri.startsWith("/doctor/queue")
                || uri.startsWith("/api/doctor/diagnoses")
                || uri.startsWith("/api/doctor/performed-services")
                || uri.startsWith("/api/doctor/paraclinical")) {
            return List.of("CLINICAL_DAILY_VISITS", "CLINICAL_WORKFLOWS");
        }

        // =====================================================================
        // 3. TÀI CHÍNH, HÓA ĐƠN & THANH TOÁN (FINANCE - 5 chức năng độc lập 1:1)
        // =====================================================================
        if (uri.startsWith("/payments/dashboard")) {
            return List.of("FINANCE_DASHBOARD");
        }
        if (uri.startsWith("/payments/debts")) {
            return List.of("FINANCE_DEBT");
        }
        if (uri.startsWith("/payments/refunds") || uri.startsWith("/api/payments/refunds")) {
            return List.of("REFUND_MANAGE");
        }
        if (uri.startsWith("/invoices") || uri.startsWith("/api/invoices")) {
            return List.of("INVOICE_CREATE");
        }
        if (uri.startsWith("/payments") || uri.startsWith("/api/payments")) {
            return List.of("PAYMENT_COLLECT");
        }

        // =====================================================================
        // 4. KHO VẬT TƯ & NHÀ CUNG CẤP (INVENTORY - 2 chức năng độc lập 1:1)
        // =====================================================================
        if (uri.startsWith("/suppliers")
                || uri.startsWith("/inventory/suppliers")
                || uri.startsWith("/api/suppliers")
                || uri.startsWith("/api/inventory/supplier-payments")) {
            return List.of("SUPPLIER_MANAGE");
        }
        if (uri.startsWith("/inventory") || uri.startsWith("/api/inventory")) {
            return List.of("INVENTORY_STOCK");
        }

        // =====================================================================
        // 5. NHÂN SỰ, CA TRỰC & CHẤM CÔNG (HR - 6 chức năng độc lập 1:1)
        // =====================================================================
        if (uri.startsWith("/attendance/my")
                || uri.startsWith("/attendance/check-in")
                || uri.startsWith("/attendance/check-out")) {
            return List.of("HR_MY_ATTENDANCE");
        }
        if (uri.startsWith("/doctor/leave-requests") || uri.startsWith("/api/doctor/leave-requests")) {
            return List.of("HR_LEAVE_REQUESTS");
        }
        if (uri.startsWith("/schedules/my")) {
            return List.of("SCHEDULE_MY");
        }
        if (uri.startsWith("/admin/attendance") || uri.startsWith("/api/attendance")) {
            return List.of("HR_BRANCH_ATTENDANCE");
        }
        if (uri.startsWith("/admin/schedules") || uri.startsWith("/api/schedules")) {
            return List.of("SCHEDULE_MANAGE");
        }
        if (uri.startsWith("/admin/staffs") || uri.startsWith("/staffs")) {
            return List.of("STAFF_MANAGE");
        }

        // =====================================================================
        // 6. QUẢN TRỊ HỆ THỐNG & DANH MỤC (SYSTEM - 12 chức năng độc lập 1:1)
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
        if (uri.startsWith("/admin/reports")) {
            return List.of("FINANCE_REPORTS");
        }
        if (uri.startsWith("/admin/audit")) {
            return List.of("SYSTEM_AUDIT");
        }
        if (uri.startsWith("/admin/bans")) {
            return List.of("SYSTEM_BANS");
        }
        if (uri.startsWith("/admin/services")) {
            return List.of("CATALOG_SERVICES");
        }
        if (uri.startsWith("/admin/medications")) {
            return List.of("CATALOG_MEDICATIONS");
        }
        if (uri.startsWith("/admin/icd10")) {
            return List.of("CATALOG_ICD10");
        }
        if (uri.startsWith("/admin/membership-tiers")) {
            return List.of("CATALOG_MEMBERSHIP_TIERS");
        }
        if (uri.startsWith("/admin/promotions")) {
            return List.of("CATALOG_PROMOTIONS");
        }
        if (uri.startsWith("/admin/news")) {
            return List.of("CATALOG_NEWS");
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
