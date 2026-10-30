package com.cvdcms.controller;

import com.cvdcms.DTO.OperatingExpenseRequestDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.OperatingExpense;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.ExpenseSourceType;
import com.cvdcms.entity.enums.RefundMethod;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
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
import java.util.List;

@Controller
@RequestMapping("/expenses")
public class ExpensePageController {

    private final OperatingExpenseService operatingExpenseService;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

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

        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        List<Long> allowedBranchIds = isAdmin ? null : allowedBranches.stream().map(Branch::getBranchId).toList();

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

        model.addAttribute("expenses", expenses);
        model.addAttribute("totalActiveAmount", totalActiveAmount);
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
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("categories", operatingExpenseService.getCommonExpenseCategories());
        model.addAttribute("paymentMethods", RefundMethod.values());

        return "finance/expenses/form";
    }

    @PostMapping("/create")
    public String createExpense(
            @Valid @ModelAttribute("expenseDTO") OperatingExpenseRequestDTO dto,
            BindingResult bindingResult,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long currentUserId = getCurrentUserId(session);

        if (bindingResult.hasErrors()) {
            populateUserAndSidebar(model, session, "expenses");
            model.addAttribute("branches", resolveAllowedBranches(currentUserId));
            model.addAttribute("categories", operatingExpenseService.getCommonExpenseCategories());
            model.addAttribute("paymentMethods", RefundMethod.values());
            return "finance/expenses/form";
        }

        try {
            OperatingExpense created = operatingExpenseService.createManualExpense(dto, currentUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Lập phiếu chi thành công! Mã phiếu: " + created.getExpenseCode());
            return "redirect:/expenses";
        } catch (Exception e) {
            populateUserAndSidebar(model, session, "expenses");
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("branches", resolveAllowedBranches(currentUserId));
            model.addAttribute("categories", operatingExpenseService.getCommonExpenseCategories());
            model.addAttribute("paymentMethods", RefundMethod.values());
            return "finance/expenses/form";
        }
    }

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
            model.addAttribute("branches", resolveAllowedBranches(currentUserId));
            model.addAttribute("categories", operatingExpenseService.getCommonExpenseCategories());
            model.addAttribute("paymentMethods", RefundMethod.values());

            return "finance/expenses/edit";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + e.getMessage());
            return "redirect:/expenses";
        }
    }

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
            model.addAttribute("branches", resolveAllowedBranches(currentUserId));
            model.addAttribute("categories", operatingExpenseService.getCommonExpenseCategories());
            model.addAttribute("paymentMethods", RefundMethod.values());
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
            model.addAttribute("branches", resolveAllowedBranches(currentUserId));
            model.addAttribute("categories", operatingExpenseService.getCommonExpenseCategories());
            model.addAttribute("paymentMethods", RefundMethod.values());
            return "finance/expenses/edit";
        }
    }

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
