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

import com.cvdcms.DTO.MedicineInvoiceCreateRequestDTO;
import com.cvdcms.DTO.PrescriptionResponseDTO;
import com.cvdcms.entity.enums.PrescriptionStatus;
import com.cvdcms.repository.PrescriptionRepository;
import com.cvdcms.repository.TreatmentPlanRepository;

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
    private final PrescriptionRepository prescriptionRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.PaymentAllocationService paymentAllocationService;

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
            StaffBranchAssignmentRepository staffBranchAssignmentRepository,
            PrescriptionRepository prescriptionRepository,
            TreatmentPlanRepository treatmentPlanRepository
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
        this.prescriptionRepository = prescriptionRepository;
        this.treatmentPlanRepository = treatmentPlanRepository;
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

                // Bàn giao đơn thuốc từ ca khám này
                List<PrescriptionResponseDTO> handedOverPrescriptions = invoiceService.getHandedOverPrescriptions(visit.getBranch().getBranchId(), visitId, pid);
                model.addAttribute("handedOverPrescriptions", handedOverPrescriptions);

                // Kế hoạch điều trị tham khảo (không tính vào hóa đơn)
                model.addAttribute("referenceTreatmentPlans", treatmentPlanRepository.findByProfileIdWithDetails(pid));
            });
        } else if (profileId != null) {
            patientProfileRepository.findById(profileId).ifPresent(patient -> {
                model.addAttribute("selectedPatient", patient);
                List<Visit> visits = visitRepository.findByPatientProfile_ProfileIdOrderByCheckInAtDesc(profileId);
                model.addAttribute("patientVisits", visits);
                patientLoyaltyWalletRepository.findByPatientProfile_ProfileId(profileId)
                        .ifPresent(w -> model.addAttribute("loyaltyWallet", w));

                // Bàn giao đơn thuốc của bệnh nhân
                List<PrescriptionResponseDTO> handedOverPrescriptions = invoiceService.getHandedOverPrescriptions(effectiveBranchId, null, profileId);
                model.addAttribute("handedOverPrescriptions", handedOverPrescriptions);

                // Kế hoạch điều trị tham khảo
                model.addAttribute("referenceTreatmentPlans", treatmentPlanRepository.findByProfileIdWithDetails(profileId));
            });
        } else {
            // Hiển thị các đơn thuốc chờ xử lý tại chi nhánh
            List<PrescriptionResponseDTO> handedOverPrescriptions = invoiceService.getHandedOverPrescriptions(effectiveBranchId, null, null);
            model.addAttribute("handedOverPrescriptions", handedOverPrescriptions);
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
            redirectAttributes.addFlashAttribute("successMessage", "Đã phát hành hóa đơn thành công: " + invoice.getInvoiceCode());
            return "redirect:/invoices/" + invoice.getInvoiceId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi phát hành hóa đơn: " + e.getMessage());
            return "redirect:/invoices/create" + (request.getVisitId() != null ? "?visitId=" + request.getVisitId() : "");
        }
    }

    @GetMapping("/create-medicine")
    public String createMedicineInvoiceForm(
            @RequestParam Long prescriptionId,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        Prescription prescription = prescriptionRepository.findByIdWithDetails(prescriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc ID: " + prescriptionId));

        resolveBranchId(prescription.getBranch().getBranchId(), currentUserId);

        if (prescription.getStatus() == PrescriptionStatus.CANCELLED) {
            throw new IllegalStateException("Đơn thuốc này đã bị hủy, không thể lập hóa đơn thuốc.");
        }
        if (prescription.getStatus() == PrescriptionStatus.CLINIC_PURCHASE_COMPLETED) {
            throw new IllegalStateException("Đơn thuốc này đã mua đủ toàn bộ tại phòng khám.");
        }

        model.addAttribute("prescription", prescription);
        model.addAttribute("selectedPatient", prescription.getPatientProfile());
        model.addAttribute("selectedBranch", prescription.getBranch());
        model.addAttribute("selectedDoctor", prescription.getDoctor());
        model.addAttribute("selectedVisit", prescription.getVisit());

        return "finance/invoices/create-medicine";
    }

    @PostMapping("/create-medicine")
    public String saveMedicineInvoice(
            @ModelAttribute MedicineInvoiceCreateRequestDTO request,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        try {
            Long cashierUserId = getCurrentUserId(session);
            Invoice invoice = invoiceService.createMedicineInvoice(request, cashierUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã lập Hóa đơn thuốc riêng thành công: " + invoice.getInvoiceCode());
            return "redirect:/invoices/" + invoice.getInvoiceId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi tạo hóa đơn thuốc: " + e.getMessage());
            return "redirect:/invoices/create-medicine?prescriptionId=" + (request != null ? request.getPrescriptionId() : "");
        }
    }

    @PostMapping("/prescriptions/{id}/choice")
    public String updatePrescriptionChoice(
            @PathVariable Long id,
            @RequestParam String choice,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) Long visitId,
            @RequestParam(required = false) Long profileId,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        try {
            Long cashierUserId = getCurrentUserId(session);
            invoiceService.recordPrescriptionChoice(id, choice, note, cashierUserId);
            String choiceLabel = "EXTERNAL".equalsIgnoreCase(choice) ? "Bệnh nhân tự mua ngoài" : "Mua thuốc tại phòng khám";
            redirectAttributes.addFlashAttribute("successMessage", "Đã lưu trạng thái đơn thuốc: " + choiceLabel);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi cập nhật lựa chọn đơn thuốc: " + e.getMessage());
        }
        if (visitId != null) {
            return "redirect:/invoices/create?visitId=" + visitId;
        } else if (profileId != null) {
            return "redirect:/invoices/create?profileId=" + profileId;
        }
        return "redirect:/invoices/create";
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
        if (paymentAllocationService != null) {
            model.addAttribute("hasAllocations", paymentAllocationService.hasAllocations(id));
            model.addAttribute("allocations", paymentAllocationService.getAllocationsByInvoiceId(id));
        } else {
            model.addAttribute("hasAllocations", false);
            model.addAttribute("allocations", java.util.List.of());
        }

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
        if (paymentAllocationService != null) {
            model.addAttribute("hasAllocations", paymentAllocationService.hasAllocations(id));
            model.addAttribute("allocations", paymentAllocationService.getAllocationsByInvoiceId(id));
        } else {
            model.addAttribute("hasAllocations", false);
            model.addAttribute("allocations", java.util.List.of());
        }

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
