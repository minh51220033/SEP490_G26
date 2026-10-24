package com.cvdcms.controller;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.DTO.PaymentRequestDTO;
import com.cvdcms.DTO.RefundRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.entity.enums.PaymentType;
import com.cvdcms.entity.enums.RefundMethod;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.InvoiceRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.PaymentRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.DebtService;
import com.cvdcms.service.PaymentService;
import com.cvdcms.service.RefundService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Controller
@RequestMapping("/payments")
public class PaymentPageController {

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final DebtService debtService;
    private final BranchRepository branchRepository;
    private final InvoiceRepository invoiceRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    public PaymentPageController(
            PaymentService paymentService,
            RefundService refundService,
            DebtService debtService,
            BranchRepository branchRepository,
            InvoiceRepository invoiceRepository,
            PatientProfileRepository patientProfileRepository,
            PaymentRepository paymentRepository,
            UserRepository userRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository
    ) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.debtService = debtService;
        this.branchRepository = branchRepository;
        this.invoiceRepository = invoiceRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.paymentRepository = paymentRepository;
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

    private Long resolveBranchId(Long requestedBranchId, Long userId) {
        boolean isAdmin = userRepository.hasActiveAdminRole(userId);
        if (isAdmin) {
            return requestedBranchId;
        }
        List<Long> activeBranchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
        if (activeBranchIds.isEmpty()) {
            throw new AccessDeniedException("Tài khoản chưa được phân công chi nhánh hợp lệ.");
        }
        if (requestedBranchId == null) {
            return activeBranchIds.get(0);
        }
        if (!activeBranchIds.contains(requestedBranchId)) {
            throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu tại chi nhánh ID: " + requestedBranchId);
        }
        return requestedBranchId;
    }

    // SCR-22.3: Lịch sử các đợt thanh toán
    @GetMapping
    public String listPayments(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) PaymentType paymentType,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "payments");

        Long effectiveBranchId = resolveBranchId(branchId, currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);

        LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDt = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "paidAt"));
        Page<Payment> paymentPage = paymentService.getPayments(effectiveBranchId, paymentType, paymentMethod, keyword, startDt, endDt, pageable);

        model.addAttribute("payments", paymentPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", paymentPage.getTotalPages());
        model.addAttribute("totalElements", paymentPage.getTotalElements());
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("selectedPaymentType", paymentType);
        model.addAttribute("selectedPaymentMethod", paymentMethod);
        model.addAttribute("keyword", keyword);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);

        return "finance/payments/list";
    }

    // SCR-22.1: Dashboard tài chính theo bệnh nhân
    @GetMapping({"/dashboard", "/patient/{profileId}"})
    public String patientFinancialDashboard(
            @PathVariable(required = false) Long profileId,
            @RequestParam(required = false) Long patientId,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "debts");

        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        List<Long> allowedBranchIds = isAdmin ? null : allowedBranches.stream().map(Branch::getBranchId).toList();

        Long targetProfileId = profileId != null ? profileId : patientId;
        if (targetProfileId == null) {
            // Nếu chưa chọn bệnh nhân, lấy bệnh nhân đầu tiên có phát sinh tài chính hoặc hiển thị tìm kiếm
            List<PatientProfile> topPatients = patientProfileRepository.findTop15ByOrderByProfileIdDesc();
            if (!topPatients.isEmpty()) {
                targetProfileId = topPatients.get(0).getProfileId();
            }
        }

        if (targetProfileId != null) {
            patientProfileRepository.findById(targetProfileId).ifPresent(patient -> {
                PatientDebtSummaryDTO summary = debtService.getPatientDebtDetail(patient.getProfileId(), allowedBranchIds);
                List<Invoice> invoices = (allowedBranchIds != null)
                        ? invoiceRepository.findInvoicesByPatientAndBranches(patient.getProfileId(), allowedBranchIds)
                        : invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(patient.getProfileId(), InvoiceStatus.CANCELLED);
                List<Payment> payments = (allowedBranchIds != null)
                        ? paymentRepository.findByPatientAndBranches(patient.getProfileId(), allowedBranchIds)
                        : paymentRepository.findByPatientProfile_ProfileIdOrderByPaidAtDesc(patient.getProfileId());

                model.addAttribute("patient", patient);
                model.addAttribute("summary", summary);
                model.addAttribute("invoices", invoices);
                model.addAttribute("payments", payments);
                model.addAttribute("branches", allowedBranches);
            });
        }

        return "finance/payments/dashboard";
    }

    // SCR-22.2: Form ghi nhận thanh toán
    @GetMapping("/create")
    public String createPaymentForm(
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(required = false) Long profileId,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "payments");
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        List<Long> allowedBranchIds = isAdmin ? null : allowedBranches.stream().map(Branch::getBranchId).toList();
        model.addAttribute("branches", allowedBranches);

        if (invoiceId != null) {
            invoiceRepository.findWithDetailsById(invoiceId).ifPresent(inv -> {
                resolveBranchId(inv.getBranch().getBranchId(), currentUserId);
                model.addAttribute("selectedInvoice", inv);
                model.addAttribute("selectedPatient", inv.getPatientProfile());
                model.addAttribute("selectedBranch", inv.getBranch());
            });
        } else if (profileId != null) {
            patientProfileRepository.findById(profileId).ifPresent(p -> {
                model.addAttribute("selectedPatient", p);
                model.addAttribute("unpaidInvoices", debtService.getUnpaidInvoicesForPatient(profileId, allowedBranchIds));
            });
        }

        return "finance/payments/form";
    }

    @PostMapping("/create")
    public String processPayment(
            @ModelAttribute PaymentRequestDTO request,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        try {
            Long cashierUserId = getCurrentUserId(session);
            Payment payment = paymentService.processPayment(request, cashierUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Ghi nhận thanh toán " + payment.getPaymentCode() + " thành công (" + payment.getAmount() + " đ)!");
            if (payment.getInvoice() != null) {
                return "redirect:/invoices/" + payment.getInvoice().getInvoiceId();
            }
            return "redirect:/payments";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi thanh toán: " + e.getMessage());
            return "redirect:/payments/create" + (request.getInvoiceId() != null ? "?invoiceId=" + request.getInvoiceId() : "");
        }
    }

    @GetMapping("/{id}/receipt")
    public String printReceipt(
            @PathVariable Long id,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "payments");
        Payment payment = paymentService.getPaymentById(id);
        resolveBranchId(payment.getBranch().getBranchId(), currentUserId);
        model.addAttribute("payment", payment);
        return "finance/payments/receipt";
    }

    @GetMapping("/deposits")
    public String listDeposits(
            @RequestParam(required = false) Long profileId,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "deposits");
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        model.addAttribute("branches", allowedBranches);

        if (profileId != null) {
            patientProfileRepository.findById(profileId).ifPresent(p -> {
                model.addAttribute("selectedPatient", p);
                model.addAttribute("depositHistory", paymentService.getPatientDepositHistory(profileId));
            });
        }
        return "finance/payments/deposits";
    }

    @PostMapping("/deposits")
    public String saveAdvanceDeposit(
            @ModelAttribute PaymentRequestDTO request,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        try {
            Long cashierUserId = getCurrentUserId(session);
            Payment deposit = paymentService.processAdvanceDeposit(request, cashierUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Nộp tiền tạm ứng " + deposit.getPaymentCode() + " thành công (" + deposit.getAmount() + " đ)!");
            return "redirect:/payments/deposits?profileId=" + request.getProfileId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi nộp tiền tạm ứng: " + e.getMessage());
            return "redirect:/payments/deposits" + (request.getProfileId() != null ? "?profileId=" + request.getProfileId() : "");
        }
    }

    @GetMapping("/refunds")
    public String listRefunds(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) RefundMethod refundMethod,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "refunds");

        Long effectiveBranchId = resolveBranchId(branchId, currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);

        LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDt = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "refundedAt"));
        Page<Refund> refundPage = refundService.getRefunds(effectiveBranchId, refundMethod, keyword, startDt, endDt, pageable);

        model.addAttribute("refunds", refundPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", refundPage.getTotalPages());
        model.addAttribute("totalElements", refundPage.getTotalElements());
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("selectedRefundMethod", refundMethod);
        model.addAttribute("keyword", keyword);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);

        return "finance/payments/refunds";
    }

    @PostMapping("/refunds")
    public String saveRefund(
            @ModelAttribute RefundRequestDTO request,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        try {
            Long staffUserId = getCurrentUserId(session);
            Refund refund = refundService.processRefund(request, staffUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Lập phiếu hoàn tiền " + refund.getRefundCode() + " thành công (" + refund.getAmount() + " đ)!");
            if (request.getInvoiceId() != null) {
                return "redirect:/invoices/" + request.getInvoiceId();
            }
            return "redirect:/payments/refunds";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi hoàn tiền: " + e.getMessage());
            return "redirect:/payments/refunds";
        }
    }

    @GetMapping("/debts")
    public String listDebts(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "debts");

        Long effectiveBranchId = resolveBranchId(branchId, currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);

        Pageable pageable = PageRequest.of(page, size);
        Page<PatientDebtSummaryDTO> debtPage = debtService.getPatientDebts(effectiveBranchId, keyword, pageable);

        model.addAttribute("patientDebts", debtPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", debtPage.getTotalPages());
        model.addAttribute("totalElements", debtPage.getTotalElements());
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("keyword", keyword);

        return "finance/payments/debts";
    }
}
