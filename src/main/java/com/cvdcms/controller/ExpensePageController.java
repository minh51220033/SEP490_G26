package com.cvdcms.controller;

import com.cvdcms.DTO.OperatingExpenseRequestDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.OperatingExpense;
import com.cvdcms.entity.StaffBranchAssignment;
import com.cvdcms.entity.StaffProfile;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.ExpenseSourceType;
import com.cvdcms.entity.enums.RefundMethod;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.OperatingExpenseRepository;
import com.cvdcms.repository.RefundRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.SupplierPaymentRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.OperatingExpenseService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [Phân hệ]: Tài chính - Chi phí hoạt động phòng khám
 * [Vai trò]: Controller MVC quản lý các trang giao diện danh sách, tạo mới, chỉnh sửa và hủy phiếu chi vận hành (/expenses).
 */
@Controller
@RequestMapping("/expenses")
public class ExpensePageController {

    private static final DateTimeFormatter VN_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final OperatingExpenseService operatingExpenseService;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperatingExpenseRepository operatingExpenseRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SupplierPaymentRepository supplierPaymentRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RefundRepository refundRepository;

    public ExpensePageController(
            OperatingExpenseService operatingExpenseService,
            BranchRepository branchRepository,
            UserRepository userRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository
    ) {
        this.operatingExpenseService = operatingExpenseService;
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
    }

    /**
     * [Hàm hỗ trợ]: Nạp thông tin người dùng đăng nhập, vai trò và menu đang chọn vào Model cho layout giao diện.
     */
    private void populateUserAndSidebar(Model model, HttpSession session, String activeMenu) {
        model.addAttribute("activeMenu", activeMenu);
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("displayName", session.getAttribute("displayName"));
            model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
        }
    }

    /**
     * [Hàm hỗ trợ]: Lấy ID người dùng đang đăng nhập từ HttpSession, ném AccessDeniedException nếu chưa đăng nhập.
     */
    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng hiện tại có vai trò Admin đang hoạt động hay không để xem toàn bộ chi nhánh.
     */
    private boolean isFinanceAdminOrAccountant(Long userId) {
        return userId != null && userRepository.hasActiveAdminRole(userId);
    }

    /**
     * [Hàm hỗ trợ]: Xác định danh sách chi nhánh người dùng được phép thao tác (toàn bộ nếu là Admin, hoặc theo phân công ACTIVE / chi nhánh mặc định).
     */
    private List<Branch> resolveAllowedBranches(Long userId) {
        if (isFinanceAdminOrAccountant(userId)) {
            return branchRepository.findAll();
        }
        List<Long> branchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
        if (branchIds.isEmpty()) {
            User u = userRepository.findById(userId).orElse(null);
            if (u != null && u.getBranch() != null && u.getBranch().getBranchId() != null) {
                branchIds = List.of(u.getBranch().getBranchId());
            }
        }
        if (branchIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return branchRepository.findAllById(branchIds);
    }

    /**
     * [Chức năng]: Hiển thị trang danh sách phiếu chi vận hành kèm bộ lọc đa điều kiện và tổng hợp chi phí toàn phòng khám (GET /expenses).
     * [Mục đích]: Kiểm tra quyền truy cập chi nhánh, gọi OperatingExpenseService lấy danh sách phân trang và tổng chi phí vận hành hiệu lực, đồng thời tổng hợp thêm chi thanh toán NCC và hoàn tiền bệnh nhân để render trang finance/expenses/list.
     */
    @GetMapping
    public String listExpenses(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) ExpenseSourceType sourceType,
            @RequestParam(required = false) RefundMethod paymentMethod,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Boolean isCancelled,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "expenses");

        boolean isPrivileged = isFinanceAdminOrAccountant(currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        List<Long> allowedBranchIds = isPrivileged ? null : allowedBranches.stream().map(Branch::getBranchId).toList();
        if (!isPrivileged && allowedBranchIds.isEmpty()) {
            throw new AccessDeniedException("Tài khoản nhân viên chưa được phân công chi nhánh làm việc hợp lệ.");
        }
        if (!isPrivileged && branchId != null && !allowedBranchIds.contains(branchId)) {
            throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu tại chi nhánh ID: " + branchId);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "expenseDate", "expenseId"));
        Page<OperatingExpense> expenses = operatingExpenseService.getExpenses(
                branchId,
                allowedBranchIds,
                category,
                sourceType,
                paymentMethod,
                startDate,
                endDate,
                isCancelled,
                keyword,
                pageable
        );

        BigDecimal totalActiveAmount = operatingExpenseService.getTotalActiveExpenses(
                branchId,
                allowedBranchIds,
                category,
                sourceType,
                paymentMethod,
                startDate,
                endDate,
                keyword
        );

        BigDecimal safeActiveOperating = totalActiveAmount != null ? totalActiveAmount : BigDecimal.ZERO;
        boolean invalidDateRange = startDate != null && endDate != null && startDate.isAfter(endDate);
        List<Long> scopeBranchIds = branchId != null ? List.of(branchId) : allowedBranchIds;

        BigDecimal totalClinicOperatingAmount = safeActiveOperating;
        if (!invalidDateRange && operatingExpenseRepository != null) {
            BigDecimal sumOp = operatingExpenseRepository.sumActiveExpenses(scopeBranchIds, startDate, endDate);
            if (sumOp != null) {
                totalClinicOperatingAmount = sumOp;
            }
        }
        BigDecimal totalSupplierPaymentAmount = BigDecimal.ZERO;
        if (!invalidDateRange && supplierPaymentRepository != null) {
            BigDecimal sumSp = supplierPaymentRepository.sumPaidAmountByBranchesAndDates(scopeBranchIds, startDate, endDate);
            if (sumSp != null) {
                totalSupplierPaymentAmount = sumSp;
            }
        }
        BigDecimal totalRefundAmount = BigDecimal.ZERO;
        if (!invalidDateRange && refundRepository != null) {
            LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
            LocalDateTime endExclusive = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;
            BigDecimal sumRef = refundRepository.sumRefundAmountByBranches(scopeBranchIds, startDt, endExclusive);
            if (sumRef != null) {
                totalRefundAmount = sumRef;
            }
        }
        BigDecimal clinicTotalExpense = totalClinicOperatingAmount
                .add(totalSupplierPaymentAmount)
                .add(totalRefundAmount);

        String filterPeriodLabel = "Toàn thời gian";
        LocalDate today = LocalDate.now();
        if (startDate != null && endDate != null) {
            if (startDate.equals(today) && endDate.equals(today)) {
                filterPeriodLabel = "Hôm nay";
            } else if (startDate.equals(endDate)) {
                filterPeriodLabel = "Ngày " + startDate.format(VN_DATE_FMT);
            } else {
                filterPeriodLabel = startDate.format(VN_DATE_FMT) + " - " + endDate.format(VN_DATE_FMT);
            }
        } else if (startDate != null) {
            filterPeriodLabel = "Từ " + startDate.format(VN_DATE_FMT);
        } else if (endDate != null) {
            filterPeriodLabel = "Đến " + endDate.format(VN_DATE_FMT);
        }

        String filterBranchLabel = "Tất cả cơ sở";
        if (branchId != null && allowedBranches != null) {
            for (Branch b : allowedBranches) {
                if (branchId.equals(b.getBranchId()) && b.getBranchName() != null) {
                    filterBranchLabel = b.getBranchName();
                    break;
                }
            }
        }

        model.addAttribute("expenses", expenses);
        model.addAttribute("totalActiveAmount", safeActiveOperating);
        model.addAttribute("totalClinicOperatingAmount", totalClinicOperatingAmount);
        model.addAttribute("totalSupplierPaymentAmount", totalSupplierPaymentAmount);
        model.addAttribute("totalRefundAmount", totalRefundAmount);
        model.addAttribute("clinicTotalExpense", clinicTotalExpense);
        model.addAttribute("filterPeriodLabel", filterPeriodLabel);
        model.addAttribute("filterBranchLabel", filterBranchLabel);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("categories", operatingExpenseService.getCommonExpenseCategories());
        model.addAttribute("sourceTypes", ExpenseSourceType.values());
        model.addAttribute("paymentMethods", RefundMethod.values());

        model.addAttribute("selectedBranchId", branchId);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("selectedSourceType", sourceType);
        model.addAttribute("selectedPaymentMethod", paymentMethod);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("isCancelled", isCancelled);
        model.addAttribute("keyword", keyword);

        return "finance/expenses/list";
    }

    public record StaffRecipientOption(Long staffId, String staffCode, String fullName, String branchIdsCsv) {}

    /**
     * [Hàm hỗ trợ]: Tổng hợp danh sách nhân sự đang công tác thuộc các chi nhánh được phép để gợi ý người nhận tiền trên form lập/sửa phiếu chi.
     */
    private List<StaffRecipientOption> resolveStaffOptions(List<Branch> allowedBranches) {
        if (allowedBranches == null || allowedBranches.isEmpty()) {
            return List.of();
        }
        Set<Long> allowedBranchIdSet = new LinkedHashSet<>();
        for (Branch b : allowedBranches) {
            if (b != null && b.getBranchId() != null) {
                allowedBranchIdSet.add(b.getBranchId());
            }
        }
        List<StaffBranchAssignment> activeAssignments = staffBranchAssignmentRepository.findAllByStatus(AssignmentStatus.ACTIVE);
        Map<Long, StaffProfile> staffMap = new LinkedHashMap<>();
        Map<Long, Set<Long>> staffBranchesMap = new LinkedHashMap<>();
        for (StaffBranchAssignment a : activeAssignments) {
            if (a.getStaff() == null || a.getBranch() == null || a.getBranch().getBranchId() == null) {
                continue;
            }
            Long branchId = a.getBranch().getBranchId();
            if (!allowedBranchIdSet.contains(branchId)) {
                continue;
            }
            Long staffId = a.getStaff().getStaffId();
            staffMap.putIfAbsent(staffId, a.getStaff());
            staffBranchesMap.computeIfAbsent(staffId, k -> new LinkedHashSet<>()).add(branchId);
        }
        List<StaffRecipientOption> options = new ArrayList<>();
        for (Map.Entry<Long, StaffProfile> entry : staffMap.entrySet()) {
            StaffProfile sp = entry.getValue();
            Set<Long> bIds = staffBranchesMap.getOrDefault(entry.getKey(), Set.of());
            String csv = bIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            options.add(new StaffRecipientOption(sp.getStaffId(), sp.getStaffCode(), sp.getFullName(), csv));
        }
        options.sort(Comparator.comparing(StaffRecipientOption::fullName, String.CASE_INSENSITIVE_ORDER));
        return options;
    }

    /**
     * [Hàm hỗ trợ]: Nạp các danh mục dùng chung (chi nhánh, danh mục chi phí, phương thức thanh toán, nhân sự nhận tiền) vào Model cho form phiếu chi.
     */
    private void populateFormOptions(Model model, Long currentUserId) {
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("categories", operatingExpenseService.getCommonExpenseCategories());
        model.addAttribute("paymentMethods", RefundMethod.values());
        model.addAttribute("staffOptions", resolveStaffOptions(allowedBranches));
    }

    /**
     * [Chức năng]: Hiển thị form lập phiếu chi vận hành thủ công mới (GET /expenses/create).
     * [Mục đích]: Khởi tạo OperatingExpenseRequestDTO với chi nhánh mặc định theo tham số hoặc chi nhánh đầu tiên được phép, nạp dữ liệu danh mục cho trang finance/expenses/form.
     */
    @GetMapping("/create")
    public String showCreateForm(
            @RequestParam(required = false) Long branchId,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "expenses");

        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        OperatingExpenseRequestDTO dto = new OperatingExpenseRequestDTO();
        if (branchId != null) {
            dto.setBranchId(branchId);
        } else if (!allowedBranches.isEmpty()) {
            dto.setBranchId(allowedBranches.get(0).getBranchId());
        }

        model.addAttribute("expenseDTO", dto);
        populateFormOptions(model, currentUserId);

        return "finance/expenses/form";
    }

    /**
     * [Chức năng]: Xử lý yêu cầu tạo mới phiếu chi vận hành thủ công (POST /expenses/create).
     * [Mục đích]: Kiểm tra hợp lệ dữ liệu đầu vào, gọi OperatingExpenseService.createManualExpense để lưu phiếu chi mới và điều hướng về danh sách hoặc tiếp tục tạo phiếu nếu chọn saveAndContinue.
     */
    @PostMapping("/create")
    public String createExpense(
            @Valid @ModelAttribute("expenseDTO") OperatingExpenseRequestDTO dto,
            BindingResult bindingResult,
            @RequestParam(name = "saveAndContinue", defaultValue = "false") boolean saveAndContinue,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);

        if (bindingResult.hasErrors()) {
            populateUserAndSidebar(model, session, "expenses");
            populateFormOptions(model, currentUserId);
            return "finance/expenses/form";
        }

        try {
            OperatingExpense created = operatingExpenseService.createManualExpense(dto, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Lập phiếu chi thành công! Mã phiếu: " + created.getExpenseCode());
            if (saveAndContinue) {
                return "redirect:/expenses/create" + (dto.getBranchId() != null ? "?branchId=" + dto.getBranchId() : "");
            }
            return "redirect:/expenses";
        } catch (Exception e) {
            populateUserAndSidebar(model, session, "expenses");
            model.addAttribute("errorMessage", e.getMessage());
            populateFormOptions(model, currentUserId);
            return "finance/expenses/form";
        }
    }

    /**
     * [Chức năng]: Hiển thị form chỉnh sửa phiếu chi vận hành thủ công theo ID (GET /expenses/{id}/edit).
     * [Mục đích]: Tải chi tiết phiếu chi, chặn chỉnh sửa nếu phiếu chi sinh tự động (không phải MANUAL) hoặc đã bị hủy, và đổ dữ liệu hiện tại vào trang finance/expenses/edit.
     */
    @GetMapping("/{id}/edit")
    public String showEditForm(
            @PathVariable Long id,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "expenses");

        try {
            OperatingExpense expense = operatingExpenseService.getExpenseDetail(id, currentUserId);
            if (expense.getSourceType() != ExpenseSourceType.MANUAL) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Không thể chỉnh sửa phiếu chi tự động (" + expense.getSourceType() + ").");
                return "redirect:/expenses";
            }
            if (Boolean.TRUE.equals(expense.getIsCancelled())) {
                redirectAttributes.addFlashAttribute("errorMessage", "Không thể chỉnh sửa phiếu chi đã bị hủy.");
                return "redirect:/expenses";
            }

            OperatingExpenseRequestDTO dto = new OperatingExpenseRequestDTO();
            dto.setBranchId(expense.getBranch().getBranchId());
            dto.setCategory(expense.getCategory());
            dto.setAmount(expense.getAmount());
            dto.setExpenseDate(expense.getExpenseDate());
            dto.setRecipientName(expense.getRecipientName());
            dto.setPaymentMethod(expense.getPaymentMethod());
            dto.setDescription(expense.getDescription());

            model.addAttribute("expense", expense);
            model.addAttribute("expenseDTO", dto);
            populateFormOptions(model, currentUserId);

            return "finance/expenses/edit";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
            return "redirect:/expenses";
        }
    }

    /**
     * [Chức năng]: Xử lý cập nhật thông tin phiếu chi vận hành thủ công kèm lý do chỉnh sửa (POST /expenses/{id}/edit).
     * [Mục đích]: Bắt buộc nhập lý do chỉnh sửa, xác thực biểu mẫu và gọi OperatingExpenseService.updateManualExpense để cập nhật bản ghi và lưu nhật ký kiểm toán.
     */
    @PostMapping("/{id}/edit")
    public String updateExpense(
            @PathVariable Long id,
            @Valid @ModelAttribute("expenseDTO") OperatingExpenseRequestDTO dto,
            BindingResult bindingResult,
            @RequestParam(required = false) String editReason,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);

        if (editReason == null || editReason.trim().isEmpty()) {
            bindingResult.rejectValue("description", "required", "Bắt buộc nhập lý do chỉnh sửa chi phí.");
        }

        if (bindingResult.hasErrors()) {
            populateUserAndSidebar(model, session, "expenses");
            OperatingExpense expense = operatingExpenseService.getExpenseDetail(id, currentUserId);
            model.addAttribute("expense", expense);
            populateFormOptions(model, currentUserId);
            return "finance/expenses/edit";
        }

        try {
            OperatingExpense updated = operatingExpenseService.updateManualExpense(id, dto, editReason, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Cập nhật phiếu chi " + updated.getExpenseCode() + " thành công!");
            return "redirect:/expenses";
        } catch (Exception e) {
            populateUserAndSidebar(model, session, "expenses");
            OperatingExpense expense = operatingExpenseService.getExpenseDetail(id, currentUserId);
            model.addAttribute("expense", expense);
            model.addAttribute("errorMessage", e.getMessage());
            populateFormOptions(model, currentUserId);
            return "finance/expenses/edit";
        }
    }

    /**
     * [Chức năng]: Xử lý hủy phiếu chi vận hành thủ công theo ID và lý do hủy (POST /expenses/{id}/cancel).
     * [Mục đích]: Gọi OperatingExpenseService.cancelExpense để đánh dấu hủy mềm phiếu chi và trả thông báo kết quả về trang danh sách /expenses.
     */
    @PostMapping("/{id}/cancel")
    public String cancelExpense(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "Hủy phiếu chi theo yêu cầu") String reason,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);
        try {
            OperatingExpense cancelled = operatingExpenseService.cancelExpense(id, reason, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Hủy phiếu chi " + cancelled.getExpenseCode() + " thành công.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể hủy phiếu chi: " + e.getMessage());
        }
        return "redirect:/expenses";
    }
}
