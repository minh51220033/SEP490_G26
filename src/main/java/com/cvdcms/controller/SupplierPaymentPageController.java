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
 * MVC Controller phục vụ phân hệ Tài chính — Công nợ &amp; Thanh toán Nhà cung cấp ({@code /supplier-payments}).
 *
 * <p><b>Chức năng chính:</b>
 * <ul>
 *   <li>Hiển thị tổng quan công nợ và lịch sử thanh toán theo chi nhánh ({@code GET /supplier-payments}).</li>
 *   <li>Hiển thị trang lập phiếu thanh toán công nợ ({@code GET /supplier-payments/create}).</li>
 *   <li>Xem chi tiết công nợ, lịch sử thanh toán và danh sách phiếu nhập của một NCC ({@code GET /supplier-payments/{supplierId}}).</li>
 *   <li>Cung cấp API nội bộ lấy danh sách phiếu nhập còn nợ ({@code GET /supplier-payments/api/unpaid-receipts})
 *   và tạo phiếu thanh toán ({@code POST /supplier-payments/pay}).</li>
 * </ul>
 * </p>
 *
 * <p><b>Phân quyền chi nhánh:</b> Admin xem được tất cả chi nhánh đang hoạt động; nhân viên tài chính/kho
 * chỉ xem được các chi nhánh được phân công trong {@code staff_branch_assignments} (hoặc chi nhánh mặc định của user).</p>
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
     * Đổ thông tin người dùng đăng nhập và đánh dấu mục menu sidebar đang chọn ({@code supplier-payments}) vào {@link Model}.
     *
     * @param model   model Thymeleaf
     * @param session phiên HTTP hiện tại
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
     * Lấy ID tài khoản đang đăng nhập từ {@link HttpSession}; ném {@link AccessDeniedException} nếu chưa đăng nhập.
     *
     * @param session phiên HTTP hiện tại
     * @return {@code userId} của người dùng đang đăng nhập
     */
    private Long getCurrentUserId(HttpSession session) {
        if (session != null && session.getAttribute("loggedInUser") instanceof User u && u.getUserId() != null) {
            return u.getUserId();
        }
        throw new AccessDeniedException("Chưa đăng nhập. Vui lòng đăng nhập để tiếp tục.");
    }

    /**
     * Xác định danh sách chi nhánh mà người dùng được phép truy cập.
     *
     * <p><b>Xử lý:</b>
     * <ol>
     *   <li>Nếu là Admin đang hoạt động: trả về toàn bộ chi nhánh có {@code isActive = true}.</li>
     *   <li>Ngược lại: lấy danh sách chi nhánh từ {@code staffBranchAssignmentRepository} có trạng thái {@code ACTIVE}.</li>
     *   <li>Nếu chưa có bản ghi phân công, fallback sang chi nhánh gắn trực tiếp trên {@code User#getBranch()}.</li>
     * </ol>
     * </p>
     *
     * @param userId ID người dùng hiện tại
     * @return danh sách {@link Branch} được phép truy cập
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
     * Xác định chi nhánh hiệu lực theo thứ tự ưu tiên:
     * {@code requestedBranchId} (phải nằm trong {@code allowedBranches}, nếu không ném {@link AccessDeniedException})
     * → {@code currentBranchId} lưu trong session (nếu hợp lệ)
     * → chi nhánh đầu tiên trong {@code allowedBranches}.
     *
     * @param requestedBranchId ID chi nhánh người dùng chọn trên URL (có thể {@code null})
     * @param allowedBranches   danh sách chi nhánh được phép truy cập
     * @param session           phiên HTTP hiện tại
     * @return ID chi nhánh hiệu lực, hoặc {@code null} nếu {@code allowedBranches} rỗng
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
     * Hiển thị trang danh sách Công nợ &amp; Lịch sử Thanh toán Nhà cung cấp (phân hệ Tài chính).
     *
     * <p><b>Endpoint:</b> {@code GET /supplier-payments}</p>
     * <p><b>Xử lý:</b>
     * <ol>
     *   <li>Xác thực user, xác định chi nhánh hiệu lực.</li>
     *   <li>Gọi {@link SupplierPaymentService#getDebtSummariesForFinance} để cộng dồn các chỉ số tổng
     *   ({@code totalImportAmount}, {@code totalPaidAmount}, {@code totalRemainingDebt}, {@code suppliersWithDebtCount})
     *   và lọc theo trạng thái nợ ({@code ALL}, {@code WITH_DEBT}, {@code SETTLED}).</li>
     *   <li>Kiểm tra hợp lệ khoảng ngày {@code startDate <= endDate}; nếu hợp lệ thì gọi
     *   {@link SupplierPaymentService#searchPaymentsForFinance} phân trang lịch sử thanh toán.</li>
     *   <li>Nạp danh sách NCC còn nợ ({@code payableSuppliers}) và toàn bộ NCC tại chi nhánh ({@code allBranchSuppliers}) cho bộ lọc/form.</li>
     * </ol>
     * </p>
     * <p><b>Kết quả:</b> View {@code finance/supplier-payments/list}.</p>
     *
     * @param branchId   ID chi nhánh lọc (tùy chọn)
     * @param supplierId ID nhà cung cấp lọc lịch sử thanh toán (tùy chọn)
     * @param debtStatus trạng thái công nợ: {@code ALL}, {@code WITH_DEBT}, {@code SETTLED}
     * @param keyword    từ khóa tìm kiếm (tùy chọn)
     * @param startDate  từ ngày thanh toán (tùy chọn)
     * @param endDate    đến ngày thanh toán (tùy chọn)
     * @param page       chỉ số trang lịch sử thanh toán (mặc định 0)
     * @param size       kích thước trang (mặc định 10, tối đa 100)
     * @param model      model Thymeleaf
     * @param session    phiên HTTP
     * @return tên template {@code "finance/supplier-payments/list"}
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
     * Hiển thị trang lập phiếu thanh toán công nợ nhà cung cấp.
     *
     * <p><b>Endpoint:</b> {@code GET /supplier-payments/create}</p>
     * <p><b>Xử lý:</b> Lấy danh sách NCC còn công nợ (cộng thêm {@code supplierId} nếu được truyền sẵn),
     * tải sẵn danh sách phiếu nhập chưa thanh toán đủ ({@code unpaidReceipts}) nếu đã chọn {@code supplierId},
     * và chuẩn hóa {@code returnUrl} (chỉ cho phép đường dẫn nội bộ bắt đầu bằng {@code /} để tránh Open Redirect).</p>
     * <p><b>Kết quả:</b> View {@code finance/supplier-payments/create}.</p>
     *
     * @param branchId   ID chi nhánh (tùy chọn)
     * @param supplierId ID nhà cung cấp chọn sẵn (tùy chọn)
     * @param receiptId  ID phiếu nhập chọn sẵn để thanh toán đối ứng (tùy chọn)
     * @param returnUrl  URL quay lại sau khi thanh toán (tùy chọn, được kiểm tra an toàn)
     * @param model      model Thymeleaf
     * @param session    phiên HTTP
     * @return tên template {@code "finance/supplier-payments/create"}
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
     * Hiển thị trang chi tiết công nợ của một Nhà cung cấp tại chi nhánh, gồm 2 tab:
     * lịch sử thanh toán ({@code payments}) và danh sách phiếu nhập kho ({@code receipts}).
     *
     * <p><b>Endpoint:</b> {@code GET /supplier-payments/{supplierId}}</p>
     * <p><b>Xử lý:</b> Kiểm tra tồn tại NCC (nếu không tìm thấy thì chuyển hướng về danh sách),
     * lấy tổng hợp công nợ qua {@link SupplierPaymentService#getSupplierDebtDetailForFinance},
     * phân trang độc lập cho tab đang mở ({@code paymentPage} và {@code receiptPage}), và nạp
     * danh sách phiếu nhập còn nợ ({@code unpaidReceipts}) cho thao tác thanh toán nhanh.</p>
     * <p><b>Kết quả:</b> View {@code finance/supplier-payments/detail}.</p>
     *
     * @param supplierId ID nhà cung cấp
     * @param branchId   ID chi nhánh (tùy chọn)
     * @param tab        tab con đang mở: {@code "payments"} (mặc định) hoặc {@code "receipts"}
     * @param keyword    từ khóa tìm kiếm trong tab (tùy chọn)
     * @param startDate  từ ngày (tùy chọn)
     * @param endDate    đến ngày (tùy chọn)
     * @param page       chỉ số trang của tab đang mở (mặc định 0)
     * @param size       kích thước trang (mặc định 10, tối đa 100)
     * @param model      model Thymeleaf
     * @param session    phiên HTTP
     * @return tên template {@code "finance/supplier-payments/detail"} hoặc redirect về danh sách
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
     * API JSON trả về danh sách các phiếu nhập kho chưa thanh toán đủ ({@code remainingDebt > 0})
     * của một nhà cung cấp tại chi nhánh.
     *
     * <p><b>Endpoint:</b> {@code GET /supplier-payments/api/unpaid-receipts}</p>
     * <p><b>Được gọi từ:</b> Ajax trên trang {@code finance/supplier-payments/create} khi đổi NCC/chi nhánh.</p>
     *
     * @param branchId   ID chi nhánh
     * @param supplierId ID nhà cung cấp
     * @param session    phiên HTTP
     * @return danh sách {@link StockReceiptResponseDTO} còn công nợ
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
     * API JSON tạo mới phiếu thanh toán nhà cung cấp từ phân hệ Tài chính.
     *
     * <p><b>Endpoint:</b> {@code POST /supplier-payments/pay}</p>
     * <p><b>Đầu vào:</b> {@link SupplierPaymentCreateDTO} dạng JSON.</p>
     * <p><b>Kết quả:</b> HTTP {@code 201 Created} cùng {@link SupplierPaymentResponseDTO}.</p>
     * <p><b>Được gọi từ:</b> Form thanh toán trên các trang {@code finance/supplier-payments/*}.</p>
     *
     * @param dto     dữ liệu thanh toán
     * @param session phiên HTTP
     * @return {@link ResponseEntity} chứa phiếu thanh toán vừa tạo
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
     * Xử lý {@link ResponseStatusException} cho các endpoint trong controller này,
     * trả về JSON {@code {"message": "..."}} với mã HTTP tương ứng.
     *
     * @param ex ngoại lệ có mã trạng thái HTTP
     * @return phản hồi JSON chứa thông điệp lỗi
     */
    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason() != null ? ex.getReason() : "Không thể xử lý yêu cầu thanh toán nhà cung cấp.";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", msg));
    }

    /**
     * Xử lý {@link IllegalArgumentException} (400) và {@link AccessDeniedException} (403)
     * dưới dạng JSON {@code {"message": "..."}}.
     *
     * @param ex ngoại lệ nghiệp vụ hoặc từ chối quyền truy cập
     * @return phản hồi JSON với HTTP {@code 400} hoặc {@code 403}
     */
    @ExceptionHandler({IllegalArgumentException.class, AccessDeniedException.class})
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException ex) {
        HttpStatus status = ex instanceof AccessDeniedException ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Yêu cầu không hợp lệ."));
    }
}
