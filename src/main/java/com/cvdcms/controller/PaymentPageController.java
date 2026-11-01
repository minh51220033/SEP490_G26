package com.cvdcms.controller;

import com.cvdcms.DTO.PatientDebtSummaryDTO;
import com.cvdcms.DTO.PaymentRequestDTO;
import com.cvdcms.DTO.RefundRequestDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.repository.TreatmentPlanRepository treatmentPlanRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.PaymentAllocationService paymentAllocationService;

    @org.springframework.beans.factory.annotation.Autowired
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

    public PaymentPageController(
            PaymentService paymentService,
            RefundService refundService,
            DebtService debtService,
            BranchRepository branchRepository,
            InvoiceRepository invoiceRepository,
            PatientProfileRepository patientProfileRepository,
            PaymentRepository paymentRepository,
            UserRepository userRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            com.cvdcms.repository.TreatmentPlanRepository treatmentPlanRepository
    ) {
        this(paymentService, refundService, debtService, branchRepository, invoiceRepository,
                patientProfileRepository, paymentRepository, userRepository, staffBranchAssignmentRepository);
        this.treatmentPlanRepository = treatmentPlanRepository;
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
            @RequestParam(name = "paymentPage", defaultValue = "0")
            int paymentPageNumber,
            @RequestParam(name = "invoicePage", defaultValue = "0")
            int invoicePageNumber,
            @RequestParam(name = "invoiceFromDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate invoiceFromDate,
            @RequestParam(name = "invoiceToDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate invoiceToDate,
            @RequestParam(name = "paymentFromDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate paymentFromDate,
            @RequestParam(name = "paymentToDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate paymentToDate,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "dashboard_finance");

        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        List<Long> allowedBranchIds = isAdmin ? null : allowedBranches.stream().map(Branch::getBranchId).toList();

        Long targetProfileId = profileId != null ? profileId : patientId;
        model.addAttribute("branches", allowedBranches);

        // Kiểm tra hợp lệ khoảng ngày cho bộ lọc Hóa đơn
        final boolean invoiceDateRangeInvalid = (invoiceFromDate != null && invoiceToDate != null && invoiceFromDate.isAfter(invoiceToDate));
        if (invoiceDateRangeInvalid) {
            model.addAttribute("invoiceDateError", "Khoảng thời gian không hợp lệ: 'Từ ngày' không được lớn hơn 'Đến ngày'.");
        }

        // Kiểm tra hợp lệ khoảng ngày cho bộ lọc Giao dịch
        final boolean paymentDateRangeInvalid = (paymentFromDate != null && paymentToDate != null && paymentFromDate.isAfter(paymentToDate));
        if (paymentDateRangeInvalid) {
            model.addAttribute("paymentDateError", "Khoảng thời gian không hợp lệ: 'Từ ngày' không được lớn hơn 'Đến ngày'.");
        }

        // Giữ dữ liệu ngày người dùng đã nhập
        model.addAttribute("invoiceFromDate", invoiceFromDate);
        model.addAttribute("invoiceToDate", invoiceToDate);
        model.addAttribute("paymentFromDate", paymentFromDate);
        model.addAttribute("paymentToDate", paymentToDate);

        // Quy đổi ngày sang khoảng LocalDateTime: [Từ ngày 00:00:00, Đến ngày + 1 00:00:00)
        LocalDateTime invoiceStartDt = (invoiceFromDate != null && !invoiceDateRangeInvalid)
                ? invoiceFromDate.atStartOfDay()
                : null;
        LocalDateTime invoiceEndDt = (invoiceToDate != null && !invoiceDateRangeInvalid)
                ? invoiceToDate.plusDays(1).atStartOfDay()
                : null;

        LocalDateTime paymentStartDt = (paymentFromDate != null && !paymentDateRangeInvalid)
                ? paymentFromDate.atStartOfDay()
                : null;
        LocalDateTime paymentEndDt = (paymentToDate != null && !paymentDateRangeInvalid)
                ? paymentToDate.plusDays(1).atStartOfDay()
                : null;

        if (targetProfileId != null) {
            patientProfileRepository.findById(targetProfileId).ifPresent(patient -> {
                PatientDebtSummaryDTO summary = debtService.getPatientDebtDetail(patient.getProfileId(), allowedBranchIds);
                List<Invoice> invoices = (allowedBranchIds != null)
                        ? invoiceRepository.findInvoicesByPatientAndBranches(patient.getProfileId(), allowedBranchIds)
                        : invoiceRepository.findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(patient.getProfileId(), InvoiceStatus.CANCELLED);

                Pageable paymentPageable = PageRequest.of(
                        Math.max(0, paymentPageNumber),
                        10,
                        Sort.by(
                                Sort.Order.desc("paidAt"),
                                Sort.Order.desc("paymentId")
                        )
                );

                Page<Payment> paymentResult;

                if (paymentDateRangeInvalid) {
                    paymentResult = Page.empty(paymentPageable);
                } else if (allowedBranchIds == null) {
                    // Admin xem giao dịch ở mọi chi nhánh có lọc ngày
                    paymentResult = paymentRepository.findPaymentsByPatientAndDates(
                            patient.getProfileId(),
                            paymentStartDt,
                            paymentEndDt,
                            paymentPageable
                    );
                } else if (allowedBranchIds.isEmpty()) {
                    // Không có chi nhánh được phép: không trả giao dịch
                    paymentResult = Page.empty(paymentPageable);
                } else {
                    paymentResult = paymentRepository.findPaymentsByPatientAndBranchesAndDates(
                            patient.getProfileId(),
                            allowedBranchIds,
                            paymentStartDt,
                            paymentEndDt,
                            paymentPageable
                    );
                }

                // Nếu trang yêu cầu vượt quá số trang hiện có, quay về trang cuối.
                if (paymentResult.getTotalPages() > 0
                        && paymentResult.getNumber() >= paymentResult.getTotalPages()) {

                    paymentPageable = PageRequest.of(
                            paymentResult.getTotalPages() - 1,
                            10,
                            paymentPageable.getSort()
                    );

                    if (!paymentDateRangeInvalid) {
                        paymentResult = (allowedBranchIds == null)
                                ? paymentRepository.findPaymentsByPatientAndDates(
                                        patient.getProfileId(),
                                        paymentStartDt,
                                        paymentEndDt,
                                        paymentPageable)
                                : paymentRepository.findPaymentsByPatientAndBranchesAndDates(
                                        patient.getProfileId(),
                                        allowedBranchIds,
                                        paymentStartDt,
                                        paymentEndDt,
                                        paymentPageable);
                    }
                }

                model.addAttribute("paymentPage", paymentResult);
                model.addAttribute("payments", paymentResult.getContent());

                model.addAttribute(
                        "paymentStartPage",
                        Math.max(0, paymentResult.getNumber() - 2)
                );

                model.addAttribute(
                        "paymentEndPage",
                        Math.max(0, Math.min(
                                paymentResult.getTotalPages() - 1,
                                paymentResult.getNumber() + 2
                        ))
                );
                model.addAttribute("patient", patient);
                model.addAttribute("summary", summary);
                model.addAttribute("invoices", invoices);

                // Phân trang riêng cho bảng hóa đơn
                Pageable invoicePageable = PageRequest.of(
                        Math.max(0, invoicePageNumber),
                        10,
                        Sort.by(
                                Sort.Order.desc("issuedAt"),
                                Sort.Order.desc("invoiceId")
                        )
                );

                Page<Invoice> invoiceResult;

                if (invoiceDateRangeInvalid) {
                    invoiceResult = Page.empty(invoicePageable);
                } else if (allowedBranchIds == null) {
                    invoiceResult = invoiceRepository.findInvoicesByPatientAndDates(
                            patient.getProfileId(),
                            InvoiceStatus.CANCELLED,
                            invoiceStartDt,
                            invoiceEndDt,
                            invoicePageable
                    );
                } else if (allowedBranchIds.isEmpty()) {
                    invoiceResult = Page.empty(invoicePageable);
                } else {
                    invoiceResult = invoiceRepository.findInvoicesByPatientAndBranchesAndDates(
                            patient.getProfileId(),
                            InvoiceStatus.CANCELLED,
                            allowedBranchIds,
                            invoiceStartDt,
                            invoiceEndDt,
                            invoicePageable
                    );
                }

                // Nếu số trang vượt giới hạn, chuyển về trang cuối.
                if (invoiceResult.getTotalPages() > 0
                        && invoiceResult.getNumber() >= invoiceResult.getTotalPages()) {

                    invoicePageable = PageRequest.of(
                            invoiceResult.getTotalPages() - 1,
                            10,
                            invoicePageable.getSort()
                    );

                    if (!invoiceDateRangeInvalid) {
                        invoiceResult = (allowedBranchIds == null)
                                ? invoiceRepository.findInvoicesByPatientAndDates(
                                        patient.getProfileId(),
                                        InvoiceStatus.CANCELLED,
                                        invoiceStartDt,
                                        invoiceEndDt,
                                        invoicePageable)
                                : invoiceRepository.findInvoicesByPatientAndBranchesAndDates(
                                        patient.getProfileId(),
                                        InvoiceStatus.CANCELLED,
                                        allowedBranchIds,
                                        invoiceStartDt,
                                        invoiceEndDt,
                                        invoicePageable);
                    }
                }

                model.addAttribute("invoicePage", invoiceResult);
                model.addAttribute("invoiceRows", invoiceResult.getContent());
                model.addAttribute(
                        "invoiceStartPage",
                        Math.max(0, invoiceResult.getNumber() - 2)
                );
                model.addAttribute(
                        "invoiceEndPage",
                        Math.max(0, Math.min(
                                invoiceResult.getTotalPages() - 1,
                                invoiceResult.getNumber() + 2
                        ))
                );
                model.addAttribute("branches", allowedBranches);
                model.addAttribute("treatmentPlans", treatmentPlanRepository != null ? treatmentPlanRepository.findByProfileIdWithDetails(patient.getProfileId()) : java.util.Collections.emptyList());
                model.addAttribute("refunds", refundService.getRefundsByProfileId(patient.getProfileId(), allowedBranchIds));
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
        List<com.cvdcms.DTO.PaymentAllocationDTO> allocations = java.util.Collections.emptyList();
        if (paymentAllocationService != null) {
            allocations = paymentAllocationService.getAllocationsByPaymentId(id);
        }
        model.addAttribute("allocations", allocations);
        model.addAttribute("hasAllocations", allocations != null && !allocations.isEmpty());
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
        String returnUrl = request.getProfileId() != null
                ? "redirect:/payments/dashboard?patientId="
                + request.getProfileId()
                : "redirect:/payments/dashboard";

        try {
            Long cashierUserId = getCurrentUserId(session);

            Payment deposit = paymentService.processAdvanceDeposit(
                    request, cashierUserId
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Nộp tiền tạm ứng "
                            + deposit.getPaymentCode()
                            + " thành công ("
                            + deposit.getAmount() + " đ)!"
            );
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Lỗi nộp tiền tạm ứng: " + e.getMessage()
            );
        }

        return returnUrl;
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
            @RequestParam(defaultValue = "false") boolean returnToDashboard,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        try {
            Long staffUserId = getCurrentUserId(session);
            Refund refund = refundService.processRefund(request, staffUserId);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Hoàn tiền thành công. Mã phiếu: " + refund.getRefundCode()
            );

            if (returnToDashboard) {
                return "redirect:/payments/dashboard?patientId="
                        + refund.getPatientProfile().getProfileId();
            }

            if (request.getInvoiceId() != null) {
                return "redirect:/invoices/" + request.getInvoiceId();
            }

            return "redirect:/payments/refunds";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Lỗi hoàn tiền: " + e.getMessage()
            );

            if (returnToDashboard && request.getProfileId() != null) {
                return "redirect:/payments/dashboard?patientId="
                        + request.getProfileId();
            }

            return "redirect:/payments/refunds";
        }
    }

    @GetMapping("/debts")
    public String listDebts(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) InvoicePaymentStatus paymentStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "debts");

        boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        List<Long> allowedBranchIds = isAdmin ? null : allowedBranches.stream().map(Branch::getBranchId).toList();

        Long effectiveBranchId = (branchId != null) ? resolveBranchId(branchId, currentUserId) : null;

        Pageable pageable = PageRequest.of(page, size);
        Page<PatientDebtSummaryDTO> debtPage = debtService.getPatientDebts(effectiveBranchId, allowedBranchIds, paymentStatus, keyword, pageable);

        model.addAttribute("patientDebts", debtPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", debtPage.getTotalPages());
        model.addAttribute("totalElements", debtPage.getTotalElements());
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("selectedPaymentStatus", paymentStatus);
        model.addAttribute("keyword", keyword);

        return "finance/payments/debts";
    }
}
