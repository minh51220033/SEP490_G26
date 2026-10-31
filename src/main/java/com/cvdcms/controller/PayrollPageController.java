package com.cvdcms.controller;

import com.cvdcms.DTO.*;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.OwnerDistributionStatus;
import com.cvdcms.entity.enums.PayrollStatus;
import com.cvdcms.entity.enums.RefundMethod;
import com.cvdcms.entity.enums.SalaryAdvanceStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.CommissionPolicyService;
import com.cvdcms.service.OwnerDistributionService;
import com.cvdcms.service.PayrollService;
import com.cvdcms.service.SalaryAdvanceService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/payroll")
public class PayrollPageController {

    private final PayrollService payrollService;
    private final SalaryAdvanceService salaryAdvanceService;
    private final CommissionPolicyService commissionPolicyService;
    private final OwnerDistributionService ownerDistributionService;
    private final BranchRepository branchRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final ServiceRepository serviceRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;
    private final OperatingExpenseRepository operatingExpenseRepository;

    public PayrollPageController(
            PayrollService payrollService,
            SalaryAdvanceService salaryAdvanceService,
            CommissionPolicyService commissionPolicyService,
            OwnerDistributionService ownerDistributionService,
            BranchRepository branchRepository,
            StaffProfileRepository staffProfileRepository,
            ServiceRepository serviceRepository,
            RoleRepository roleRepository,
            UserRepository userRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            OperatingExpenseRepository operatingExpenseRepository
    ) {
        this.payrollService = payrollService;
        this.salaryAdvanceService = salaryAdvanceService;
        this.commissionPolicyService = commissionPolicyService;
        this.ownerDistributionService = ownerDistributionService;
        this.branchRepository = branchRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.serviceRepository = serviceRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
        this.operatingExpenseRepository = operatingExpenseRepository;
    }

    private void populateUserAndSidebar(Model model, HttpSession session, String activeMenu) {
        model.addAttribute("activeMenu", activeMenu);
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("displayName", session.getAttribute("displayName"));
            model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
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
        if (branchIds.isEmpty()) {
            throw new AccessDeniedException("Nhân sự chưa được phân công chi nhánh hoạt động hợp lệ.");
        }
        return branchRepository.findAllById(branchIds);
    }

    // ==========================================
    // 1. KỲ LƯƠNG & BẢNG LƯƠNG (SCR-32.1, SCR-32.2)
    // ==========================================

    @GetMapping
    public String listPeriods(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) PayrollStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "payroll");

        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        List<Long> allowedBranchIds = isAdmin ? null : allowedBranches.stream().map(Branch::getBranchId).toList();

        Pageable pageable = PageRequest.of(page, size);
        Page<PayrollPeriod> periods = payrollService.getPeriods(
                branchId,
                allowedBranchIds,
                month,
                year,
                status,
                pageable
        );

        model.addAttribute("periods", periods);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("statuses", PayrollStatus.values());
        model.addAttribute("selectedBranchId", branchId);
        model.addAttribute("selectedMonth", month);
        model.addAttribute("selectedYear", year != null ? year : LocalDate.now().getYear());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("isAdmin", isAdmin);

        return "finance/payroll/list";
    }

    @PostMapping("/create")
    public String createPeriod(
            @Valid @ModelAttribute("periodCreateDTO") PayrollPeriodCreateDTO dto,
            BindingResult bindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Thông tin tạo kỳ lương không hợp lệ.");
            return "redirect:/payroll";
        }

        try {
            PayrollPeriod period = payrollService.createPeriod(dto, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Khởi tạo bảng lương thành công: " + period.getPeriodName());
            return "redirect:/payroll/" + period.getPeriodId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi tạo kỳ lương: " + e.getMessage());
            return "redirect:/payroll";
        }
    }

    @GetMapping("/{id:[0-9]+}")
    public String periodDetail(
            @PathVariable Long id,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "payroll");

        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        PayrollPeriod period = payrollService.getPeriodDetail(id, currentUserId);
        List<PayrollItem> items = payrollService.getPeriodItems(id, currentUserId);

        OperatingExpense linkedExpense = null;
        if (period.getStatus() == PayrollStatus.PAID) {
            linkedExpense = operatingExpenseRepository.findByPayrollPeriod_PeriodId(period.getPeriodId()).orElse(null);
        }

        model.addAttribute("period", period);
        model.addAttribute("items", items);
        model.addAttribute("linkedExpense", linkedExpense);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("paymentMethods", RefundMethod.values());

        return "finance/payroll/detail";
    }

    @PostMapping("/{id}/recalculate")
    public String recalculatePeriod(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            payrollService.recalculatePeriod(id, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã tính toán lại dữ liệu bảng lương thành công.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi tính toán lại: " + e.getMessage());
        }
        return "redirect:/payroll/" + id;
    }

    @PostMapping("/items/{itemId}/update")
    public String updateItem(
            @PathVariable Long itemId,
            @jakarta.validation.Valid @ModelAttribute PayrollItemUpdateDTO dto,
            org.springframework.validation.BindingResult bindingResult,
            @RequestParam Long periodId,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        if (bindingResult.hasErrors()) {
            String firstError = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("errorMessage", "Dữ liệu không hợp lệ: " + firstError);
            return "redirect:/payroll/" + periodId;
        }
        try {
            payrollService.updateItem(itemId, dto, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật dòng lương thành công.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi cập nhật dòng lương: " + e.getMessage());
        }
        return "redirect:/payroll/" + periodId;
    }

    @PostMapping("/{id}/submit")
    public String submitPeriod(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            payrollService.submitPeriod(id, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Trình duyệt bảng lương thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi trình duyệt: " + e.getMessage());
        }
        return "redirect:/payroll/" + id;
    }

    @PostMapping("/{id}/approve")
    public String approvePeriod(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            payrollService.approvePeriod(id, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Phê duyệt bảng lương thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi phê duyệt: " + e.getMessage());
        }
        return "redirect:/payroll/" + id;
    }

    @PostMapping("/{id}/reject")
    public String rejectPeriod(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "Yêu cầu kiểm tra lại số liệu") String reason,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            payrollService.rejectPeriod(id, reason, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã trả về bảng lương để điều chỉnh.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi từ chối: " + e.getMessage());
        }
        return "redirect:/payroll/" + id;
    }

    @PostMapping("/{id}/pay")
    public String payPeriod(
            @PathVariable Long id,
            @RequestParam(required = false) RefundMethod paymentMethod,
            @RequestParam(required = false) String paymentReference,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            PayrollPeriod paidPeriod = payrollService.payPeriod(id, paymentMethod, paymentReference, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Xác nhận chi trả bảng lương thành công! " +
                    (paidPeriod.getTotalRemainingPayable().signum() > 0 ? "Đã tự động tạo phiếu chi chi phí vận hành." : "Tất toán lương Net = 0đ."));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi chi trả: " + e.getMessage());
        }
        return "redirect:/payroll/" + id;
    }

    @PostMapping("/{id}/delete")
    public String deletePeriod(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            payrollService.deleteDraftPeriod(id, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã xóa bản nháp bảng lương.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể xóa: " + e.getMessage());
            return "redirect:/payroll/" + id;
        }
        return "redirect:/payroll";
    }

    // ==========================================
    // 2. TẠM ỨNG LƯƠNG NHÂN VIÊN
    // ==========================================

    @GetMapping("/advances")
    public String listAdvances(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) SalaryAdvanceStatus status,
            @RequestParam(required = false) Long staffId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "payroll");

        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        List<Long> allowedBranchIds = isAdmin ? null : allowedBranches.stream().map(Branch::getBranchId).toList();

        Pageable pageable = PageRequest.of(page, size);
        Page<SalaryAdvance> advances = salaryAdvanceService.getAdvances(
                branchId,
                allowedBranchIds,
                status,
                staffId,
                null,
                null,
                keyword,
                pageable
        );

        List<StaffProfile> activeStaff = staffProfileRepository.findByStatus(com.cvdcms.entity.enums.StaffStatus.ACTIVE);

        model.addAttribute("advances", advances);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("statuses", SalaryAdvanceStatus.values());
        model.addAttribute("activeStaff", activeStaff);
        model.addAttribute("paymentMethods", RefundMethod.values());
        model.addAttribute("selectedBranchId", branchId);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedStaffId", staffId);
        model.addAttribute("keyword", keyword);
        model.addAttribute("isAdmin", isAdmin);

        return "finance/payroll/advance-list";
    }

    @PostMapping("/advances/request")
    public String requestAdvance(
            @Valid @ModelAttribute("advanceDTO") SalaryAdvanceRequestDTO dto,
            BindingResult bindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Thông tin phiếu tạm ứng không hợp lệ.");
            return "redirect:/payroll/advances";
        }

        try {
            SalaryAdvance created = salaryAdvanceService.createAdvanceRequest(dto, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã tạo yêu cầu tạm ứng lương thành công: " + created.getAdvanceCode());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi tạo yêu cầu tạm ứng: " + e.getMessage());
        }
        return "redirect:/payroll/advances";
    }

    @PostMapping("/advances/{id}/approve")
    public String approveAdvance(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            salaryAdvanceService.approveAdvance(id, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã phê duyệt đơn tạm ứng!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi duyệt: " + e.getMessage());
        }
        return "redirect:/payroll/advances";
    }

    @PostMapping("/advances/{id}/pay")
    public String payAdvance(
            @PathVariable Long id,
            @RequestParam(required = false) RefundMethod paymentMethod,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            SalaryAdvance paid = salaryAdvanceService.payAdvance(id, paymentMethod, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã chi tạm ứng thành công cho " + paid.getStaff().getFullName()
                    + "! Đã tự động tạo phiếu chi chi phí vận hành.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi chi tiền: " + e.getMessage());
        }
        return "redirect:/payroll/advances";
    }

    @PostMapping("/advances/{id}/reject")
    public String rejectAdvance(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "Không đủ điều kiện") String reason,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            salaryAdvanceService.rejectAdvance(id, reason, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã từ chối đơn tạm ứng.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
        }
        return "redirect:/payroll/advances";
    }

    // ==========================================
    // 3. CẤU HÌNH HOA HỒNG BÁC SĨ (SCR-32.3)
    // ==========================================

    @GetMapping({"/commission-config", "/config"})
    public String commissionConfig(
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "payroll");

        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        List<CommissionPolicy> policies = commissionPolicyService.getAllPolicies();

        model.addAttribute("policies", policies);
        model.addAttribute("services", serviceRepository.findAll());
        model.addAttribute("roles", roleRepository.findAll());
        model.addAttribute("staffList", staffProfileRepository.findByStatus(com.cvdcms.entity.enums.StaffStatus.ACTIVE));
        model.addAttribute("isAdmin", isAdmin);

        return "finance/payroll/config";
    }

    @PostMapping("/commission-config/save")
    public String saveCommissionPolicy(
            @ModelAttribute CommissionPolicyDTO dto,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            commissionPolicyService.savePolicy(dto, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Lưu chính sách hoa hồng thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi lưu chính sách: " + e.getMessage());
        }
        return "redirect:/payroll/commission-config";
    }

    @PostMapping("/commission-config/{id}/toggle")
    public String toggleCommissionPolicy(
            @PathVariable Long id,
            @RequestParam boolean isActive,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            commissionPolicyService.togglePolicyStatus(id, isActive, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    (isActive ? "Bật" : "Tắt") + " chính sách hoa hồng thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
        }
        return "redirect:/payroll/commission-config";
    }

    @PostMapping("/commission-config/{id}/delete")
    public String deleteCommissionPolicy(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            commissionPolicyService.deletePolicy(id, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã xóa chính sách hoa hồng.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi xóa: " + e.getMessage());
        }
        return "redirect:/payroll/commission-config";
    }

    // ==========================================
    // 4. PHÂN CHIA LỢI NHUẬN CHỦ SỞ HỮU (SCR-32.3+)
    // ==========================================

    @GetMapping("/owner-distributions")
    public String listDistributions(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) OwnerDistributionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "payroll");

        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        List<Long> allowedBranchIds = isAdmin ? null : allowedBranches.stream().map(Branch::getBranchId).toList();

        Pageable pageable = PageRequest.of(page, size);
        Page<OwnerDistribution> distributions = ownerDistributionService.getDistributions(allowedBranchIds, month, year, status, pageable);

        List<StaffProfile> activeStaff = staffProfileRepository.findByStatus(com.cvdcms.entity.enums.StaffStatus.ACTIVE);
        List<com.cvdcms.DTO.StaffOptionDTO> staffOptions = activeStaff.stream()
                .map(s -> new com.cvdcms.DTO.StaffOptionDTO(s.getStaffId(), s.getStaffCode(), s.getFullName()))
                .toList();

        model.addAttribute("distributions", distributions);
        model.addAttribute("staffOptions", staffOptions);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("statuses", OwnerDistributionStatus.values());
        model.addAttribute("paymentMethods", RefundMethod.values());
        model.addAttribute("selectedMonth", month);
        model.addAttribute("selectedYear", year != null ? year : LocalDate.now().getYear());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("isAdmin", isAdmin);

        return "finance/payroll/owner-distribution";
    }

    @PostMapping("/owner-distributions/propose")
    public String proposeDistribution(
            @jakarta.validation.Valid @ModelAttribute OwnerDistributionRequestDTO dto,
            org.springframework.validation.BindingResult bindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        if (bindingResult.hasErrors()) {
            String firstError = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("errorMessage", "Dữ liệu đề xuất không hợp lệ: " + firstError);
            return "redirect:/payroll/owner-distributions";
        }
        try {
            OwnerDistribution dist = ownerDistributionService.createProposal(dto, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã lập đề xuất phân chia lợi nhuận thành công: " + dist.getDistributionCode());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi tạo đề xuất: " + e.getMessage());
        }
        return "redirect:/payroll/owner-distributions";
    }

    @PostMapping("/owner-distributions/{id}/approve")
    public String approveDistribution(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            ownerDistributionService.approveDistribution(id, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Phê duyệt đợt phân chia lợi nhuận thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
        }
        return "redirect:/payroll/owner-distributions";
    }

    @PostMapping("/owner-distributions/{id}/reject")
    public String rejectDistribution(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "Yêu cầu rà soát lại số liệu") String reason,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            ownerDistributionService.rejectDistribution(id, reason, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã từ chối đề xuất phân chia.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
        }
        return "redirect:/payroll/owner-distributions";
    }

    @PostMapping("/owner-distributions/{id}/pay")
    public String payDistribution(
            @PathVariable Long id,
            @RequestParam(required = false) RefundMethod paymentMethod,
            @RequestParam(required = false) String paymentReference,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            ownerDistributionService.payDistribution(id, paymentMethod, paymentReference, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Xác nhận chi trả lợi nhuận thành công! (Tách biệt khỏi chi phí hoạt động phòng khám)");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
        }
        return "redirect:/payroll/owner-distributions";
    }
}
