package com.cvdcms.controller;

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

/**
 * [Phân hệ]: Thanh toán & Hóa đơn (Giao diện Web MVC)
 * [Vai trò]: Controller điều phối toàn bộ các trang Thymeleaf dưới tiền tố `/invoices`, bao gồm tra cứu danh sách & tổng hợp công nợ hóa đơn, lập hóa đơn dịch vụ/hỗn hợp, tách lập hóa đơn thuốc riêng từ đơn thuốc, ghi nhận lựa chọn mua thuốc (tại phòng khám/tự mua ngoài), xem chi tiết, in hóa đơn (A4/A5/POS), điều chỉnh và hủy hóa đơn kèm kiểm soát phân quyền theo chi nhánh.
 */
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InvoiceRepository invoiceRepository;

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

    /**
     * [Hàm hỗ trợ]: Nạp thông tin người dùng đăng nhập từ `HttpSession` và đánh dấu menu `activeMenu = "invoices"` vào `Model` cho layout sidebar.
     */
    private void populateUserAndSidebar(Model model, HttpSession session) {
        model.addAttribute("activeMenu", "invoices");
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("displayName", session.getAttribute("displayName"));
            model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
        }
    }

    /**
     * [Hàm hỗ trợ]: Lấy `userId` của nhân viên đang đăng nhập từ `HttpSession`; ném `AccessDeniedException` nếu phiên chưa đăng nhập.
     */
    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra người dùng có vai trò Admin đang hoạt động (được phép xem/thao tác dữ liệu toàn hệ thống đa chi nhánh) hay không qua `UserRepository.hasActiveAdminRole`.
     */
    private boolean isFinanceAdminOrAccountant(Long userId) {
        return userId != null && userRepository.hasActiveAdminRole(userId);
    }

    /**
     * [Hàm hỗ trợ]: Trả về danh sách chi nhánh mà người dùng được phép xem; Admin thấy toàn bộ chi nhánh, nhân viên thường lấy theo `StaffBranchAssignment` đang `ACTIVE` (hoặc fallback về chi nhánh mặc định trên `User`).
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
     * [Hàm hỗ trợ]: Xác thực và chuẩn hóa `branchId` theo phạm vi phân công chi nhánh của nhân viên; chặn truy cập chi nhánh ngoài quyền bằng `AccessDeniedException` và tự động ép về chi nhánh phân công đầu tiên nếu nhân viên thường không truyền `branchId`.
     */
    private Long resolveBranchId(Long requestedBranchId, Long userId) {
        if (isFinanceAdminOrAccountant(userId)) {
            return requestedBranchId;
        }
        List<Long> activeBranchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
        if (activeBranchIds.isEmpty()) {
            User u = userRepository.findById(userId).orElse(null);
            if (u != null && u.getBranch() != null && u.getBranch().getBranchId() != null) {
                activeBranchIds = List.of(u.getBranch().getBranchId());
            }
        }
        if (activeBranchIds.isEmpty()) {
            throw new AccessDeniedException("Tài khoản nhân viên chưa được phân công chi nhánh làm việc hợp lệ.");
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

    /**
     * [Chức năng]: Trang `GET /invoices` hiển thị danh sách hóa đơn phân trang kèm bộ lọc chi nhánh, trạng thái thanh toán, trạng thái hóa đơn, từ khóa và khoảng ngày phát hành (`finance/invoices/list`).
     * [Mục đích]: Phục vụ thu ngân/kế toán tra cứu và đối soát hóa đơn theo quyền chi nhánh (`resolveBranchId`); gọi `InvoiceService.getInvoices` sắp xếp giảm dần theo `issuedAt`, đồng thời gọi `InvoiceRepository.sumFilteredInvoices` để tính tổng tiền hóa đơn, tổng đã thu và tổng công nợ còn lại khớp đúng bộ lọc đang áp dụng.
     */
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

        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 10 : Math.min(size, 100);

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "issuedAt"));
        Page<Invoice> invoicePage = invoiceService.getInvoices(effectiveBranchId, paymentStatus, status, keyword, startDt, endDt, pageable);

        long totalItems = invoicePage.getTotalElements();
        long fromItem = invoicePage.getContent().isEmpty() ? 0 : (long) safePage * safeSize + 1;
        long toItem = invoicePage.getContent().isEmpty() ? 0 : Math.min((long) (safePage + 1) * safeSize, totalItems);

        model.addAttribute("invoices", invoicePage.getContent());
        model.addAttribute("currentPage", safePage);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("totalPages", invoicePage.getTotalPages());
        model.addAttribute("totalElements", totalItems);
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("selectedPaymentStatus", paymentStatus);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("keyword", keyword);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);

        java.math.BigDecimal filteredTotalAmount = java.math.BigDecimal.ZERO;
        java.math.BigDecimal filteredPaidAmount = java.math.BigDecimal.ZERO;
        java.math.BigDecimal filteredRemainingDebt = java.math.BigDecimal.ZERO;
        if (invoiceRepository != null) {
            List<Object[]> sums = invoiceRepository.sumFilteredInvoices(effectiveBranchId, paymentStatus, status, startDt, endDt, keyword);
            if (sums != null && !sums.isEmpty() && sums.get(0) != null) {
                Object[] row = sums.get(0);
                if (row.length >= 1 && row[0] instanceof java.math.BigDecimal b0) {
                    filteredTotalAmount = b0;
                }
                if (row.length >= 2 && row[1] instanceof java.math.BigDecimal b1) {
                    filteredPaidAmount = b1;
                }
                if (row.length >= 3 && row[2] instanceof java.math.BigDecimal b2) {
                    filteredRemainingDebt = b2;
                }
            }
        }
        String filterBranchLabel = "Tất cả cơ sở";
        if (effectiveBranchId != null && allowedBranches != null) {
            for (Branch b : allowedBranches) {
                if (effectiveBranchId.equals(b.getBranchId()) && b.getBranchName() != null) {
                    filterBranchLabel = b.getBranchName();
                    break;
                }
            }
        }
        java.time.format.DateTimeFormatter vnDateFmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        LocalDate today = LocalDate.now();
        String filterPeriodLabel = "Toàn thời gian";
        if (startDate != null && endDate != null) {
            if (startDate.equals(today) && endDate.equals(today)) {
                filterPeriodLabel = "Hôm nay";
            } else if (startDate.equals(endDate)) {
                filterPeriodLabel = "Ngày " + startDate.format(vnDateFmt);
            } else {
                filterPeriodLabel = startDate.format(vnDateFmt) + " - " + endDate.format(vnDateFmt);
            }
        } else if (startDate != null) {
            filterPeriodLabel = "Từ " + startDate.format(vnDateFmt);
        } else if (endDate != null) {
            filterPeriodLabel = "Đến " + endDate.format(vnDateFmt);
        }
        model.addAttribute("filteredTotalAmount", filteredTotalAmount);
        model.addAttribute("filteredPaidAmount", filteredPaidAmount);
        model.addAttribute("filteredRemainingDebt", filteredRemainingDebt);
        model.addAttribute("filterBranchLabel", filterBranchLabel);
        model.addAttribute("filterPeriodLabel", filterPeriodLabel);

        return "finance/invoices/list";
    }

    /**
     * [Hàm hỗ trợ]: Làm sạch tham số `returnUrl` để chống lỗi Open Redirect, chỉ chấp nhận đường dẫn tương đối nội bộ bắt đầu bằng `/` (không bắt đầu bằng `//` và không chứa `\`).
     */
    private String sanitizeReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return null;
        }
        String trimmed = returnUrl.trim();
        if (trimmed.startsWith("/") && !trimmed.startsWith("//") && !trimmed.contains("\\")) {
            return trimmed;
        }
        return null;
    }

    /**
     * [Chức năng]: Trang `GET /invoices/create` hiển thị biểu mẫu lập hóa đơn mới (`finance/invoices/create`) theo `visitId`, `profileId` hoặc chọn từ danh sách lượt khám đang chờ thanh toán.
     * [Mục đích]: Nạp danh sách lượt khám trạng thái `WAITING_PAYMENT` trong chi nhánh phụ trách, dịch vụ đã hoàn thành chưa lập hóa đơn (`getUnbilledPerformedServices`), đơn thuốc bác sĩ đã bàn giao (`getHandedOverPrescriptions`), ví điểm Loyalty, khuyến mãi/voucher khả dụng và kế hoạch điều trị tham khảo (tự động gắn lượt khám `WAITING_PAYMENT` đầu tiên nếu chỉ truyền `profileId`).
     */
    @GetMapping("/create")
    public String createInvoiceForm(
            @RequestParam(required = false) Long visitId,
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String returnUrl,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        String safeReturnUrl = sanitizeReturnUrl(returnUrl);
        if (safeReturnUrl != null) {
            model.addAttribute("returnUrl", safeReturnUrl);
        }

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
                resolveBranchId(visit.getBranch() != null ? visit.getBranch().getBranchId() : null, currentUserId);

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
                Visit autoVisit = visits.stream()
                        .filter(v -> v.getStatus() == VisitStatus.WAITING_PAYMENT)
                        .findFirst()
                        .orElse(null);

                if (autoVisit != null) {
                    model.addAttribute("selectedVisit", autoVisit);
                    model.addAttribute("selectedBranch", autoVisit.getBranch());
                    model.addAttribute("unbilledServices", invoiceService.getUnbilledPerformedServices(autoVisit.getVisitId()));
                    model.addAttribute("availablePromotions", promotionRepository.findAvailablePromotions(autoVisit.getBranch().getBranchId(), LocalDateTime.now()));
                    model.addAttribute("availableVouchers", patientVoucherRepository.findAvailableVouchersForPatient(profileId, VoucherStatus.AVAILABLE, LocalDateTime.now()));

                    List<PrescriptionResponseDTO> handedOverPrescriptions = invoiceService.getHandedOverPrescriptions(autoVisit.getBranch().getBranchId(), autoVisit.getVisitId(), profileId);
                    model.addAttribute("handedOverPrescriptions", handedOverPrescriptions);
                } else {
                    List<PrescriptionResponseDTO> handedOverPrescriptions = invoiceService.getHandedOverPrescriptions(effectiveBranchId, null, profileId);
                    model.addAttribute("handedOverPrescriptions", handedOverPrescriptions);
                }
                // old handedOverPrescriptions removed:
                /*
                model.addAttribute("handedOverPrescriptions", handedOverPrescriptions);

                */
                // Kế hoạch điều trị tham khảo
                model.addAttribute("referenceTreatmentPlans", treatmentPlanRepository.findByProfileIdWithDetails(profileId));
            });
        } else {
            // Chưa chọn lượt khám hoặc bệnh nhân -> không hiển thị tràn danh sách đơn thuốc toàn chi nhánh
            model.addAttribute("handedOverPrescriptions", List.of());
        }

        return "finance/invoices/create";
    }

    /**
     * [Chức năng]: Xử lý `POST /invoices/create` để phát hành hóa đơn mới (hóa đơn dịch vụ, hóa đơn hỗn hợp dịch vụ + thuốc, hoặc tự động rẽ nhánh tách lập hóa đơn thuốc riêng).
     * [Mục đích]: Khi request không gắn `visitId`, không có dịch vụ nhưng có chọn mua thuốc tại phòng khám (`buyMedicineAtClinic = true` kèm `prescriptionId` và `medicineItems`), tự chuyển đổi sang `MedicineInvoiceCreateRequestDTO` và gọi `InvoiceService.createMedicineInvoice`; ngược lại gọi `InvoiceService.createInvoice`. Thành công chuyển hướng tới `/invoices/{id}`, thất bại quay lại `/invoices/create` kèm thông báo lỗi.
     */
    @PostMapping("/create")
    public String saveInvoice(
            @ModelAttribute InvoiceCreateRequestDTO request,
            @RequestParam(required = false) String returnUrl,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        String safeReturnUrl = sanitizeReturnUrl(returnUrl);
        try {
            Long cashierUserId = getCurrentUserId(session);
            boolean hasNoServices = request == null || request.getItems() == null || request.getItems().isEmpty();
            boolean isMedicineOnlyOrder = request != null
                    && request.getVisitId() == null
                    && hasNoServices
                    && Boolean.TRUE.equals(request.getBuyMedicineAtClinic())
                    && request.getPrescriptionId() != null
                    && request.getMedicineItems() != null
                    && !request.getMedicineItems().isEmpty();

            Invoice invoice;
            if (isMedicineOnlyOrder) {
                MedicineInvoiceCreateRequestDTO medReq = new MedicineInvoiceCreateRequestDTO();
                medReq.setPrescriptionId(request.getPrescriptionId());
                medReq.setNotes(request.getNotes());
                medReq.setSignatureData(request.getSignatureData());
                List<MedicineInvoiceCreateRequestDTO.Item> medItems = request.getMedicineItems().stream()
                        .filter(m -> m != null && m.getPrescriptionItemId() != null)
                        .map(m -> new MedicineInvoiceCreateRequestDTO.Item(m.getPrescriptionItemId(), m.getQuantity()))
                        .toList();
                medReq.setItems(medItems);
                invoice = invoiceService.createMedicineInvoice(medReq, cashierUserId);
            } else {
                invoice = invoiceService.createInvoice(request, cashierUserId);
            }
            redirectAttributes.addFlashAttribute("successMessage", "Đã phát hành hóa đơn thành công: " + invoice.getInvoiceCode());
            if (safeReturnUrl != null) {
                redirectAttributes.addAttribute("returnUrl", safeReturnUrl);
            }
            return "redirect:/invoices/" + invoice.getInvoiceId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi phát hành hóa đơn: " + e.getMessage());
            if (request != null && request.getVisitId() != null) {
                redirectAttributes.addAttribute("visitId", request.getVisitId());
            } else if (request != null && request.getProfileId() != null) {
                redirectAttributes.addAttribute("profileId", request.getProfileId());
            }
            if (safeReturnUrl != null) {
                redirectAttributes.addAttribute("returnUrl", safeReturnUrl);
            }
            return "redirect:/invoices/create";
        }
    }

    /**
     * [Chức năng]: Trang `GET /invoices/create-medicine` hiển thị biểu mẫu tách lập hóa đơn thuốc riêng từ một đơn thuốc bác sĩ đã kê (`finance/invoices/create-medicine`).
     * [Mục đích]: Tải chi tiết đơn thuốc theo `prescriptionId`, kiểm tra quyền chi nhánh (`resolveBranchId`) và chặn lập hóa đơn nếu đơn thuốc đã bị hủy (`CANCELLED`) hoặc đã mua đủ toàn bộ tại phòng khám (`CLINIC_PURCHASE_COMPLETED`).
     */
    @GetMapping("/create-medicine")
    public String createMedicineInvoiceForm(
            @RequestParam Long prescriptionId,
            @RequestParam(required = false) String returnUrl,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        String safeReturnUrl = sanitizeReturnUrl(returnUrl);
        if (safeReturnUrl != null) {
            model.addAttribute("returnUrl", safeReturnUrl);
        }

        Prescription prescription = prescriptionRepository.findByIdWithDetails(prescriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn thuốc ID: " + prescriptionId));

        resolveBranchId(prescription.getBranch() != null ? prescription.getBranch().getBranchId() : null, currentUserId);

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

    /**
     * [Chức năng]: Xử lý `POST /invoices/create-medicine` để phát hành hóa đơn thuốc độc lập (`InvoiceType.MEDICINE`) và xuất kho thuốc theo đơn.
     * [Mục đích]: Gọi `InvoiceService.createMedicineInvoice` với `cashierUserId` từ session; thành công chuyển hướng sang trang chi tiết `/invoices/{id}` kèm thông báo, thất bại chuyển hướng về `/invoices/create-medicine` kèm `prescriptionId` và thông báo lỗi.
     */
    @PostMapping("/create-medicine")
    public String saveMedicineInvoice(
            @ModelAttribute MedicineInvoiceCreateRequestDTO request,
            @RequestParam(required = false) String returnUrl,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        String safeReturnUrl = sanitizeReturnUrl(returnUrl);
        try {
            Long cashierUserId = getCurrentUserId(session);
            Invoice invoice = invoiceService.createMedicineInvoice(request, cashierUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã lập Hóa đơn thuốc riêng thành công: " + invoice.getInvoiceCode());
            if (safeReturnUrl != null) {
                redirectAttributes.addAttribute("returnUrl", safeReturnUrl);
            }
            return "redirect:/invoices/" + invoice.getInvoiceId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi tạo hóa đơn thuốc: " + e.getMessage());
            if (request != null && request.getPrescriptionId() != null) {
                redirectAttributes.addAttribute("prescriptionId", request.getPrescriptionId());
            }
            if (safeReturnUrl != null) {
                redirectAttributes.addAttribute("returnUrl", safeReturnUrl);
            }
            return "redirect:/invoices/create-medicine";
        }
    }

    /**
     * [Chức năng]: Xử lý `POST /invoices/prescriptions/{id}/choice` để lưu lựa chọn mua thuốc của bệnh nhân đối với một đơn thuốc (`EXTERNAL` tự mua ngoài hoặc `CLINIC` mua tại phòng khám).
     * [Mục đích]: Gọi `InvoiceService.recordPrescriptionChoice` để cập nhật trạng thái và ghi chú lựa chọn đơn thuốc, sau đó quay lại `/invoices/create` giữ nguyên ngữ cảnh `visitId` / `profileId` / `returnUrl`.
     */
    @PostMapping("/prescriptions/{id}/choice")
    public String updatePrescriptionChoice(
            @PathVariable Long id,
            @RequestParam String choice,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) Long visitId,
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) String returnUrl,
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
        String safeReturnUrl = sanitizeReturnUrl(returnUrl);
        if (visitId != null) {
            redirectAttributes.addAttribute("visitId", visitId);
        } else if (profileId != null) {
            redirectAttributes.addAttribute("profileId", profileId);
        }
        if (safeReturnUrl != null) {
            redirectAttributes.addAttribute("returnUrl", safeReturnUrl);
        }
        return "redirect:/invoices/create";
    }

    /**
     * [Chức năng]: Trang `GET /invoices/{id}` hiển thị chi tiết hóa đơn cùng lịch sử thanh toán, hoàn tiền, phân bổ thanh toán theo từng dòng và nhật ký kiểm toán (`finance/invoices/detail`).
     * [Mục đích]: Tải đầy đủ hóa đơn (`InvoiceService.getInvoiceWithDetails`), xác thực quyền chi nhánh (`resolveBranchId`), nạp `payments`, `refunds`, `auditLogs` và danh sách phân bổ công nợ chi tiết từ `PaymentAllocationService` (nếu có).
     */
    @GetMapping("/{id}")
    public String invoiceDetail(
            @PathVariable Long id,
            @RequestParam(required = false) String returnUrl,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        Invoice invoice = invoiceService.getInvoiceWithDetails(id);
        Long branchId = (invoice.getBranch() != null) ? invoice.getBranch().getBranchId() : null;
        resolveBranchId(branchId, currentUserId);

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

        String safeReturnUrl = sanitizeReturnUrl(returnUrl);
        if (safeReturnUrl != null) {
            model.addAttribute("returnUrl", safeReturnUrl);
        }

        return "finance/invoices/detail";
    }

    /**
     * [Chức năng]: Trang `GET /invoices/{id}/print` hiển thị bản in hóa đơn theo khổ giấy yêu cầu (`A4`, `A5` hoặc `POS`) trên view `finance/invoices/print`.
     * [Mục đích]: Tải chi tiết hóa đơn, kiểm tra quyền chi nhánh của nhân viên, nạp lịch sử thanh toán (`PaymentService.getPaymentsByInvoiceId`) và dữ liệu phân bổ thanh toán theo dòng (`PaymentAllocationService`) để render mẫu in chứng từ cho bệnh nhân.
     */
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
        Long branchId = (invoice.getBranch() != null) ? invoice.getBranch().getBranchId() : null;
        resolveBranchId(branchId, currentUserId);

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

    /**
     * [Chức năng]: Trang `GET /invoices/{id}/adjust` hiển thị biểu mẫu điều chỉnh hóa đơn (giảm giá, phụ thu, lý do điều chỉnh) kèm lịch sử kiểm toán (`finance/invoices/adjust`).
     * [Mục đích]: Cho phép kế toán/thu ngân có quyền tại chi nhánh mở màn hình điều chỉnh số tiền hóa đơn sau khi đã phát hành, nạp chi tiết hóa đơn và danh sách `InvoiceAuditLog` để đối chiếu.
     */
    @GetMapping("/{id}/adjust")
    public String adjustInvoiceForm(
            @PathVariable Long id,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        Invoice invoice = invoiceService.getInvoiceWithDetails(id);
        Long branchId = (invoice.getBranch() != null) ? invoice.getBranch().getBranchId() : null;
        resolveBranchId(branchId, currentUserId);

        List<InvoiceAuditLog> auditLogs = invoiceService.getInvoiceAuditLogs(id);

        model.addAttribute("invoice", invoice);
        model.addAttribute("auditLogs", auditLogs);

        return "finance/invoices/adjust";
    }

    /**
     * [Chức năng]: Xử lý `POST /invoices/{id}/adjust` để lưu thông tin điều chỉnh giảm giá/phụ thu/tổng tiền hóa đơn.
     * [Mục đích]: Ủy quyền cho `InvoiceService.adjustInvoice` với `staffUserId` từ session; thành công chuyển hướng về trang chi tiết `/invoices/{id}`, thất bại quay lại `/invoices/{id}/adjust` kèm thông báo lỗi nghiệp vụ.
     */
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

    /**
     * [Chức năng]: Xử lý `POST /invoices/{id}/cancel` để hủy hóa đơn kèm lý do hủy bắt buộc.
     * [Mục đích]: Ủy quyền cho `InvoiceService.cancelInvoice` với `staffUserId` từ session (chỉ hủy được khi chưa phát sinh thanh toán và tự động hoàn nguyên điểm/voucher/lượt khuyến mãi), sau đó chuyển hướng về `/invoices/{id}` kèm thông báo kết quả.
     */
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
