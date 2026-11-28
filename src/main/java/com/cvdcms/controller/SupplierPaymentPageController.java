package com.cvdcms.controller;

import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.DTO.SupplierPaymentCreateDTO;
import com.cvdcms.DTO.SupplierPaymentResponseDTO;
import com.cvdcms.entity.Branch;
import com.cvdcms.entity.Supplier;
import com.cvdcms.entity.User;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.repository.BranchRepository;
import com.cvdcms.repository.StaffBranchAssignmentRepository;
import com.cvdcms.repository.SupplierRepository;
import com.cvdcms.repository.UserRepository;
import com.cvdcms.service.SupplierPaymentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * [Phân hệ]: Tài chính - Công nợ và thanh toán nhà cung cấp
 * [Vai trò]: Controller MVC phục vụ giao diện quản lý công nợ nhà cung cấp, chi tiết lịch sử nhập/trả và lập phiếu chi trả NCC (/supplier-payments).
 */
@Controller
@RequestMapping("/supplier-payments")
public class SupplierPaymentPageController {

    private final SupplierPaymentService supplierPaymentService;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;
    private final StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    public SupplierPaymentPageController(
            SupplierPaymentService supplierPaymentService,
            BranchRepository branchRepository,
            SupplierRepository supplierRepository,
            UserRepository userRepository,
            StaffBranchAssignmentRepository staffBranchAssignmentRepository
    ) {
        this.supplierPaymentService = supplierPaymentService;
        this.branchRepository = branchRepository;
        this.supplierRepository = supplierRepository;
        this.userRepository = userRepository;
        this.staffBranchAssignmentRepository = staffBranchAssignmentRepository;
    }

    /**
     * [Hàm hỗ trợ]: Nạp thông tin người dùng đang đăng nhập và đánh dấu menu supplier-payments vào Model cho giao diện.
     */
    private void populateUserAndSidebar(Model model, HttpSession session) {
        model.addAttribute("activeMenu", "supplier-payments");
        if (session != null) {
            model.addAttribute("roleNames", session.getAttribute("roleNames"));
            model.addAttribute("displayName", session.getAttribute("displayName"));
            model.addAttribute("loggedInUser", session.getAttribute("loggedInUser"));
        }
    }

    /**
     * [Hàm hỗ trợ]: Lấy ID người dùng hiện tại từ HttpSession, ném AccessDeniedException nếu chưa đăng nhập.
     */
    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

    /**
     * [Hàm hỗ trợ]: Xác định danh sách chi nhánh người dùng được phép xem công nợ (tất cả chi nhánh đang hoạt động với Admin, hoặc theo phân công ACTIVE / chi nhánh mặc định).
     */
    private List<Branch> resolveAllowedBranches(Long userId) {
        if (userId != null && userRepository.hasActiveAdminRole(userId)) {
            return branchRepository.findAll().stream()
                    .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                    .toList();
        }
        List<Long> branchIds = staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE);
        if (branchIds.isEmpty()) {
            User u = userRepository.findById(userId).orElse(null);
            if (u != null && u.getBranch() != null && u.getBranch().getBranchId() != null) {
                branchIds = List.of(u.getBranch().getBranchId());
            }
        }
        if (branchIds.isEmpty()) {
            return List.of();
        }
        return branchRepository.findAllById(branchIds);
    }

    /**
     * [Hàm hỗ trợ]: Xác định chi nhánh hiệu lực theo thứ tự ưu tiên tham số yêu cầu, session hiện tại hoặc chi nhánh đầu tiên được phân công và kiểm tra quyền truy cập.
     */
    private Long resolveEffectiveBranchId(Long requestedBranchId, List<Branch> allowedBranches, HttpSession session) {
        if (allowedBranches == null || allowedBranches.isEmpty()) {
            return null;
        }
        if (requestedBranchId != null) {
            boolean allowed = allowedBranches.stream().anyMatch(b -> b.getBranchId().equals(requestedBranchId));
            if (!allowed) {
                throw new AccessDeniedException("Bạn không có quyền truy cập dữ liệu của chi nhánh ID: " + requestedBranchId);
            }
            return requestedBranchId;
        }
        if (session != null && session.getAttribute("currentBranchId") instanceof Long sessionBranchId) {
            boolean allowed = allowedBranches.stream().anyMatch(b -> b.getBranchId().equals(sessionBranchId));
            if (allowed) {
                return sessionBranchId;
            }
        }
        return allowedBranches.get(0).getBranchId();
    }

    /**
     * [Chức năng]: Hiển thị trang tổng hợp công nợ nhà cung cấp và lịch sử thanh toán NCC theo chi nhánh (GET /supplier-payments).
     * [Mục đích]: Tải tổng hợp công nợ, tính toán KPI tổng nhập/đã trả/còn nợ, lọc theo trạng thái công nợ và tra cứu phân trang lịch sử chi trả qua SupplierPaymentService cho trang finance/supplier-payments/list.
     */
    @GetMapping
    public String listSupplierPayments(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String debtStatus,
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

        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        if (allowedBranches.isEmpty()) {
            throw new AccessDeniedException("Tài khoản nhân viên chưa được phân công chi nhánh làm việc hợp lệ.");
        }

        Long effectiveBranchId = resolveEffectiveBranchId(branchId, allowedBranches, session);
        String trimmedKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        String safeDebtStatus = (debtStatus != null && !debtStatus.isBlank()) ? debtStatus.trim() : "ALL";

        List<SupplierDebtSummaryDTO> allDebts = supplierPaymentService.getDebtSummariesForFinance(
                effectiveBranchId, trimmedKeyword, currentUserId);

        BigDecimal totalImport = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalRemainingDebt = BigDecimal.ZERO;
        int suppliersWithDebtCount = 0;

        for (SupplierDebtSummaryDTO d : allDebts) {
            BigDecimal imp = d.getTotalImportAmount() != null ? d.getTotalImportAmount() : BigDecimal.ZERO;
            BigDecimal paid = d.getTotalPaidAmount() != null ? d.getTotalPaidAmount() : BigDecimal.ZERO;
            BigDecimal rem = d.getRemainingDebt() != null ? d.getRemainingDebt() : BigDecimal.ZERO;
            totalImport = totalImport.add(imp);
            totalPaid = totalPaid.add(paid);
            totalRemainingDebt = totalRemainingDebt.add(rem);
            if (rem.compareTo(BigDecimal.ZERO) > 0) {
                suppliersWithDebtCount++;
            }
        }

        List<SupplierDebtSummaryDTO> filteredDebts = allDebts.stream()
                .filter(d -> {
                    BigDecimal rem = d.getRemainingDebt() != null ? d.getRemainingDebt() : BigDecimal.ZERO;
                    if ("WITH_DEBT".equalsIgnoreCase(safeDebtStatus)) {
                        return rem.compareTo(BigDecimal.ZERO) > 0;
                    }
                    if ("SETTLED".equalsIgnoreCase(safeDebtStatus)) {
                        return rem.compareTo(BigDecimal.ZERO) <= 0 && d.getReceiptCount() > 0;
                    }
                    return true;
                })
                .toList();

        boolean dateRangeInvalid = startDate != null && endDate != null && startDate.isAfter(endDate);
        if (dateRangeInvalid) {
            model.addAttribute("errorMessage", "Khoảng thời gian không hợp lệ: 'Từ ngày' không được lớn hơn 'Đến ngày'.");
        }

        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 10 : Math.min(size, 100);
        Pageable pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Direction.DESC, "paymentDate").and(Sort.by(Sort.Direction.DESC, "supplierPaymentId"))
        );

        Page<SupplierPaymentResponseDTO> paymentPage = dateRangeInvalid
                ? Page.empty(pageable)
                : supplierPaymentService.searchPaymentsForFinance(
                        effectiveBranchId,
                        supplierId,
                        trimmedKeyword,
                        startDate,
                        endDate,
                        pageable,
                        currentUserId
                );

        List<SupplierDebtSummaryDTO> payableSuppliers = supplierPaymentService.getDebtSummariesForFinance(
                        effectiveBranchId, null, currentUserId)
                .stream()
                .filter(s -> s.getRemainingDebt() != null && s.getRemainingDebt().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        List<SupplierDebtSummaryDTO> allBranchSuppliers = supplierPaymentService.getDebtSummariesForFinance(
                effectiveBranchId, null, currentUserId);

        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("selectedSupplierId", supplierId);
        model.addAttribute("selectedDebtStatus", safeDebtStatus);
        model.addAttribute("keyword", trimmedKeyword);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);

        model.addAttribute("supplierDebts", filteredDebts);
        model.addAttribute("payableSuppliers", payableSuppliers);
        model.addAttribute("allBranchSuppliers", allBranchSuppliers);
        model.addAttribute("paymentPage", paymentPage);
        model.addAttribute("payments", paymentPage.getContent());
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("currentPage", safePage);

        model.addAttribute("totalRemainingDebt", totalRemainingDebt);
        model.addAttribute("suppliersWithDebtCount", suppliersWithDebtCount);
        model.addAttribute("totalImportAmount", totalImport);
        model.addAttribute("totalPaidAmount", totalPaid);

        return "finance/supplier-payments/list";
    }

    /**
     * [Chức năng]: Hiển thị trang lập phiếu thanh toán công nợ nhà cung cấp (GET /supplier-payments/create).
     * [Mục đích]: Nạp danh sách nhà cung cấp còn công nợ tại chi nhánh, các phiếu nhập chưa thanh toán đủ nếu đã chọn nhà cung cấp và kiểm tra an toàn đường dẫn returnUrl cho trang finance/supplier-payments/create.
     */
    @GetMapping("/create")
    public String createSupplierPaymentPage(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long receiptId,
            @RequestParam(required = false) String returnUrl,
            Model model,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        populateUserAndSidebar(model, session);

        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        if (allowedBranches.isEmpty()) {
            throw new AccessDeniedException("Tài khoản nhân viên chưa được phân công chi nhánh làm việc hợp lệ.");
        }

        Long effectiveBranchId = resolveEffectiveBranchId(branchId, allowedBranches, session);
        List<SupplierDebtSummaryDTO> allSuppliers = supplierPaymentService.getDebtSummariesForFinance(
                effectiveBranchId, null, currentUserId);
        List<SupplierDebtSummaryDTO> payableSuppliers = allSuppliers.stream()
                .filter(s -> (s.getRemainingDebt() != null && s.getRemainingDebt().compareTo(BigDecimal.ZERO) > 0)
                        || (supplierId != null && supplierId.equals(s.getSupplierId())))
                .toList();

        List<StockReceiptResponseDTO> unpaidReceipts = supplierId != null
                ? supplierPaymentService.getUnpaidReceiptsForFinance(effectiveBranchId, supplierId, currentUserId)
                : List.of();

        String safeReturnUrl = null;
        if (returnUrl != null && !returnUrl.isBlank()) {
            String trimmed = returnUrl.trim();
            if (trimmed.startsWith("/") && !trimmed.startsWith("//") && !trimmed.contains("\\")) {
                safeReturnUrl = trimmed;
            }
        }

        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("payableSuppliers", payableSuppliers);
        model.addAttribute("selectedSupplierId", supplierId);
        model.addAttribute("selectedReceiptId", receiptId);
        model.addAttribute("unpaidReceipts", unpaidReceipts);
        model.addAttribute("todayDate", LocalDate.now());
        model.addAttribute("returnUrl", safeReturnUrl != null ? safeReturnUrl : "/supplier-payments?branchId=" + effectiveBranchId);

        return "finance/supplier-payments/create";
    }

    /**
     * [Chức năng]: Hiển thị trang chi tiết công nợ, lịch sử thanh toán và danh sách phiếu nhập kho của một nhà cung cấp (GET /supplier-payments/{supplierId}).
     * [Mục đích]: Tải tổng quan công nợ nhà cung cấp, phân trang lịch sử thanh toán và phiếu nhập theo tab đang chọn, cùng danh sách phiếu nhập chưa thanh toán đủ cho trang finance/supplier-payments/detail.
     */
    @GetMapping("/{supplierId:\\d+}")
    public String supplierPaymentDetail(
            @PathVariable Long supplierId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false, defaultValue = "payments") String tab,
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

        List<Branch> allowedBranches = resolveAllowedBranches(currentUserId);
        if (allowedBranches.isEmpty()) {
            throw new AccessDeniedException("Tài khoản nhân viên chưa được phân công chi nhánh làm việc hợp lệ.");
        }
        Long effectiveBranchId = resolveEffectiveBranchId(branchId, allowedBranches, session);
        Supplier supplier = supplierRepository.findById(supplierId).orElse(null);
        if (supplier == null) {
            return "redirect:/supplier-payments?branchId=" + effectiveBranchId;
        }

        SupplierDebtSummaryDTO debtSummary = supplierPaymentService.getSupplierDebtDetailForFinance(
                effectiveBranchId, supplierId, currentUserId);

        String activeSubTab = "receipts".equalsIgnoreCase(tab) ? "receipts" : "payments";
        String trimmedKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        boolean dateRangeInvalid = startDate != null && endDate != null && startDate.isAfter(endDate);
        if (dateRangeInvalid) {
            model.addAttribute("errorMessage", "Khoảng thời gian không hợp lệ: 'Từ ngày' không được lớn hơn 'Đến ngày'.");
        }

        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 10 : Math.min(size, 100);

        Pageable paymentPageable = PageRequest.of(
                "payments".equals(activeSubTab) ? safePage : 0,
                safeSize,
                Sort.by(Sort.Direction.DESC, "paymentDate").and(Sort.by(Sort.Direction.DESC, "supplierPaymentId"))
        );
        Pageable receiptPageable = PageRequest.of(
                "receipts".equals(activeSubTab) ? safePage : 0,
                safeSize,
                Sort.by(Sort.Direction.DESC, "receiptDate").and(Sort.by(Sort.Direction.DESC, "receiptId"))
        );

        Page<SupplierPaymentResponseDTO> paymentPage = dateRangeInvalid
                ? Page.empty(paymentPageable)
                : supplierPaymentService.searchPaymentsForFinance(
                        effectiveBranchId,
                        supplierId,
                        trimmedKeyword,
                        startDate,
                        endDate,
                        paymentPageable,
                        currentUserId
                );

        Page<StockReceiptResponseDTO> receiptPage = dateRangeInvalid
                ? Page.empty(receiptPageable)
                : supplierPaymentService.getReceiptsForFinance(
                        effectiveBranchId,
                        supplierId,
                        trimmedKeyword,
                        startDate,
                        endDate,
                        receiptPageable,
                        currentUserId
                );

        List<StockReceiptResponseDTO> unpaidReceipts = supplierPaymentService.getUnpaidReceiptsForFinance(
                effectiveBranchId, supplierId, currentUserId);

        model.addAttribute("branches", allowedBranches);
        model.addAttribute("selectedBranchId", effectiveBranchId);
        model.addAttribute("supplier", supplier);
        model.addAttribute("debtSummary", debtSummary);
        model.addAttribute("activeSubTab", activeSubTab);
        model.addAttribute("keyword", trimmedKeyword);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("currentPage", safePage);
        model.addAttribute("paymentPage", paymentPage);
        model.addAttribute("payments", paymentPage.getContent());
        model.addAttribute("receiptPage", receiptPage);
        model.addAttribute("receipts", receiptPage.getContent());
        model.addAttribute("unpaidReceipts", unpaidReceipts);

        return "finance/supplier-payments/detail";
    }

    /**
     * [Chức năng]: Trả về danh sách JSON các phiếu nhập kho còn công nợ của một nhà cung cấp tại chi nhánh (GET /supplier-payments/api/unpaid-receipts).
     * [Mục đích]: Hỗ trợ giao diện lập phiếu thanh toán tải động danh sách phiếu nhập cần thanh toán khi người dùng thay đổi nhà cung cấp hoặc chi nhánh.
     */
    @GetMapping("/api/unpaid-receipts")
    @ResponseBody
    public List<StockReceiptResponseDTO> getUnpaidReceipts(
            @RequestParam Long branchId,
            @RequestParam Long supplierId,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        return supplierPaymentService.getUnpaidReceiptsForFinance(branchId, supplierId, currentUserId);
    }

    /**
     * [Chức năng]: Xử lý yêu cầu tạo phiếu thanh toán công nợ nhà cung cấp từ phân hệ Tài chính (POST /supplier-payments/pay).
     * [Mục đích]: Xác thực phiên người dùng và gọi SupplierPaymentService.createPaymentForFinance để ghi nhận thanh toán, trả về mã 201 CREATED cùng dữ liệu phiếu chi vừa tạo.
     */
    @PostMapping("/pay")
    @ResponseBody
    public ResponseEntity<?> createSupplierPayment(
            @RequestBody SupplierPaymentCreateDTO dto,
            HttpSession session
    ) {
        Long currentUserId = getCurrentUserId(session);
        SupplierPaymentResponseDTO saved = supplierPaymentService.createPaymentForFinance(dto, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * [Chức năng]: Xử lý ngoại lệ ResponseStatusException trong Controller và trả về JSON chứa thông điệp lỗi.
     * [Mục đích]: Đảm bảo các yêu cầu AJAX/API tại /supplier-payments nhận được mã lỗi HTTP và thông báo rõ ràng.
     */
    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu thanh toán nhà cung cấp.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }

    /**
     * [Chức năng]: Xử lý ngoại lệ IllegalArgumentException và AccessDeniedException thành phản hồi JSON lỗi 400 hoặc 403.
     * [Mục đích]: Chuyển đổi lỗi dữ liệu không hợp lệ hoặc từ chối quyền truy cập sang định dạng JSON thống nhất cho giao diện.
     */
    @ExceptionHandler({IllegalArgumentException.class, AccessDeniedException.class})
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException ex) {
        HttpStatus status = ex instanceof AccessDeniedException ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Yêu cầu không hợp lệ."));
    }
}
