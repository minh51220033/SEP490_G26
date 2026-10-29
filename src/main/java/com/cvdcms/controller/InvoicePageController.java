package com.cvdcms.controller;

import com.cvdcms.DTO.FinancialMetricsDTO;
import com.cvdcms.DTO.InvoiceAdjustmentRequestDTO;
import com.cvdcms.DTO.InvoiceCreateRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
import com.cvdcms.entity.enums.VisitStatus;
import com.cvdcms.entity.enums.VoucherStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.InvoiceService;
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
@RequestMapping("/invoices")
public class InvoicePageController {

    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final BranchRepository branchRepository;
    private final VisitRepository visitRepository;
    private final PatientProfileRepository patientProfileRepository;
    private final PromotionRepository promotionRepository;
    private final PatientVoucherRepository patientVoucherRepository;
    private final PatientLoyaltyWalletRepository patientLoyaltyWalletRepository;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    public InvoicePageController(
            InvoiceService invoiceService,
            PaymentService paymentService,
            RefundService refundService,
            BranchRepository branchRepository,
            VisitRepository visitRepository,
            PatientProfileRepository patientProfileRepository,
            PromotionRepository promotionRepository,
            PatientVoucherRepository patientVoucherRepository,
            PatientLoyaltyWalletRepository patientLoyaltyWalletRepository,
            UserRepository userRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository
    ) {
        this.invoiceService = invoiceService;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.branchRepository = branchRepository;
        this.visitRepository = visitRepository;
        this.patientProfileRepository = patientProfileRepository;
        this.promotionRepository = promotionRepository;
        this.patientVoucherRepository = patientVoucherRepository;
        this.patientLoyaltyWalletRepository = patientLoyaltyWalletRepository;
        this.userRepository = userRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
    }

    private void populateUserAndSidebar(Model model, HttpSession session) {
        model.addAttribute("activeMenu", "invoices");
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
            // Không cho người dùng bỏ branchId để xem mọi chi nhánh ngoài quyền -> mặc định về chi nhánh được phân công
            return activeBranchIds.get(0);
        }
        if (!activeBranchIds.contains(requestedBranchId)) {
            throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu tại chi nhánh ID: " + requestedBranchId);
        }
        return requestedBranchId;
    }

    @GetMapping
    public String listInvoices(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) InvoicePaymentStatus paymentStatus,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        Long effectiveBranchId = resolveBranchId(branchId, currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);

        LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDt = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "issuedAt"));
        Page<Invoice> invoicePage = invoiceService.getInvoices(effectiveBranchId, paymentStatus, status, keyword, startDt, endDt, pageable);

        LocalDate today = LocalDate.now();
        FinancialMetricsDTO todayMetrics = invoiceService.getFinancialMetrics(effectiveBranchId, today.atStartOfDay(), today.atTime(LocalTime.MAX));

        model.addAttribute("invoices", invoicePage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", invoicePage.getTotalPages());
        model.addAttribute("totalElements", invoicePage.getTotalElements());
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("selectedPaymentStatus", paymentStatus);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("keyword", keyword);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("metrics", todayMetrics);

        return "finance/invoices/list";
    }

    @GetMapping("/create")
    public String createInvoiceForm(
            @RequestParam(required = false) Long visitId,
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) Long branchId,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        Long effectiveBranchId = resolveBranchId(branchId, currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);

        // Bổ sung danh sách các ca khám đang chờ thanh toán (WAITING_PAYMENT) tại chi nhánh kế toán phụ trách
        List<Visit> waitingVisits = visitRepository.findVisitsByStatusAndBranch(VisitStatus.WAITING_PAYMENT, effectiveBranchId);
        model.addAttribute("waitingVisits", waitingVisits);

        if (visitId != null) {
            visitRepository.findWithDetailsById(visitId).ifPresent(visit -> {
                // Kiểm tra phân công chi nhánh của lượt khám
                resolveBranchId(visit.getBranch().getBranchId(), currentUserId);

                model.addAttribute("selectedVisit", visit);
                model.addAttribute("selectedPatient", visit.getPatientProfile());
                model.addAttribute("selectedBranch", visit.getBranch());
                model.addAttribute("unbilledServices", invoiceService.getUnbilledPerformedServices(visitId));

                Long pid = visit.getPatientProfile().getProfileId();
                patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(pid)
                        .ifPresent(w -> model.addAttribute("loyaltyWallet", w));

                model.addAttribute("availablePromotions", promotionRepository.findAvailablePromotions(visit.getBranch().getBranchId(), LocalDateTime.now()));
                model.addAttribute("availableVouchers", patientVoucherRepository.findAvailableVouchersForPatient(pid, VoucherStatus.AVAILABLE, LocalDateTime.now()));
            });
        } else if (profileId != null) {
            patientProfileRepository.findById(profileId).ifPresent(patient -> {
                model.addAttribute("selectedPatient", patient);
                List<Visit> visits = visitRepository.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(profileId);
                model.addAttribute("patientVisits", visits);
                patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(profileId)
                        .ifPresent(w -> model.addAttribute("loyaltyWallet", w));
            });
        }

        return "finance/invoices/create";
    }

    @PostMapping("/create")
    public String saveInvoice(
            @ModelAttribute InvoiceCreateRequestDTO request,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        try {
            Long cashierUserId = getCurrentUserId(session);
            Invoice invoice = invoiceService.createInvoice(request, cashierUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã tạo hóa đơn thành công: " + invoice.getInvoiceCode());
            return "redirect:/invoices/" + invoice.getInvoiceId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi tạo hóa đơn: " + e.getMessage());
            return "redirect:/invoices/create" + (request.getVisitId() != null ? "?visitId=" + request.getVisitId() : "");
        }
    }

    @GetMapping("/{id}")
    public String invoiceDetail(
            @PathVariable Long id,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        Invoice invoice = invoiceService.getInvoiceWithDetails(id);
        resolveBranchId(invoice.getBranch().getBranchId(), currentUserId);

        List<Payment> payments = paymentService.getPaymentsByInvoiceId(id);
        List<Refund> refunds = refundService.getRefundsByInvoiceId(id);
        List<InvoiceAuditLog> auditLogs = invoiceService.getInvoiceAuditLogs(id);

        model.addAttribute("invoice", invoice);
        model.addAttribute("payments", payments);
        model.addAttribute("refunds", refunds);
        model.addAttribute("auditLogs", auditLogs);

        return "finance/invoices/detail";
    }

    @GetMapping("/{id}/print")
    public String printInvoice(
            @PathVariable Long id,
            @RequestParam(defaultValue = "A4") String format,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        Invoice invoice = invoiceService.getInvoiceWithDetails(id);
        resolveBranchId(invoice.getBranch().getBranchId(), currentUserId);

        List<Payment> payments = paymentService.getPaymentsByInvoiceId(id);

        model.addAttribute("invoice", invoice);
        model.addAttribute("payments", payments);
        model.addAttribute("printFormat", format); // A4, A5, POS

        return "finance/invoices/print";
    }

    @GetMapping("/{id}/adjust")
    public String adjustInvoiceForm(
            @PathVariable Long id,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        Invoice invoice = invoiceService.getInvoiceWithDetails(id);
        resolveBranchId(invoice.getBranch().getBranchId(), currentUserId);

        List<InvoiceAuditLog> auditLogs = invoiceService.getInvoiceAuditLogs(id);

        model.addAttribute("invoice", invoice);
        model.addAttribute("auditLogs", auditLogs);

        return "finance/invoices/adjust";
    }

    @PostMapping("/{id}/adjust")
    public String saveAdjustment(
            @PathVariable Long id,
            @ModelAttribute InvoiceAdjustmentRequestDTO request,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        try {
            Long staffUserId = getCurrentUserId(session);
            invoiceService.adjustInvoice(id, request, staffUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Điều chỉnh hóa đơn thành công!");
            return "redirect:/invoices/" + id;
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi điều chỉnh hóa đơn: " + e.getMessage());
            return "redirect:/invoices/" + id + "/adjust";
        }
    }

    @PostMapping("/{id}/cancel")
    public String cancelInvoice(
            @PathVariable Long id,
            @RequestParam String reason,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        try {
            Long staffUserId = getCurrentUserId(session);
            invoiceService.cancelInvoice(id, reason, staffUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã hủy hóa đơn thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi hủy hóa đơn: " + e.getMessage());
        }
        return "redirect:/invoices/" + id;
    }
}
