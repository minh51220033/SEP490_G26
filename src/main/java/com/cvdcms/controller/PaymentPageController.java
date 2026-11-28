package com.cvdcms.controller;

import com.cvdcms.DTO.FinancialMetricsDTO;
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
import com.cvdcms.repository.OperatingExpenseRepository;
import com.cvdcms.repository.PatientProfileRepository;
import com.cvdcms.repository.PaymentRepository;
import com.cvdcms.repository.RefundRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.SupplierPaymentRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * [Phân hệ]: Thu ngân & Quản lý Thanh toán, Ví tạm ứng, Hoàn tiền, Công nợ
 * [Vai trò]: Controller chính (`/payments`) điều phối toàn bộ màn hình nghiệp vụ thu ngân gồm Tổng quan thu ngân/bệnh nhân, Lịch sử thu tiền, In biên lai, Ví tạm ứng, Hoàn tiền, Công nợ và các API JSON hỗ trợ form thu/hoàn.
 */
@Controller
@RequestMapping("/payments")
public class PaymentPageController {

    private static final DateTimeFormatter VN_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.CashierOverviewService cashierOverviewService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cvdcms.service.InvoiceService invoiceService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RefundRepository refundRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperatingExpenseRepository operatingExpenseRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SupplierPaymentRepository supplierPaymentRepository;

    /**
     * [Hàm hỗ trợ]: Tiêm `CashierOverviewService` phục vụ kiểm thử hoặc cấu hình phụ thuộc tùy chọn.
     */
    public void setCashierOverviewService(com.cvdcms.service.CashierOverviewService cashierOverviewService) {
        this.cashierOverviewService = cashierOverviewService;
    }

    /**
     * [Hàm hỗ trợ]: Tiêm `InvoiceService` phục vụ tính toán chỉ số tài chính tổng quan trên dashboard thu ngân.
     */
    public void setInvoiceService(com.cvdcms.service.InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    /**
     * [Hàm hỗ trợ]: Khởi tạo controller thu ngân với các service thanh toán, hoàn tiền, công nợ và các repository nền tảng.
     */
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

    /**
     * [Hàm hỗ trợ]: Constructor mở rộng kèm `TreatmentPlanRepository` phục vụ kiểm thử hoặc khởi tạo thủ công.
     */
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

    /**
     * [Hàm hỗ trợ]: Đẩy trạng thái menu đang chọn (`activeMenu`) và thông tin tài khoản phiên đăng nhập vào `Model` để render layout sidebar.
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
     * [Hàm hỗ trợ]: Trích xuất `userId` của người đang đăng nhập từ `HttpSession` hoặc ném `AccessDeniedException` nếu chưa đăng nhập.
     */
    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

    /**
     * [Hàm hỗ trợ]: Kiểm tra tài khoản hiện tại có vai trò Admin đang hoạt động (được phép xem/thao tác toàn hệ thống) hay không.
     */
    private boolean isFinanceAdminOrAccountant(Long userId) {
        return userId != null && userRepository.hasActiveAdminRole(userId);
    }

    /**
     * [Hàm hỗ trợ]: Xác định danh sách chi nhánh mà người dùng được phép xem dữ liệu tài chính (Admin lấy toàn bộ; nhân sự khác lấy theo phân công `ACTIVE` hoặc chi nhánh gốc).
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
     * [Hàm hỗ trợ]: Chuẩn hóa và kiểm tra quyền truy cập đối với `requestedBranchId`; Admin giữ nguyên giá trị lọc, nhân sự chi nhánh tự động chọn chi nhánh phân công đầu tiên nếu bỏ trống và ném `AccessDeniedException` nếu truy cập ngoài phạm vi.
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
            return activeBranchIds.get(0);
        }
        if (!activeBranchIds.contains(requestedBranchId)) {
            throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu tại chi nhánh ID: " + requestedBranchId);
        }
        return requestedBranchId;
    }

    /**
     * [Hàm hỗ trợ]: Quy đổi giá trị tiền `BigDecimal` bị `null` về `BigDecimal.ZERO` để tránh `NullPointerException`.
     */
    private static BigDecimal safeMoney(BigDecimal val) {
        return val != null ? val : BigDecimal.ZERO;
    }

    /**
     * [Hàm hỗ trợ]: Định dạng nhãn khoảng thời gian lọc (`Hôm nay`, `Ngày dd/MM/yyyy`, khoảng ngày hoặc `Toàn thời gian`) để hiển thị trên thanh tóm tắt bộ lọc.
     */
    private static String formatFilterPeriodLabel(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        if (from != null && to != null) {
            if (from.equals(today) && to.equals(today)) {
                return "Hôm nay";
            }
            if (from.equals(to)) {
                return "Ngày " + from.format(VN_DATE_FMT);
            }
            return from.format(VN_DATE_FMT) + " - " + to.format(VN_DATE_FMT);
        }
        if (from != null) {
            return "Từ " + from.format(VN_DATE_FMT);
        }
        if (to != null) {
            return "Đến " + to.format(VN_DATE_FMT);
        }
        return "Toàn thời gian";
    }

    /**
     * [Hàm hỗ trợ]: Tra cứu tên chi nhánh đang được lọc từ danh sách chi nhánh được phép hoặc trả về `"Tất cả cơ sở"`.
     */
    private static String resolveFilterBranchLabel(Long branchId, List<Branch> allowedBranches) {
        if (branchId != null && allowedBranches != null) {
            for (Branch b : allowedBranches) {
                if (branchId.equals(b.getBranchId()) && b.getBranchName() != null) {
                    return b.getBranchName();
                }
            }
        }
        return "Tất cả cơ sở";
    }

    /**
     * [Chức năng]: Hiển thị màn hình lịch sử các đợt thanh toán (`GET /payments`, view `finance/payments/list`).
     * [Mục đích]: Hỗ trợ thu ngân/kế toán tra cứu và phân trang toàn bộ phiếu thu theo phạm vi chi nhánh được phân công, loại thanh toán (`PAYMENT`/`ADVANCE_DEPOSIT`), phương thức thanh toán, từ khóa và khoảng ngày, đồng thời tính tổng tiền thực thu theo đúng bộ lọc.
     */
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

        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 10 : Math.min(size, 100);

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "paidAt"));
        Page<Payment> paymentPage = paymentService.getPayments(effectiveBranchId, paymentType, paymentMethod, keyword, startDt, endDt, pageable);

        long totalItems = paymentPage.getTotalElements();
        long fromItem = paymentPage.getContent().isEmpty() ? 0 : (long) safePage * safeSize + 1;
        long toItem = paymentPage.getContent().isEmpty() ? 0 : Math.min((long) (safePage + 1) * safeSize, totalItems);

        BigDecimal filteredTotalAmount = safeMoney(paymentRepository != null
                ? paymentRepository.sumFilteredPayments(effectiveBranchId, paymentType, paymentMethod, startDt, endDt, keyword)
                : null);

        model.addAttribute("payments", paymentPage.getContent());
        model.addAttribute("currentPage", safePage);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("totalPages", paymentPage.getTotalPages());
        model.addAttribute("totalElements", totalItems);
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("selectedPaymentType", paymentType);
        model.addAttribute("selectedPaymentMethod", paymentMethod);
        model.addAttribute("keyword", keyword);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("filteredTotalAmount", filteredTotalAmount);
        model.addAttribute("filterPeriodLabel", formatFilterPeriodLabel(startDate, endDate));
        model.addAttribute("filterBranchLabel", resolveFilterBranchLabel(effectiveBranchId, allowedBranches));

        return "finance/payments/list";
    }

    /**
     * [Hàm hỗ trợ]: Overload giữ tương thích ngược cho `patientFinancialDashboard` (không truyền bộ lọc danh sách chờ và trạng thái thanh toán hóa đơn).
     */
    public String patientFinancialDashboard(
            Long profileId,
            Long patientId,
            int paymentPageNumber,
            int invoicePageNumber,
            LocalDate invoiceFromDate,
            LocalDate invoiceToDate,
            LocalDate paymentFromDate,
            LocalDate paymentToDate,
            Model model,
            HttpSession session
    ) {
        return patientFinancialDashboard(profileId, patientId, null, 0, paymentPageNumber, invoicePageNumber,
                null, invoiceFromDate, invoiceToDate, paymentFromDate, paymentToDate, model, session);
    }

    /**
     * [Hàm hỗ trợ]: Overload giữ tương thích ngược cho `patientFinancialDashboard` (có `keyword` và `pendingPageNumber`, chưa lọc trạng thái hóa đơn).
     */
    public String patientFinancialDashboard(
            Long profileId,
            Long patientId,
            String keyword,
            int pendingPageNumber,
            int paymentPageNumber,
            int invoicePageNumber,
            LocalDate invoiceFromDate,
            LocalDate invoiceToDate,
            LocalDate paymentFromDate,
            LocalDate paymentToDate,
            Model model,
            HttpSession session
    ) {
        return patientFinancialDashboard(profileId, patientId, keyword, pendingPageNumber, paymentPageNumber, invoicePageNumber,
                null, invoiceFromDate, invoiceToDate, paymentFromDate, paymentToDate, model, session);
    }

    /**
     * [Hàm hỗ trợ]: Overload giữ tương thích ngược cho `patientFinancialDashboard` (chưa truyền các bộ lọc mở rộng theo chi nhánh/ngày/trạng thái chờ).
     */
    public String patientFinancialDashboard(
            Long profileId,
            Long patientId,
            String keyword,
            int pendingPageNumber,
            int paymentPageNumber,
            int invoicePageNumber,
            String invoicePaymentStatus,
            LocalDate invoiceFromDate,
            LocalDate invoiceToDate,
            LocalDate paymentFromDate,
            LocalDate paymentToDate,
            Model model,
            HttpSession session
    ) {
        return patientFinancialDashboard(
                profileId, patientId, keyword,
                null, null, null, null, null, null, null, null,
                pendingPageNumber, paymentPageNumber, invoicePageNumber,
                invoicePaymentStatus,
                invoiceFromDate, invoiceToDate,
                paymentFromDate, paymentToDate,
                model, session
        );
    }

    /**
     * [Chức năng]: Hiển thị màn hình Tổng quan Thu ngân và Dashboard tài chính chi tiết theo bệnh nhân (`GET /payments/dashboard`, `/payments/patient/{profileId}`, view `finance/payments/dashboard`).
     * [Mục đích]: Khi chưa chọn bệnh nhân: tổng hợp KPI doanh thu thực thu, hoàn tiền, thực thu ròng khách hàng, chỉ số hóa đơn và danh sách bệnh nhân chờ lập hóa đơn/thu tiền từ `CashierOverviewService` theo đúng chi nhánh phân quyền; khi đã chọn bệnh nhân: tải tổng hợp công nợ (`DebtService`), ví tạm ứng, danh sách hóa đơn/giao dịch phân trang theo ngày, kế hoạch điều trị và lịch sử hoàn tiền.
     */
    @GetMapping({"/dashboard", "/patient/{profileId}"})
    public String patientFinancialDashboard(
            @PathVariable(required = false) Long profileId,
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "pendingBranchId", required = false)
            Long pendingBranchId,
            @RequestParam(name = "branchId", required = false)
            Long branchId,
            @RequestParam(name = "pendingFromDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate pendingFromDate,
            @RequestParam(name = "fromDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam(name = "pendingToDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate pendingToDate,
            @RequestParam(name = "toDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate,
            @RequestParam(name = "pendingStatus", required = false)
            String pendingStatus,
            @RequestParam(name = "status", required = false)
            String status,
            @RequestParam(name = "pendingPage", defaultValue = "0")
            int pendingPageNumber,
            @RequestParam(name = "paymentPage", defaultValue = "0")
            int paymentPageNumber,
            @RequestParam(name = "invoicePage", defaultValue = "0")
            int invoicePageNumber,
            @RequestParam(name = "invoicePaymentStatus", required = false)
            String invoicePaymentStatus,
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

        Long effectivePendingBranchId = pendingBranchId != null ? pendingBranchId : branchId;
        LocalDate effectivePendingFromDate = pendingFromDate != null ? pendingFromDate : fromDate;
        LocalDate effectivePendingToDate = pendingToDate != null ? pendingToDate : toDate;
        String effectivePendingStatus = (pendingStatus != null && !pendingStatus.isBlank()) ? pendingStatus : status;

        if (effectivePendingFromDate != null && effectivePendingToDate != null && effectivePendingFromDate.isAfter(effectivePendingToDate)) {
            model.addAttribute("pendingDateError", "Khoảng thời gian không hợp lệ: 'Từ ngày' không được lớn hơn 'Đến ngày'.");
        }

        // Bổ sung bảng danh sách bệnh nhân chờ lập hóa đơn / thu tiền trên tab Tổng quan (Requirement 2 & 7)
        int effectivePendingPage = Math.max(0, pendingPageNumber);
        Pageable pendingPageable = PageRequest.of(effectivePendingPage, 10);
        if (cashierOverviewService != null) {
            Page<com.cvdcms.DTO.CashierPendingPatientDTO> pendingPatientsPage = cashierOverviewService.getPendingCashierPatients(
                    keyword,
                    effectivePendingBranchId,
                    effectivePendingFromDate,
                    effectivePendingToDate,
                    effectivePendingStatus,
                    allowedBranchIds,
                    pendingPageable
            );
            model.addAttribute("pendingPatientsPage", pendingPatientsPage);
            model.addAttribute("pendingPatients", pendingPatientsPage.getContent());
        } else {
            model.addAttribute("pendingPatientsPage", Page.empty(pendingPageable));
            model.addAttribute("pendingPatients", java.util.Collections.emptyList());
        }
        model.addAttribute("pendingKeyword", (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null);
        model.addAttribute("pendingBranchId", effectivePendingBranchId);
        model.addAttribute("pendingFromDate", effectivePendingFromDate);
        model.addAttribute("pendingToDate", effectivePendingToDate);
        model.addAttribute("pendingStatus", (effectivePendingStatus != null && !effectivePendingStatus.isBlank()) ? effectivePendingStatus.trim() : null);
        model.addAttribute("currentPendingPage", effectivePendingPage);

        final boolean pendingDateRangeInvalid = effectivePendingFromDate != null
                && effectivePendingToDate != null
                && effectivePendingFromDate.isAfter(effectivePendingToDate);

        FinancialMetricsDTO filteredMetrics = new FinancialMetricsDTO();
        BigDecimal filteredTotalIncome = BigDecimal.ZERO;
        BigDecimal filteredRefundExpense = BigDecimal.ZERO;
        BigDecimal filteredCustomerNetIncome = BigDecimal.ZERO;
        String filterPeriodLabel = formatFilterPeriodLabel(effectivePendingFromDate, effectivePendingToDate);
        String filterBranchLabel = resolveFilterBranchLabel(effectivePendingBranchId, allowedBranches);

        if (targetProfileId == null && !pendingDateRangeInvalid) {
            LocalDateTime filterStartDt = effectivePendingFromDate != null ? effectivePendingFromDate.atStartOfDay() : null;
            LocalDateTime filterEndDt = effectivePendingToDate != null ? effectivePendingToDate.atTime(LocalTime.MAX) : null;
            LocalDateTime cashflowEndExclusive = effectivePendingToDate != null ? effectivePendingToDate.plusDays(1).atStartOfDay() : null;

            List<Long> cashflowBranchIds = null;
            boolean hasValidBranchScope = false;

            if (effectivePendingBranchId != null) {
                if (isAdmin || (allowedBranchIds != null && allowedBranchIds.contains(effectivePendingBranchId))) {
                    cashflowBranchIds = List.of(effectivePendingBranchId);
                    hasValidBranchScope = true;
                    if (invoiceService != null) {
                        FinancialMetricsDTO fetched = invoiceService.getFinancialMetrics(effectivePendingBranchId, filterStartDt, filterEndDt);
                        if (fetched != null) {
                            filteredMetrics = fetched;
                        }
                    }
                }
            } else if (isAdmin) {
                hasValidBranchScope = true;
                if (invoiceService != null) {
                    FinancialMetricsDTO fetched = invoiceService.getFinancialMetrics(null, filterStartDt, filterEndDt);
                    if (fetched != null) {
                        filteredMetrics = fetched;
                    }
                }
            } else if (allowedBranchIds != null && !allowedBranchIds.isEmpty()) {
                cashflowBranchIds = allowedBranchIds;
                hasValidBranchScope = true;
                if (invoiceService != null) {
                    if (allowedBranchIds.size() == 1) {
                        FinancialMetricsDTO fetched = invoiceService.getFinancialMetrics(allowedBranchIds.get(0), filterStartDt, filterEndDt);
                        if (fetched != null) {
                            filteredMetrics = fetched;
                        }
                    } else {
                        for (Long bId : allowedBranchIds) {
                            FinancialMetricsDTO branchMetrics = invoiceService.getFinancialMetrics(bId, filterStartDt, filterEndDt);
                            if (branchMetrics != null) {
                                filteredMetrics.setTodayTotalBilled(safeMoney(filteredMetrics.getTodayTotalBilled()).add(safeMoney(branchMetrics.getTodayTotalBilled())));
                                filteredMetrics.setTodayTotalCollected(safeMoney(filteredMetrics.getTodayTotalCollected()).add(safeMoney(branchMetrics.getTodayTotalCollected())));
                                filteredMetrics.setTodayTotalDebt(safeMoney(filteredMetrics.getTodayTotalDebt()).add(safeMoney(branchMetrics.getTodayTotalDebt())));
                                filteredMetrics.setTodayTotalRefunded(safeMoney(filteredMetrics.getTodayTotalRefunded()).add(safeMoney(branchMetrics.getTodayTotalRefunded())));
                                filteredMetrics.setTodayTotalInvoices(filteredMetrics.getTodayTotalInvoices() + branchMetrics.getTodayTotalInvoices());
                            }
                        }
                    }
                }
            }

            if (hasValidBranchScope) {
                filteredTotalIncome = safeMoney(paymentRepository != null
                        ? paymentRepository.sumRealRevenueByBranches(cashflowBranchIds, filterStartDt, cashflowEndExclusive)
                        : null);
                filteredRefundExpense = safeMoney(refundRepository != null
                        ? refundRepository.sumRefundAmountByBranches(cashflowBranchIds, filterStartDt, cashflowEndExclusive)
                        : null);
                filteredCustomerNetIncome = filteredTotalIncome.subtract(filteredRefundExpense);
            }
        }
        model.addAttribute("metrics", filteredMetrics);
        model.addAttribute("filterPeriodLabel", filterPeriodLabel);
        model.addAttribute("filterBranchLabel", filterBranchLabel);
        model.addAttribute("filteredTotalIncome", filteredTotalIncome);
        model.addAttribute("filteredRefundExpense", filteredRefundExpense);
        model.addAttribute("filteredCustomerNetIncome", filteredCustomerNetIncome);

        // Kiểm tra hợp lệ khoảng ngày cho bộ lọc Hóa đơn
        final boolean invoiceDateRangeInvalid = (invoiceFromDate != null && invoiceToDate != null && invoiceFromDate.isAfter(invoiceToDate));
        if (invoiceDateRangeInvalid) {
            model.addAttribute("invoiceDateError", "Khoảng thời gian không hợp lệ: 'Từ ngày' không được lớn hơn 'Đến ngày'.");
        }

        // Parse trạng thái thanh toán hóa đơn
        InvoicePaymentStatus tempStatus = null;
        if (invoicePaymentStatus != null && !invoicePaymentStatus.trim().isEmpty()) {
            try {
                tempStatus = InvoicePaymentStatus.valueOf(invoicePaymentStatus.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                tempStatus = null;
            }
        }
        final InvoicePaymentStatus parsedPaymentStatus = tempStatus;
        model.addAttribute("invoicePaymentStatus", parsedPaymentStatus != null ? parsedPaymentStatus.name() : "");

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
                    invoiceResult = (parsedPaymentStatus != null)
                            ? invoiceRepository.findInvoicesByPatientAndDates(
                                    patient.getProfileId(),
                                    InvoiceStatus.CANCELLED,
                                    parsedPaymentStatus,
                                    invoiceStartDt,
                                    invoiceEndDt,
                                    invoicePageable)
                            : invoiceRepository.findInvoicesByPatientAndDates(
                                    patient.getProfileId(),
                                    InvoiceStatus.CANCELLED,
                                    invoiceStartDt,
                                    invoiceEndDt,
                                    invoicePageable);
                } else if (allowedBranchIds.isEmpty()) {
                    invoiceResult = Page.empty(invoicePageable);
                } else {
                    invoiceResult = (parsedPaymentStatus != null)
                            ? invoiceRepository.findInvoicesByPatientAndBranchesAndDates(
                                    patient.getProfileId(),
                                    InvoiceStatus.CANCELLED,
                                    allowedBranchIds,
                                    parsedPaymentStatus,
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

                // Nếu số trang vượt giới hạn, chuyển về trang cuối.
                if (invoiceResult.getTotalPages() > 0
                        && invoiceResult.getNumber() >= invoiceResult.getTotalPages()) {

                    invoicePageable = PageRequest.of(
                            invoiceResult.getTotalPages() - 1,
                            10,
                            invoicePageable.getSort()
                    );

                    if (!invoiceDateRangeInvalid) {
                        if (allowedBranchIds == null) {
                            invoiceResult = (parsedPaymentStatus != null)
                                    ? invoiceRepository.findInvoicesByPatientAndDates(
                                            patient.getProfileId(),
                                            InvoiceStatus.CANCELLED,
                                            parsedPaymentStatus,
                                            invoiceStartDt,
                                            invoiceEndDt,
                                            invoicePageable)
                                    : invoiceRepository.findInvoicesByPatientAndDates(
                                            patient.getProfileId(),
                                            InvoiceStatus.CANCELLED,
                                            invoiceStartDt,
                                            invoiceEndDt,
                                            invoicePageable);
                        } else {
                            invoiceResult = (parsedPaymentStatus != null)
                                    ? invoiceRepository.findInvoicesByPatientAndBranchesAndDates(
                                            patient.getProfileId(),
                                            InvoiceStatus.CANCELLED,
                                            allowedBranchIds,
                                            parsedPaymentStatus,
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

    /**
     * [Chức năng]: Điều hướng đường dẫn tạo thanh toán cũ (`GET /payments/create`) sang trang chi tiết hóa đơn hoặc trang lập hóa đơn mới.
     * [Mục đích]: Gom luồng thu tiền về trực tiếp trang chi tiết hóa đơn (`/invoices/{invoiceId}`) nếu đã có hóa đơn, hoặc về màn hình tạo hóa đơn (`/invoices/create`) nếu chưa lập hóa đơn.
     */
    @GetMapping("/create")
    public String createPaymentForm(
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(required = false) Long profileId
    ) {
        if (invoiceId != null) {
            return "redirect:/invoices/" + invoiceId;
        }
        if (profileId != null) {
            return "redirect:/invoices/create?profileId=" + profileId;
        }
        return "redirect:/invoices/create";
    }

    /**
     * [Chức năng]: Xử lý form ghi nhận một đợt thanh toán hóa đơn (`POST /payments/create`).
     * [Mục đích]: Lấy ID thu ngân hiện tại, gọi `paymentService.processPayment` (hỗ trợ thu một phần hoặc thu đủ, trừ ví cọc/chuyển khoản/tiền mặt, phân bổ FIFO và chống ghi trùng theo `idempotencyKey`), gửi flash message và chuyển hướng về `returnUrl` hoặc trang chi tiết hóa đơn.
     */
    @PostMapping("/create")
    public String processPayment(
            @ModelAttribute PaymentRequestDTO request,
            @RequestParam(name = "returnUrl", required = false) String returnUrl,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        try {
            Long cashierUserId = getCurrentUserId(session);
            Payment payment = paymentService.processPayment(request, cashierUserId);
            redirectAttributes.addFlashAttribute("successMessage", "Ghi nhận thanh toán " + payment.getPaymentCode() + " thành công (" + payment.getAmount() + " đ)!");
            if (returnUrl != null && !returnUrl.isBlank()) {
                return "redirect:" + returnUrl;
            }
            if (payment.getInvoice() != null) {
                return "redirect:/invoices/" + payment.getInvoice().getInvoiceId();
            }
            return "redirect:/payments";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi thanh toán: " + e.getMessage());
            if (returnUrl != null && !returnUrl.isBlank()) {
                return "redirect:" + returnUrl;
            }
            return "redirect:" + (request.getInvoiceId() != null ? "/invoices/" + request.getInvoiceId() : "/invoices/create");
        }
    }

    /**
     * [Chức năng]: API JSON trả về danh sách các hóa đơn còn công nợ (`UNPAID`/`PARTIAL`) của một bệnh nhân trong phạm vi chi nhánh được phép (`GET /payments/api/patients/{profileId}/unpaid-invoices`).
     * [Mục đích]: Phục vụ modal thu nợ nhanh và các dropdown chọn hóa đơn cần thanh toán, đảm bảo thu ngân chỉ nhìn thấy hóa đơn thuộc chi nhánh mình quản lý.
     */
    @GetMapping("/api/patients/{profileId}/unpaid-invoices")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> getPatientUnpaidInvoices(
            @PathVariable Long profileId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) InvoicePaymentStatus paymentStatus,
            HttpSession session
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
            List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
            List<Long> allowedBranchIds = isAdmin ? null : allowedBranches.stream().map(Branch::getBranchId).toList();
            if (branchId != null) {
                allowedBranchIds = List.of(resolveBranchId(branchId, currentUserId));
            }
            List<Invoice> unpaid = debtService.getUnpaidInvoicesForPatient(profileId, allowedBranchIds);
            List<java.util.Map<String, Object>> data = unpaid.stream()
                    .filter(inv -> paymentStatus == null || inv.getPaymentStatus() == paymentStatus)
                    .map(inv -> {
                java.util.Map<String, Object> map = new java.util.HashMap<>();
                map.put("invoiceId", inv.getInvoiceId());
                map.put("invoiceCode", inv.getInvoiceCode());
                map.put("remainingDebt", inv.getRemainingDebt() != null ? inv.getRemainingDebt() : java.math.BigDecimal.ZERO);
                map.put("totalAmount", inv.getTotalAmount() != null ? inv.getTotalAmount() : java.math.BigDecimal.ZERO);
                map.put("paidAmount", inv.getPaidAmount() != null ? inv.getPaidAmount() : java.math.BigDecimal.ZERO);
                map.put("issuedAt", inv.getIssuedAt() != null ? inv.getIssuedAt().toString() : "");
                map.put("branchName", inv.getBranch() != null ? inv.getBranch().getBranchName() : "");
                return map;
            }).toList();
            return org.springframework.http.ResponseEntity.ok(data);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * [Chức năng]: API JSON trả về các nguồn có thể hoàn tiền của một bệnh nhân gồm số dư ví tạm ứng và danh sách hóa đơn đã có tiền thực thu (`GET /payments/api/patients/{profileId}/refund-sources`).
     * [Mục đích]: Cung cấp dữ liệu cho modal tạo phiếu hoàn tiền giúp kế toán chọn hoàn từ số dư ví cọc hoặc hoàn từ một hóa đơn cụ thể (`status != CANCELLED` và `paidAmount > 0`) thuộc chi nhánh được phân quyền.
     */
    @GetMapping("/api/patients/{profileId}/refund-sources")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> getPatientRefundSources(
            @PathVariable Long profileId,
            HttpSession session
    ) {
        try {
            Long currentUserId = getCurrentUserId(session);
            boolean isAdmin = userRepository.hasActiveAdminRole(currentUserId);
            List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
            List<Long> allowedBranchIds = isAdmin ? null : allowedBranches.stream().map(Branch::getBranchId).toList();

            BigDecimal depositBalance = BigDecimal.ZERO;
            var patientOpt = patientProfileRepository.findById(profileId);
            if (patientOpt.isPresent()) {
                depositBalance = safeMoney(patientOpt.get().getDepositBalance());
            }

            List<Invoice> patientInvoices = (isAdmin || (allowedBranchIds != null && !allowedBranchIds.isEmpty()))
                    ? invoiceRepository.findInvoicesByPatientAndBranches(profileId, allowedBranchIds)
                    : java.util.Collections.emptyList();

            List<java.util.Map<String, Object>> refundableInvoices = patientInvoices.stream()
                    .filter(inv -> inv.getStatus() != InvoiceStatus.CANCELLED
                            && inv.getPaidAmount() != null
                            && inv.getPaidAmount().compareTo(BigDecimal.ZERO) > 0)
                    .map(inv -> {
                        java.util.Map<String, Object> map = new java.util.HashMap<>();
                        map.put("invoiceId", inv.getInvoiceId());
                        map.put("invoiceCode", inv.getInvoiceCode());
                        map.put("totalAmount", safeMoney(inv.getTotalAmount()));
                        map.put("paidAmount", safeMoney(inv.getPaidAmount()));
                        map.put("remainingDebt", safeMoney(inv.getRemainingDebt()));
                        map.put("paymentStatus", inv.getPaymentStatus() != null ? inv.getPaymentStatus().name() : "");
                        map.put("branchId", inv.getBranch() != null ? inv.getBranch().getBranchId() : null);
                        map.put("branchName", inv.getBranch() != null ? inv.getBranch().getBranchName() : "");
                        return map;
                    })
                    .toList();

            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("depositBalance", depositBalance);
            result.put("invoices", refundableInvoices);
            return org.springframework.http.ResponseEntity.ok(result);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * [Chức năng]: Hiển thị trang in biên lai/phiếu thu cho một giao dịch thanh toán (`GET /payments/{id}/receipt`, view `finance/payments/receipt`).
     * [Mục đích]: Tải chi tiết phiếu thu, kiểm tra quyền truy cập chi nhánh của phiếu thu và đính kèm bảng chi tiết phân bổ FIFO từng dòng (`PaymentAllocationDTO`) để in giao khách hàng.
     */
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

    /**
     * [Chức năng]: Hiển thị danh sách lịch sử nạp tiền tạm ứng vào ví cọc của bệnh nhân (`GET /payments/deposits`, view `finance/payments/deposits`).
     * [Mục đích]: Lọc và phân trang các phiếu thu loại `ADVANCE_DEPOSIT` theo chi nhánh được phân công, từ khóa, khoảng ngày và tính tổng tiền tạm ứng đã thu; nếu truyền `profileId` thì chuyển thẳng sang trang tạo tạm ứng cho bệnh nhân đó.
     */
    @GetMapping("/deposits")
    public String listDeposits(
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session, "deposits");
        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        model.addAttribute("branches", allowedBranches);

        Long effectiveBranchId = resolveBranchId(branchId, currentUserId);

        LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDt = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "paidAt"));
        Page<Payment> depositPage = paymentService.getPayments(
                effectiveBranchId,
                PaymentType.ADVANCE_DEPOSIT,
                null,
                keyword,
                startDt,
                endDt,
                pageable
        );

        long totalItems = depositPage.getTotalElements();
        long fromItem = totalItems == 0 ? 0 : (long) page * size + 1;
        long toItem = Math.min((long) (page + 1) * size, totalItems);

        model.addAttribute("deposits", depositPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", depositPage.getTotalPages());
        model.addAttribute("totalElements", totalItems);
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);

        BigDecimal filteredTotalAmount = safeMoney(paymentRepository != null
                ? paymentRepository.sumFilteredPayments(effectiveBranchId, PaymentType.ADVANCE_DEPOSIT, null, startDt, endDt, keyword)
                : null);

        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("keyword", keyword);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("filteredTotalAmount", filteredTotalAmount);
        model.addAttribute("filterPeriodLabel", formatFilterPeriodLabel(startDate, endDate));
        model.addAttribute("filterBranchLabel", resolveFilterBranchLabel(effectiveBranchId, allowedBranches));

        if (profileId != null) {
            return "redirect:/payments/deposits/create?profileId=" + profileId;
        }
        return "finance/payments/deposits";
    }

    /**
     * [Chức năng]: Hiển thị biểu mẫu nạp tiền tạm ứng mới (`GET /payments/deposits/create`, view `finance/payments/deposit-create`).
     * [Mục đích]: Nạp danh sách chi nhánh được phép và nếu có `profileId` thì hiển thị sẵn thông tin bệnh nhân cùng lịch sử các lần nạp cọc trước đó.
     */
    @GetMapping("/deposits/create")
    public String createDepositForm(
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
        return "finance/payments/deposit-create";
    }

    /**
     * [Chức năng]: Xử lý submit form nạp tiền tạm ứng vào ví cọc bệnh nhân (`POST /payments/deposits`).
     * [Mục đích]: Kiểm tra an toàn đường dẫn `returnUrl` (chống open redirect), gọi `paymentService.processAdvanceDeposit` để cộng số dư ví tạm ứng và ghi phiếu `TU...`, sau đó chuyển hướng kèm thông báo kết quả.
     */
    @PostMapping("/deposits")
    public String saveAdvanceDeposit(
            @ModelAttribute PaymentRequestDTO request,
            @RequestParam(name = "returnUrl", required = false) String returnUrl,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        String safeReturnUrl = null;
        if (returnUrl != null && !returnUrl.isBlank()) {
            String trimmed = returnUrl.trim();
            if (trimmed.startsWith("/") && !trimmed.startsWith("//") && !trimmed.contains("\\")) {
                safeReturnUrl = "redirect:" + trimmed;
            }
        }
        String targetRedirect = safeReturnUrl != null
                ? safeReturnUrl
                : (request.getProfileId() != null
                        ? "redirect:/payments/dashboard?patientId=" + request.getProfileId()
                        : "redirect:/payments/dashboard");

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

        return targetRedirect;
    }

    /**
     * [Chức năng]: Hiển thị danh sách phiếu hoàn tiền (`GET /payments/refunds`, view `finance/payments/refunds`).
     * [Mục đích]: Phân trang và lọc lịch sử hoàn tiền theo chi nhánh được phân quyền, phương thức hoàn (`RefundMethod`), từ khóa và khoảng ngày, đồng thời tính tổng số tiền đã hoàn theo bộ lọc hiện hành.
     */
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

        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 10 : Math.min(size, 100);

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "refundedAt"));
        Page<Refund> refundPage = refundService.getRefunds(effectiveBranchId, refundMethod, keyword, startDt, endDt, pageable);

        long totalItems = refundPage.getTotalElements();
        long fromItem = refundPage.getContent().isEmpty() ? 0 : (long) safePage * safeSize + 1;
        long toItem = refundPage.getContent().isEmpty() ? 0 : Math.min((long) (safePage + 1) * safeSize, totalItems);

        model.addAttribute("refunds", refundPage.getContent());
        model.addAttribute("currentPage", safePage);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("totalPages", refundPage.getTotalPages());
        model.addAttribute("totalElements", totalItems);
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        BigDecimal filteredTotalAmount = safeMoney(refundRepository != null
                ? refundRepository.sumFilteredRefunds(effectiveBranchId, refundMethod, startDt, endDt, keyword)
                : null);

        model.addAttribute("selectedRefundMethod", refundMethod);
        model.addAttribute("keyword", keyword);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("filteredTotalAmount", filteredTotalAmount);
        model.addAttribute("filterPeriodLabel", formatFilterPeriodLabel(startDate, endDate));
        model.addAttribute("filterBranchLabel", resolveFilterBranchLabel(effectiveBranchId, allowedBranches));

        return "finance/payments/refunds";
    }

    /**
     * [Chức năng]: Xử lý submit form lập phiếu hoàn tiền cho bệnh nhân (`POST /payments/refunds`).
     * [Mục đích]: Gọi `refundService.processRefund` với quyền nhân sự hiện tại và điều hướng về dashboard bệnh nhân (nếu `returnToDashboard=true`), trang chi tiết hóa đơn hoặc danh sách hoàn tiền kèm thông báo flash.
     */
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

    /**
     * [Chức năng]: Hiển thị màn hình chi tiết công nợ và các khoản chờ lập hóa đơn/thu tiền của một bệnh nhân (`GET /payments/debts/{profileId}`, view `finance/payments/debt-detail`).
     * [Mục đích]: Giới hạn dữ liệu theo phạm vi chi nhánh được phép của người dùng, tải chi tiết từ `cashierOverviewService.findDebtDetail` (ném 404 nếu không còn khoản chờ xử lý) cùng danh sách kế hoạch điều trị của bệnh nhân.
     */
    @GetMapping("/debts/{profileId}")
    public String viewDebtDetail(@PathVariable Long profileId, @RequestParam(required = false) Long branchId,
                                 Model model, HttpSession session) {
        Long userId = getCurrentUserId(session);
        List<Long> scope = null;
        if (branchId != null) {
            scope = List.of(resolveBranchId(branchId, userId));
        } else if (!userRepository.hasActiveAdminRole(userId)) {
            scope = resolveAllowedBranches(userId).stream().map(Branch::getBranchId).toList();
        }
        var detail = cashierOverviewService.findDebtDetail(profileId, scope)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Không có khoản cần xử lý trong phạm vi chi nhánh."));
        populateUserAndSidebar(model, session, "debts");
        model.addAttribute("detail", detail);
        model.addAttribute("branchId", branchId);
        model.addAttribute("treatmentPlans", treatmentPlanRepository != null ? treatmentPlanRepository.findByProfileIdWithDetails(profileId) : java.util.Collections.emptyList());
        return "finance/payments/debt-detail";
    }

    /**
     * [Chức năng]: Hiển thị danh sách công nợ tổng hợp theo từng bệnh nhân (`GET /payments/debts`, view `finance/payments/debts`).
     * [Mục đích]: Gọi `debtService.getPatientDebts` để phân trang các bệnh nhân còn nợ trong phạm vi chi nhánh được phép, hỗ trợ lọc theo chi nhánh, trạng thái thanh toán (`UNPAID`/`PARTIAL`), từ khóa và khoảng ngày phát sinh hóa đơn.
     */
    @GetMapping("/debts")
    public String listDebts(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) InvoicePaymentStatus paymentStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
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

        LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDt = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 10 : Math.min(size, 100);

        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<PatientDebtSummaryDTO> debtPage = debtService.getPatientDebts(effectiveBranchId, allowedBranchIds, paymentStatus, keyword, startDt, endDt, pageable);

        long totalItems = debtPage.getTotalElements();
        long fromItem = debtPage.getContent().isEmpty() ? 0 : (long) safePage * safeSize + 1;
        long toItem = debtPage.getContent().isEmpty() ? 0 : Math.min((long) (safePage + 1) * safeSize, totalItems);

        model.addAttribute("patientDebts", debtPage.getContent());
        model.addAttribute("currentPage", safePage);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("totalPages", debtPage.getTotalPages());
        model.addAttribute("totalElements", totalItems);
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("fromItem", fromItem);
        model.addAttribute("toItem", toItem);
        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("selectedPaymentStatus", paymentStatus);
        model.addAttribute("keyword", keyword);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);

        return "finance/payments/debts";
    }
}
