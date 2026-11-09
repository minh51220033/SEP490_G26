package com.cvdcms.controller;

import com.cvdcms.DTO.report.FinancialReportDTO;
import com.cvdcms.DTO.report.OperationalReportDTO;
import com.cvdcms.DTO.report.ReportDashboardDTO;
import com.cvdcms.DTO.report.ReportFilterDTO;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Controller
@RequestMapping("/admin/reports")
@RequiredArgsConstructor
@Slf4j
public class AdminReportController {

    private final ReportService reportService;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

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

    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

    private List<Branch> resolveAllowedBranches(Long userId) {
        boolean isAdmin = userRepository.hasActiveAdminRole(userId);
        if (isAdmin) {
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
            throw new AccessDeniedException("Nhân sự chưa được phân công chi nhánh hoạt động hợp lệ.");
        }
        return branchRepository.findAllById(branchIds);
    }

    private Collection<Long> resolveBranchScope(Long requestedBranchId, Long userId, List<Branch> allowedBranches) {
        boolean isAdmin = userRepository.hasActiveAdminRole(userId);
        if (isAdmin) {
            if (requestedBranchId != null && requestedBranchId > 0) {
                return List.of(requestedBranchId);
            }
            return null; // Toàn chuỗi
        }

        List<Long> allowedIds = allowedBranches.stream().map(Branch::getBranchId).toList();
        if (requestedBranchId != null && requestedBranchId > 0) {
            if (!allowedIds.contains(requestedBranchId)) {
                throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu tại chi nhánh ID: " + requestedBranchId);
            }
            return List.of(requestedBranchId);
        }
        // Non-admin mặc định chỉ xem các chi nhánh được phân công
        return allowedIds;
    }

    private String resolveBranchDisplayName(Long branchId, Collection<Long> branchIds, List<Branch> allowedBranches, boolean isAdmin) {
        if (branchId != null && branchId > 0) {
            return allowedBranches.stream()
                    .filter(b -> b.getBranchId().equals(branchId))
                    .map(Branch::getBranchName)
                    .findFirst()
                    .orElse("Chi nhánh " + branchId);
        }
        if (isAdmin) {
            return "Toàn bộ hệ thống";
        }
        if (allowedBranches.size() == 1) {
            return allowedBranches.get(0).getBranchName();
        }
        return "Các chi nhánh được phân công (" + allowedBranches.size() + " cơ sở)";
    }

    @GetMapping
    public String viewReports(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false, defaultValue = "20") BigDecimal taxRate,
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
            if (taxRate != null) builder.queryParam("taxRate", taxRate);
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

        BigDecimal safeTaxRate = (taxRate != null && taxRate.compareTo(BigDecimal.ZERO) >= 0) ? taxRate : BigDecimal.valueOf(20);
        String branchDisplayName = resolveBranchDisplayName(branchId, resolvedBranchIds, allowedBranches, isAdmin);

        // Lấy dữ liệu cho từng tab
        ReportDashboardDTO dashboard = reportService.getDashboardReport(resolvedBranchIds, safeStart, safeEnd, branchDisplayName, safeTaxRate);
        FinancialReportDTO financial = reportService.getFinancialReport(resolvedBranchIds, safeStart, safeEnd, safeTaxRate);
        OperationalReportDTO operational = reportService.getOperationalReport(resolvedBranchIds, safeStart, safeEnd);

        ReportFilterDTO filter = new ReportFilterDTO();
        filter.setBranchId(branchId);
        filter.setStartDate(safeStart);
        filter.setEndDate(safeEnd);
        filter.setTaxRate(safeTaxRate);
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

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportReports(
            @RequestParam(required = false, defaultValue = "OVERVIEW") String reportType,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false, defaultValue = "20") BigDecimal taxRate,
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

        BigDecimal safeTaxRate = (taxRate != null && taxRate.compareTo(BigDecimal.ZERO) >= 0) ? taxRate : BigDecimal.valueOf(20);
        String branchDisplayName = resolveBranchDisplayName(branchId, resolvedBranchIds, allowedBranches, isAdmin);

        byte[] excelBytes = reportService.exportReportToExcel(reportType, resolvedBranchIds, safeStart, safeEnd, branchDisplayName, safeTaxRate);

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
