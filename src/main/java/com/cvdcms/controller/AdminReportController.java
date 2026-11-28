package com.cvdcms.controller;

import com.cvdcms.DTO.FinancialReportDTO;
import com.cvdcms.DTO.OperationalReportDTO;
import com.cvdcms.DTO.ReportDashboardDTO;
import com.cvdcms.DTO.ReportFilterDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.ReportService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * [Phân hệ]: Báo cáo quản trị & Xuất Excel
 * [Vai trò]: Controller điều hướng màn hình báo cáo tổng quan, tài chính, vận hành và cung cấp API tải file báo cáo Excel (.xlsx) theo phân quyền chi nhánh.
 */
@Controller
@RequestMapping("/admin/reports")
@RequiredArgsConstructor
@Slf4j
public class AdminReportController {

    private final ReportService reportService;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    /**
     * [Hàm hỗ trợ]: Nạp thông tin người dùng đang đăng nhập, vai trò, quyền hạn và trạng thái menu đang chọn vào Model cho thanh điều hướng.
     */
    private void populateUserAndSidebar(Model model, HttpSession session, String activeMenu) {
        model.addAttribute("activeMenu", activeMenu);
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("displayName", session.getAttribute("displayName"));
            model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
            model.addAttribute("isAdmin", session.getAttribute("isAdmin"));
            model.addAttribute("permissionCodes", session.getAttribute("permissionCodes"));
        }
    }

    /**
     * [Hàm hỗ trợ]: Lấy mã người dùng hiện tại từ HttpSession; ném AccessDeniedException nếu phiên chưa đăng nhập.
     */
    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng hiện tại có vai trò quản trị hệ thống đang hoạt động (xem toàn chuỗi) hay không.
     */
    private boolean isFinanceAdminOrAccountant(Long userId) {
        return userId != null && userRepository.hasActiveAdminRole(userId);
    }

    /**
     * [Chức năng]: Xác định danh sách chi nhánh người dùng được phép xem báo cáo.
     * [Mục đích]: Trả về toàn bộ chi nhánh nếu là quản trị viên; ngược lại lấy các chi nhánh nhân sự được phân công ACTIVE trong staff_branch_assignments hoặc chi nhánh gốc gắn trên tài khoản.
     */
    private List<Branch> resolveAllowedBranches(Long userId) {
        if (isFinanceAdminOrAccountant(userId)) {
            return branchRepository.findAll();
        }
        List<Long> branchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
        if (branchIds == null || branchIds.isEmpty()) {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null && user.getBranch() != null && user.getBranch().getBranchId() != null) {
                branchIds = List.of(user.getBranch().getBranchId());
            }
        }
        if (branchIds == null || branchIds.isEmpty()) {
            return Collections.emptyList();
        }
        return branchRepository.findAllById(branchIds);
    }

    /**
     * [Chức năng]: Xác định tập mã chi nhánh áp dụng để truy vấn dữ liệu báo cáo theo phạm vi quyền.
     * [Mục đích]: Cho phép quản trị viên xem toàn chuỗi (trả về null) hoặc lọc 1 chi nhánh; với nhân sự thường, xác thực chi nhánh yêu cầu thuộc phạm vi được phân công (ném AccessDeniedException nếu vượt quyền) và mặc định giới hạn trong danh sách chi nhánh được phân công.
     */
    private Collection<Long> resolveBranchScope(Long requestedBranchId, Long userId, List<Branch> allowedBranches) {
        if (isFinanceAdminOrAccountant(userId)) {
            if (requestedBranchId != null && requestedBranchId > 0) {
                return List.of(requestedBranchId);
            }
            return null; // Toàn chuỗi
        }

        List<Long> allowedIds = allowedBranches.stream().map(Branch::getBranchId).toList();
        if (allowedIds.isEmpty()) {
            throw new AccessDeniedException("Tài khoản nhân viên chưa được phân công chi nhánh làm việc hợp lệ.");
        }
        if (requestedBranchId != null && requestedBranchId > 0) {
            if (!allowedIds.contains(requestedBranchId)) {
                throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu tại chi nhánh ID: " + requestedBranchId);
            }
            return List.of(requestedBranchId);
        }
        // Non-admin mặc định chỉ xem các chi nhánh được phân công
        return allowedIds;
    }

    /**
     * [Hàm hỗ trợ]: Tạo nhãn tên chi nhánh hiển thị trên giao diện và tiêu đề file báo cáo Excel tương ứng với phạm vi lọc.
     */
    private String resolveBranchDisplayName(Long branchId, Collection<Long> branchIds, List<Branch> allowedBranches, boolean isPrivileged) {
        if (branchId != null && branchId > 0) {
            return allowedBranches.stream()
                    .filter(b -> b.getBranchId().equals(branchId))
                    .map(Branch::getBranchName)
                    .findFirst()
                    .orElse("Chi nhánh " + branchId);
        }
        if (isPrivileged) {
            return "Toàn bộ hệ thống";
        }
        if (allowedBranches.size() == 1) {
            return allowedBranches.get(0).getBranchName();
        }
        return "Các chi nhánh được phân công (" + allowedBranches.size() + " cơ sở)";
    }

    /**
     * [Chức năng]: Hiển thị trang báo cáo tổng quan, tài chính và vận hành (GET /admin/reports).
     * [Mục đích]: Điều hướng Admin về /home kèm tham số lọc để xem dashboard tích hợp; với nhân sự được phân quyền báo cáo, kiểm tra phạm vi chi nhánh, chuẩn hóa khoảng ngày lọc mặc định từ đầu tháng đến hiện tại, gọi ReportService tổng hợp dữ liệu 3 tab (tổng quan, tài chính thực thu trừ hoàn, vận hành) và trả về view admin/reports/index.
     */
    @GetMapping
    public String viewReports(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false, defaultValue = "overview") String tab,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        if (isAdmin) {
            org.springframework.web.util.UriComponentsBuilder builder = org.springframework.web.util.UriComponentsBuilder.fromPath("/home");
            if (branchId != null) builder.queryParam("branchId", branchId);
            if (startDate != null) builder.queryParam("startDate", startDate);
            if (endDate != null) builder.queryParam("endDate", endDate);
            if (tab != null && !tab.isBlank()) builder.queryParam("tab", tab);
            return "redirect:" + builder.toUriString();
        }
        populateUserAndSidebar(model, session, "reports");
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        Collection<Long> resolvedBranchIds = resolveBranchScope(branchId, currentUserId, allowedBranches);

        // Validation mốc thời gian: Mặc định từ ngày 01 đầu tháng đến hiện tại
        LocalDate safeEnd = (endDate != null) ? endDate : LocalDate.now();
        LocalDate safeStart = (startDate != null) ? startDate : safeEnd.withDayOfMonth(1);
        if (safeStart.isAfter(safeEnd)) {
            safeStart = safeEnd.withDayOfMonth(1);
            model.addAttribute("warningMessage", "Ngày bắt đầu không được lớn hơn ngày kết thúc. Hệ thống đã tự động điều chỉnh về đầu tháng.");
        }

        boolean isPrivileged = isFinanceAdminOrAccountant(currentUserId);
        String branchDisplayName = resolveBranchDisplayName(branchId, resolvedBranchIds, allowedBranches, isPrivileged);

        // Lấy dữ liệu cho từng tab
        ReportDashboardDTO dashboard = reportService.getDashboardReport(resolvedBranchIds, safeStart, safeEnd, branchDisplayName);
        FinancialReportDTO financial = reportService.getFinancialReport(resolvedBranchIds, safeStart, safeEnd);
        OperationalReportDTO operational = reportService.getOperationalReport(resolvedBranchIds, safeStart, safeEnd);

        ReportFilterDTO filter = new ReportFilterDTO();
        filter.setBranchId(branchId);
        filter.setStartDate(safeStart);
        filter.setEndDate(safeEnd);
        filter.setTab(tab);

        model.addAttribute("filter", filter);
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("financial", financial);
        model.addAttribute("operational", operational);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", branchId);
        model.addAttribute("branchDisplayName", branchDisplayName);
        model.addAttribute("activeTab", tab);
        model.addAttribute("isAdmin", isAdmin);

        return "admin/reports/index";
    }

    /**
     * [Chức năng]: Xuất báo cáo quản trị ra file Excel chuẩn .xlsx để tải về (GET /admin/reports/export).
     * [Mục đích]: Xác thực người dùng và phạm vi chi nhánh được phép truy cập, chuẩn hóa khoảng thời gian lọc, gọi ReportService.exportReportToExcel theo loại báo cáo (OVERVIEW, FINANCIAL, OPERATIONAL, DEBTS) và trả về ResponseEntity kèm header Content-Disposition tải file.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportReports(
            @RequestParam(required = false, defaultValue = "OVERVIEW") String reportType,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);

        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        Collection<Long> resolvedBranchIds = resolveBranchScope(branchId, currentUserId, allowedBranches);

        LocalDate safeEnd = (endDate != null) ? endDate : LocalDate.now();
        LocalDate safeStart = (startDate != null) ? startDate : safeEnd.withDayOfMonth(1);
        if (safeStart.isAfter(safeEnd)) {
            safeStart = safeEnd.withDayOfMonth(1);
        }

        boolean isPrivileged = isFinanceAdminOrAccountant(currentUserId);
        String branchDisplayName = resolveBranchDisplayName(branchId, resolvedBranchIds, allowedBranches, isPrivileged);

        byte[] excelBytes = reportService.exportReportToExcel(reportType, resolvedBranchIds, safeStart, safeEnd, branchDisplayName);

        String typePrefix = switch (reportType.toUpperCase()) {
            case "FINANCIAL" -> "Tai_Chinh";
            case "OPERATIONAL" -> "Van_Hanh";
            case "DEBTS" -> "Cong_No";
            default -> "Tong_Quan";
        };
        String fileName = "Bao_Cao_" + typePrefix + "_" + safeStart + "_den_" + safeEnd + ".xlsx";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }
}
