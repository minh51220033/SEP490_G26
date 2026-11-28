package com.cvdcms.service.impl;

import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.DTO.SupplierPaymentCreateDTO;
import com.cvdcms.DTO.SupplierPaymentResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.AssignmentStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.SupplierPaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * [Phân hệ]: Tài chính & Kho - Công nợ và thanh toán nhà cung cấp
 * [Vai trò]: Triển khai nghiệp vụ tổng hợp công nợ NCC theo chi nhánh (tối ưu chống N+1 query), tra cứu phiếu nhập/lịch sử thanh toán và tạo phiếu thanh toán NCC với khóa bi quan (Pessimistic Write Lock), chống gửi trùng (clientRequestId + cửa sổ 5 giây) và kiểm soát không chi vượt công nợ.
 */
@Service
public class SupplierPaymentServiceImpl implements SupplierPaymentService {

    private static final Set<String> ALLOWED_METHODS = Set.of("CASH", "BANK_TRANSFER");
    private static final Pattern REFERENCE_NO_PATTERN =
            Pattern.compile("^[A-Za-z0-9._/-]{1,100}$");
    private static final AtomicLong CODE_SEQ = new AtomicLong(System.currentTimeMillis());

    // Chống double-submit theo clientRequestId trong cửa sổ 30 giây
    private final Map<String, Long> recentRequestIds = new ConcurrentHashMap<>();

    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;
    private final StockReceiptRepository receiptRepository;
    private final SupplierPaymentRepository paymentRepository;
    private final StaffProfileRepository staffRepository;
    private final UserRepository userRepository;
    private final BranchAccessService accessService;
    @Autowired(required = false)
    private StaffBranchAssignmentRepository staffBranchAssignmentRepository;

    public SupplierPaymentServiceImpl(
            SupplierRepository supplierRepository,
            BranchRepository branchRepository,
            StockReceiptRepository receiptRepository,
            SupplierPaymentRepository paymentRepository,
            StaffProfileRepository staffRepository,
            UserRepository userRepository,
            BranchAccessService accessService) {
        this.supplierRepository = supplierRepository;
        this.branchRepository = branchRepository;
        this.receiptRepository = receiptRepository;
        this.paymentRepository = paymentRepository;
        this.staffRepository = staffRepository;
        this.userRepository = userRepository;
        this.accessService = accessService;
    }

    /**
     * Tổng hợp công nợ nhà cung cấp tại chi nhánh cho phân hệ Kho (kiểm tra quyền qua {@link BranchAccessService}).
     *
     * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierPaymentController#getDebtSummaries}.</p>
     *
     * @param branchId ID chi nhánh
     * @param keyword  từ khóa tìm kiếm NCC (tùy chọn)
     * @param userId   ID người dùng hiện tại
     * @return danh sách {@link SupplierDebtSummaryDTO}
     */
    @Override
    @Transactional(readOnly = true)
    public List<SupplierDebtSummaryDTO> getDebtSummaries(Long branchId, String keyword, Long userId) {
        if (branchId == null || branchId <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!accessService.canAccessBranch(userId, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem công nợ nhà cung cấp của chi nhánh này.");
        }
        return getDebtSummariesInternal(branchId, keyword);
    }

    /**
     * Tổng hợp công nợ nhà cung cấp tại chi nhánh cho phân hệ Tài chính
     * (kiểm tra quyền chi nhánh qua {@link #canAccessBranchForFinance}).
     *
     * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierPaymentPageController}.</p>
     *
     * @param branchId ID chi nhánh
     * @param keyword  từ khóa tìm kiếm NCC (tùy chọn)
     * @param userId   ID người dùng hiện tại
     * @return danh sách {@link SupplierDebtSummaryDTO}
     */
    @Override
    @Transactional(readOnly = true)
    public List<SupplierDebtSummaryDTO> getDebtSummariesForFinance(Long branchId, String keyword, Long userId) {
        if (branchId == null || branchId <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!canAccessBranchForFinance(userId, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem công nợ nhà cung cấp của chi nhánh này.");
        }
        return getDebtSummariesInternal(branchId, keyword);
    }

    /**
     * Logic lõi tính công nợ động {@code remainingDebt = max(0, totalImport - totalPaid)} cho danh sách nhà cung cấp tại chi nhánh.
     *
     * <p><b>Xử lý tối ưu chống N+1 query:</b>
     * <ol>
     *   <li>Truy vấn tối đa 500 nhà cung cấp khớp {@code keyword}, sắp xếp theo {@code supplierName} tăng dần.</li>
     *   <li>Thực hiện 2 truy vấn tổng hợp theo lô ({@code GROUP BY supplierId}):
     *   {@code receiptRepository.aggregateReceiptsByBranchAndSuppliers} (đếm số phiếu nhập + tổng tiền nhập)
     *   và {@code paymentRepository.aggregatePaidByBranchAndSuppliers} (tổng tiền đã trả).</li>
     *   <li>Nếu batch trả về rỗng (ví dụ trong unit test mock hoặc chưa có dữ liệu batch), fallback sang truy vấn đơn lẻ theo từng NCC.</li>
     *   <li>Tính {@code remainingDebt = totalImport.subtract(totalPaid)} (nếu âm thì kẹp về {@code 0}) và map ra {@link SupplierDebtSummaryDTO}.</li>
     * </ol>
     * </p>
     *
     * @param branchId ID chi nhánh
     * @param keyword  từ khóa tìm kiếm NCC (có thể {@code null})
     * @return danh sách tổng hợp công nợ nhà cung cấp
     */
    private List<SupplierDebtSummaryDTO> getDebtSummariesInternal(Long branchId, String keyword) {

        Page<Supplier> suppliers = supplierRepository.searchSuppliers(
                trimToNull(keyword),
                null,
                PageRequest.of(0, 500, Sort.by(Sort.Direction.ASC, "supplierName"))
        );
        List<Supplier> supplierList = (suppliers != null && suppliers.getContent() != null)
                ? suppliers.getContent()
                : supplierRepository.findAll();

        if (supplierList == null || supplierList.isEmpty()) {
            return List.of();
        }

        List<Long> supplierIds = supplierList.stream()
                .map(Supplier::getSupplierId)
                .filter(id -> id != null)
                .toList();

        Map<Long, Integer> receiptCountMap = new HashMap<>();
        Map<Long, BigDecimal> importSumMap = new HashMap<>();
        Map<Long, BigDecimal> paidSumMap = new HashMap<>();
        boolean hasBatchReceiptAgg = false;
        boolean hasBatchPaidAgg = false;

        if (!supplierIds.isEmpty()) {
            List<Object[]> receiptAggs = receiptRepository.aggregateReceiptsByBranchAndSuppliers(branchId, supplierIds);
            if (receiptAggs != null && !receiptAggs.isEmpty()) {
                hasBatchReceiptAgg = true;
                for (Object[] row : receiptAggs) {
                    if (row != null && row.length >= 3 && row[0] instanceof Number sid) {
                        int cnt = row[1] instanceof Number n ? n.intValue() : 0;
                        BigDecimal sum = row[2] instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
                        receiptCountMap.put(sid.longValue(), cnt);
                        importSumMap.put(sid.longValue(), sum);
                    }
                }
            }
            List<Object[]> paidAggs = paymentRepository.aggregatePaidByBranchAndSuppliers(branchId, supplierIds);
            if (paidAggs != null && !paidAggs.isEmpty()) {
                hasBatchPaidAgg = true;
                for (Object[] row : paidAggs) {
                    if (row != null && row.length >= 2 && row[0] instanceof Number sid) {
                        BigDecimal sum = row[1] instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
                        paidSumMap.put(sid.longValue(), sum);
                    }
                }
            }
        }

        List<SupplierDebtSummaryDTO> result = new ArrayList<>(supplierList.size());
        for (Supplier s : supplierList) {
            Long sid = s.getSupplierId();
            int receiptCount;
            BigDecimal totalImport;
            if (hasBatchReceiptAgg) {
                receiptCount = receiptCountMap.getOrDefault(sid, 0);
                totalImport = importSumMap.getOrDefault(sid, BigDecimal.ZERO);
            } else {
                long countedReceipts = receiptRepository.countReceiptsByBranchAndSupplier(branchId, sid);
                receiptCount = (int) countedReceipts;
                totalImport = receiptRepository.sumTotalAmountByBranchAndSupplier(branchId, sid);
                if (totalImport == null) totalImport = BigDecimal.ZERO;
            }

            BigDecimal totalPaid;
            if (hasBatchPaidAgg) {
                totalPaid = paidSumMap.getOrDefault(sid, BigDecimal.ZERO);
            } else {
                totalPaid = paymentRepository.sumPaidAmountByBranchAndSupplier(branchId, sid);
                if (totalPaid == null) totalPaid = BigDecimal.ZERO;
            }

            BigDecimal remainingDebt = totalImport.subtract(totalPaid);
            if (remainingDebt.compareTo(BigDecimal.ZERO) < 0) {
                remainingDebt = BigDecimal.ZERO;
            }

            result.add(new SupplierDebtSummaryDTO(
                    s.getSupplierId(),
                    s.getSupplierCode(),
                    s.getSupplierName(),
                    s.getTaxCode(),
                    s.getPhoneNumber(),
                    s.getIsActive(),
                    receiptCount,
                    totalImport,
                    totalPaid,
                    remainingDebt
            ));
        }
        return result;
    }

    /**
     * Tra cứu lịch sử thanh toán nhà cung cấp có phân trang cho phân hệ Kho.
     *
     * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierPaymentController#listPayments}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Page<SupplierPaymentResponseDTO> searchPayments(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId) {

        if (branchId == null || branchId <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!accessService.canAccessBranch(userId, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập chi nhánh này.");
        }
        return searchPaymentsInternal(branchId, supplierId, keyword, fromDate, toDate, pageable);
    }

    /**
     * Tra cứu lịch sử thanh toán nhà cung cấp có phân trang cho phân hệ Tài chính.
     *
     * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierPaymentPageController}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Page<SupplierPaymentResponseDTO> searchPaymentsForFinance(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId) {

        if (branchId == null || branchId <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!canAccessBranchForFinance(userId, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập chi nhánh này.");
        }
        return searchPaymentsInternal(branchId, supplierId, keyword, fromDate, toDate, pageable);
    }

    /**
     * Logic lõi tra cứu lịch sử thanh toán: kiểm tra {@code fromDate <= toDate}, truy vấn
     * {@link SupplierPaymentRepository#searchPayments} và chuyển đổi sang {@link SupplierPaymentResponseDTO}.
     */
    private Page<SupplierPaymentResponseDTO> searchPaymentsInternal(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable) {
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            bad("Từ ngày không được sau đến ngày.");
        }

        Page<SupplierPayment> page = paymentRepository.searchPayments(
                branchId, supplierId, trimToNull(keyword), fromDate, toDate, pageable);

        return page.map(this::toDTO);
    }

    /**
     * Tạo mới phiếu thanh toán công nợ nhà cung cấp cho phân hệ Kho (kiểm tra quyền chi nhánh qua {@link BranchAccessService}).
     *
     * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierPaymentController#createPayment}.</p>
     */
    @Override
    @Transactional
    public SupplierPaymentResponseDTO createPayment(SupplierPaymentCreateDTO dto, Long userId) {
        if (dto == null || dto.getBranchId() == null || dto.getBranchId() <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!accessService.canAccessBranch(userId, dto.getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền tạo thanh toán tại chi nhánh này.");
        }
        return createPaymentInternal(dto, userId);
    }

    /**
     * Tạo mới phiếu thanh toán công nợ nhà cung cấp cho phân hệ Tài chính
     * (kiểm tra quyền chi nhánh qua {@link #canAccessBranchForFinance}).
     *
     * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierPaymentPageController#createSupplierPayment}.</p>
     */
    @Override
    @Transactional
    public SupplierPaymentResponseDTO createPaymentForFinance(SupplierPaymentCreateDTO dto, Long userId) {
        if (dto == null || dto.getBranchId() == null || dto.getBranchId() <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!canAccessBranchForFinance(userId, dto.getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền tạo thanh toán tại chi nhánh này.");
        }
        return createPaymentInternal(dto, userId);
    }

    /**
     * Lấy danh sách phiếu nhập kho ({@link StockReceipt}) còn công nợ ({@code totalAmount - paidAmount > 0})
     * của một nhà cung cấp tại chi nhánh.
     *
     * <p><b>Xử lý:</b> Truy vấn toàn bộ phiếu nhập của NCC tại chi nhánh, tổng hợp theo lô số tiền đã trả
     * của từng phiếu qua {@link SupplierPaymentRepository#sumPaidAmountByReceipts}, tính {@code remaining = total - paid}
     * và chỉ giữ lại các phiếu có {@code remaining > 0}.</p>
     * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierPaymentPageController}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<StockReceiptResponseDTO> getUnpaidReceiptsForFinance(Long branchId, Long supplierId, Long userId) {
        if (branchId == null || branchId <= 0 || supplierId == null || supplierId <= 0) {
            return List.of();
        }
        if (!canAccessBranchForFinance(userId, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem phiếu nhập của chi nhánh này.");
        }
        List<StockReceipt> receipts = receiptRepository.findByBranchAndSupplierOrderByReceiptDateDesc(branchId, supplierId);
        if (receipts == null || receipts.isEmpty()) {
            return List.of();
        }
        List<Long> receiptIds = receipts.stream()
                .map(StockReceipt::getReceiptId)
                .filter(id -> id != null)
                .toList();
        Map<Long, BigDecimal> paidMap = new HashMap<>();
        if (!receiptIds.isEmpty()) {
            List<Object[]> rows = paymentRepository.sumPaidAmountByReceipts(receiptIds);
            if (rows != null) {
                for (Object[] row : rows) {
                    if (row != null && row.length >= 2 && row[0] instanceof Number rid) {
                        BigDecimal sum = row[1] instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
                        paidMap.put(rid.longValue(), sum);
                    }
                }
            }
        }
        List<StockReceiptResponseDTO> result = new ArrayList<>();
        for (StockReceipt r : receipts) {
            BigDecimal total = r.getTotalAmount() != null ? r.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal paid = paidMap.getOrDefault(r.getReceiptId(), BigDecimal.ZERO);
            BigDecimal remaining = total.subtract(paid);
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                StockReceiptResponseDTO dto = new StockReceiptResponseDTO(
                        r.getReceiptId(),
                        r.getReceiptCode(),
                        branchId,
                        supplierId,
                        r.getReceiptDate(),
                        total
                );
                dto.setSupplierInvoiceNo(r.getSupplierInvoiceNo());
                dto.setPaidAmount(paid);
                dto.setRemainingDebt(remaining);
                result.add(dto);
            }
        }
        return result;
    }

    /**
     * Lấy chi tiết tổng hợp công nợ của một nhà cung cấp cụ thể tại chi nhánh (tính động {@code totalImport - totalPaid}).
     *
     * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierPaymentPageController#supplierPaymentDetail}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public SupplierDebtSummaryDTO getSupplierDebtDetailForFinance(Long branchId, Long supplierId, Long userId) {
        if (branchId == null || branchId <= 0 || supplierId == null || supplierId <= 0) {
            bad("Thông tin chi nhánh hoặc nhà cung cấp không hợp lệ.");
        }
        if (!canAccessBranchForFinance(userId, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem công nợ nhà cung cấp của chi nhánh này.");
        }
        Supplier s = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà cung cấp."));

        long receiptCount = receiptRepository.countReceiptsByBranchAndSupplier(branchId, supplierId);
        BigDecimal totalImport = receiptRepository.sumTotalAmountByBranchAndSupplier(branchId, supplierId);
        if (totalImport == null) totalImport = BigDecimal.ZERO;
        BigDecimal totalPaid = paymentRepository.sumPaidAmountByBranchAndSupplier(branchId, supplierId);
        if (totalPaid == null) totalPaid = BigDecimal.ZERO;
        BigDecimal remaining = totalImport.subtract(totalPaid);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            remaining = BigDecimal.ZERO;
        }

        return new SupplierDebtSummaryDTO(
                s.getSupplierId(),
                s.getSupplierCode(),
                s.getSupplierName(),
                s.getTaxCode(),
                s.getPhoneNumber(),
                s.getIsActive(),
                (int) receiptCount,
                totalImport,
                totalPaid,
                remaining
        );
    }

    /**
     * Tra cứu phân trang danh sách phiếu nhập kho ({@link StockReceipt}) của nhà cung cấp tại chi nhánh,
     * đồng thời tính động {@code paidAmount} và {@code remainingDebt} cho từng phiếu nhập theo lô (batch).
     *
     * <p><b>Được gọi từ:</b> {@link com.cvdcms.controller.SupplierPaymentPageController#supplierPaymentDetail} (tab {@code receipts}).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Page<StockReceiptResponseDTO> getReceiptsForFinance(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId) {
        if (branchId == null || branchId <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!canAccessBranchForFinance(userId, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem phiếu nhập của chi nhánh này.");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            bad("Từ ngày không được sau đến ngày.");
        }
        Page<StockReceipt> page = receiptRepository.searchReceipts(
                branchId, supplierId, trimToNull(keyword), fromDate, toDate, pageable);
        List<Long> receiptIds = page.getContent().stream()
                .map(StockReceipt::getReceiptId)
                .filter(id -> id != null)
                .toList();
        Map<Long, BigDecimal> paidMap = new HashMap<>();
        if (!receiptIds.isEmpty()) {
            List<Object[]> rows = paymentRepository.sumPaidAmountByReceipts(receiptIds);
            if (rows != null) {
                for (Object[] row : rows) {
                    if (row != null && row.length >= 2 && row[0] instanceof Number rid) {
                        BigDecimal sum = row[1] instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
                        paidMap.put(rid.longValue(), sum);
                    }
                }
            }
        }
        return page.map(r -> {
            BigDecimal total = r.getTotalAmount() != null ? r.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal paid = paidMap.getOrDefault(r.getReceiptId(), BigDecimal.ZERO);
            BigDecimal remaining = total.subtract(paid);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) {
                remaining = BigDecimal.ZERO;
            }
            StockReceiptResponseDTO dto = new StockReceiptResponseDTO(
                    r.getReceiptId(),
                    r.getReceiptCode(),
                    r.getBranch() != null ? r.getBranch().getBranchId() : branchId,
                    r.getSupplier() != null ? r.getSupplier().getSupplierId() : supplierId,
                    r.getReceiptDate(),
                    total
            );
            dto.setBranchName(r.getBranch() != null ? r.getBranch().getBranchName() : null);
            dto.setSupplierCode(r.getSupplier() != null ? r.getSupplier().getSupplierCode() : null);
            dto.setSupplierName(r.getSupplier() != null ? r.getSupplier().getSupplierName() : null);
            dto.setSupplierInvoiceNo(r.getSupplierInvoiceNo());
            dto.setPaidAmount(paid);
            dto.setRemainingDebt(remaining);
            dto.setReceiverName(r.getReceiver() != null ? r.getReceiver().getFullName() : null);
            dto.setNotes(r.getNotes());
            return dto;
        });
    }

    /**
     * Kiểm tra quyền truy cập chi nhánh mở rộng cho phân hệ Tài chính.
     *
     * <p><b>Xử lý:</b> Cho phép nếu {@link BranchAccessService#canAccessBranch} trả về {@code true};
     * nếu không, kiểm tra người dùng có ít nhất một quyền tài chính/NCC ({@code FINANCE_EXPENSES},
     * {@code FINANCE_DASHBOARD}, {@code SUPPLIER_MANAGE}) VÀ được phân công chi nhánh {@code branchId}
     * trong {@code staff_branch_assignments} (hoặc thuộc chi nhánh chính của user khi chưa có bản ghi phân công).</p>
     */
    private boolean canAccessBranchForFinance(Long userId, Long branchId) {
        if (accessService.canAccessBranch(userId, branchId)) {
            return true;
        }
        if (userId == null || userId <= 0 || branchId == null || branchId <= 0) {
            return false;
        }
        boolean hasFinancePerm = userRepository.hasActivePermission(userId, "FINANCE_EXPENSES")
                || userRepository.hasActivePermission(userId, "FINANCE_DASHBOARD")
                || userRepository.hasActivePermission(userId, "SUPPLIER_MANAGE");
        if (!hasFinancePerm) {
            return false;
        }
        if (staffBranchAssignmentRepository != null
                && staffBranchAssignmentRepository.hasActiveUserAssignment(userId, branchId, AssignmentStatus.ACTIVE)) {
            return true;
        }
        if (staffBranchAssignmentRepository != null
                && !staffBranchAssignmentRepository.findActiveBranchIdsByUserId(userId, AssignmentStatus.ACTIVE).isEmpty()) {
            return false;
        }
        return userRepository.findById(userId)
                .map(u -> u.getBranch() != null && branchId.equals(u.getBranch().getBranchId()))
                .orElse(false);
    }

    /**
     * Logic lõi ghi nhận một phiếu thanh toán công nợ Nhà cung cấp ({@link SupplierPayment}).
     *
     * <p><b>Quy trình xử lý từng bước:</b>
     * <ol>
     *   <li><b>Validate đầu vào:</b> Kiểm tra {@code supplierId > 0}, {@code amount > 0},
     *   {@code paymentDate <= hôm nay}, {@code paymentMethod} thuộc {@code {CASH, BANK_TRANSFER}},
     *   định dạng {@code referenceNo} (regex {@code ^[A-Za-z0-9._/-]{1,100}$}) và độ dài {@code notes <= 255}.</li>
     *   <li><b>Chống double-submit tầng bộ nhớ (30 giây):</b> Dọn các khóa hết hạn trong {@code recentRequestIds}
     *   và kiểm tra {@code userId + "::" + clientRequestId}; nếu trùng thì từ chối ngay.</li>
     *   <li><b>Khóa bi quan Nhà cung cấp ({@code PESSIMISTIC_WRITE}):</b> Gọi {@code supplierRepository.findByIdForUpdate}
     *   để tuần tự hóa các giao dịch thanh toán đồng thời cùng NCC, tránh race condition thanh toán vượt nợ.</li>
     *   <li><b>Chống double-submit tầng CSDL (5 giây):</b> Gọi {@code paymentRepository.existsRecentDuplicatePayment}
     *   với cùng {@code (branchId, supplierId, receiptId, amount)} trong 5 giây gần nhất.</li>
     *   <li><b>Liên kết &amp; kiểm tra công nợ phiếu nhập đối ứng ({@code StockReceipt ↔ SupplierPayment}):</b>
     *   Nếu có {@code receiptId}, khóa bi quan phiếu nhập qua {@code receiptRepository.findByIdForUpdate},
     *   xác minh phiếu thuộc đúng chi nhánh và NCC, tính công nợ còn lại của phiếu
     *   ({@code receipt.totalAmount - sumPaidByReceipt}) và đảm bảo {@code amount <= receiptRemaining}.</li>
     *   <li><b>Kiểm tra tổng công nợ động toàn chi nhánh:</b> Tính {@code totalRemaining = totalImport - totalPaid};
     *   đảm bảo NCC còn công nợ và {@code amount <= totalRemaining}.</li>
     *   <li><b>Lưu phiếu thanh toán:</b> Xác định nhân viên chi qua {@link #resolveOrCreateStaffProfile},
     *   sinh mã phiếu duy nhất {@code PC<seq>}, lưu ảnh chữ ký (nếu có {@code signatureData}) và persist {@link SupplierPayment}.</li>
     * </ol>
     * </p>
     *
     * @param dto    dữ liệu thanh toán từ client
     * @param userId ID người dùng thực hiện thanh toán
     * @return {@link SupplierPaymentResponseDTO} của phiếu vừa lưu
     */
    private SupplierPaymentResponseDTO createPaymentInternal(SupplierPaymentCreateDTO dto, Long userId) {
        if (dto.getSupplierId() == null || dto.getSupplierId() <= 0) {
            bad("Vui lòng chọn nhà cung cấp.");
        }
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            bad("Số tiền thanh toán phải lớn hơn 0.");
        }

        LocalDate today = LocalDate.now();
        LocalDate paymentDate = dto.getPaymentDate() == null ? today : dto.getPaymentDate();
        if (paymentDate.isAfter(today)) {
            bad("Ngày thanh toán không được ở tương lai.");
        }

        String method = dto.getPaymentMethod() == null || dto.getPaymentMethod().isBlank()
                ? "BANK_TRANSFER"
                : dto.getPaymentMethod().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            bad("Hình thức thanh toán chỉ chấp nhận CASH (Tiền mặt) hoặc BANK_TRANSFER (Chuyển khoản).");
        }

        String refNo = trimToNull(dto.getReferenceNo());
        if (refNo != null && !REFERENCE_NO_PATTERN.matcher(refNo).matches()) {
            bad("Số chứng từ / Mã giao dịch tối đa 100 ký tự và chỉ chứa chữ, số, ., _, /, -.");
        }

        String notes = trimToNull(dto.getNotes());
        if (notes != null && notes.length() > 255) {
            bad("Ghi chú thanh toán tối đa 255 ký tự.");
        }

        // Chống gửi trùng qua clientRequestId
        String clientReqId = trimToNull(dto.getClientRequestId());
        long nowMs = System.currentTimeMillis();
        recentRequestIds.entrySet().removeIf(e -> nowMs - e.getValue() > 30_000L);
        if (clientReqId != null) {
            Long prevTime = recentRequestIds.putIfAbsent(userId + "::" + clientReqId, nowMs);
            if (prevTime != null && (nowMs - prevTime) <= 30_000L) {
                bad("Yêu cầu thanh toán đang được xử lý hoặc vừa được gửi. Vui lòng không bấm thanh toán nhiều lần.");
            }
        }

        Branch branch = branchRepository.findById(dto.getBranchId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));

        // Khóa bi quan nhà cung cấp để chống thanh toán vượt công nợ khi gửi đồng thời
        Supplier supplier = supplierRepository.findByIdForUpdate(dto.getSupplierId())
                .orElseGet(() -> supplierRepository.findById(dto.getSupplierId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà cung cấp.")));

        BigDecimal amount = dto.getAmount().setScale(2, RoundingMode.HALF_UP);

        Long targetReceiptId = (dto.getReceiptId() != null && dto.getReceiptId() > 0) ? dto.getReceiptId() : null;
        if (paymentRepository.existsRecentDuplicatePayment(
                branch.getBranchId(),
                supplier.getSupplierId(),
                targetReceiptId,
                amount,
                LocalDateTime.now().minusSeconds(5))) {
            bad("Giao dịch thanh toán cùng số tiền vừa được ghi nhận cách đây vài giây. Vui lòng kiểm tra lại lịch sử thanh toán.");
        }

        StockReceipt linkedReceipt = null;
        if (targetReceiptId != null) {
            linkedReceipt = receiptRepository.findByIdForUpdate(targetReceiptId)
                    .orElseGet(() -> receiptRepository.findById(targetReceiptId)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu nhập kho.")));

            if (!linkedReceipt.getBranch().getBranchId().equals(branch.getBranchId())
                    || !linkedReceipt.getSupplier().getSupplierId().equals(supplier.getSupplierId())) {
                bad("Phiếu nhập được chọn không thuộc nhà cung cấp hoặc chi nhánh này.");
            }
            BigDecimal paidForReceipt = paymentRepository.sumPaidAmountByReceipt(linkedReceipt.getReceiptId());
            if (paidForReceipt == null) paidForReceipt = BigDecimal.ZERO;
            BigDecimal receiptRemaining = linkedReceipt.getTotalAmount().subtract(paidForReceipt);
            if (receiptRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                bad("Phiếu nhập " + linkedReceipt.getReceiptCode() + " đã được thanh toán đủ, không còn công nợ.");
            }
            if (amount.compareTo(receiptRemaining) > 0) {
                bad("Số tiền thanh toán (" + amount + ") vượt quá công nợ còn lại của phiếu nhập "
                        + linkedReceipt.getReceiptCode() + " (Còn nợ: " + receiptRemaining + ").");
            }
        }

        // Luôn kiểm tra tổng công nợ còn lại của nhà cung cấp tại chi nhánh (kể cả khi chọn hoặc không chọn phiếu nhập)
        BigDecimal totalImport = receiptRepository.sumTotalAmountByBranchAndSupplier(
                branch.getBranchId(), supplier.getSupplierId());
        if (totalImport == null) totalImport = BigDecimal.ZERO;
        BigDecimal totalPaid = paymentRepository.sumPaidAmountByBranchAndSupplier(
                branch.getBranchId(), supplier.getSupplierId());
        if (totalPaid == null) totalPaid = BigDecimal.ZERO;
        BigDecimal totalRemaining = totalImport.subtract(totalPaid);

        if (linkedReceipt == null || totalImport.compareTo(BigDecimal.ZERO) > 0) {
            if (totalRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                bad("Nhà cung cấp " + supplier.getSupplierName() + " không còn công nợ tại chi nhánh này.");
            }
            if (amount.compareTo(totalRemaining) > 0) {
                bad("Số tiền thanh toán (" + amount + ") vượt quá tổng công nợ hiện tại của nhà cung cấp "
                        + supplier.getSupplierName() + " tại chi nhánh (Còn nợ: " + totalRemaining + ").");
            }
        }

        StaffProfile paidBy = resolveOrCreateStaffProfile(userId);

        SupplierPayment payment = new SupplierPayment();
        payment.setPaymentCode(generateUniquePaymentCode());
        payment.setBranch(branch);
        payment.setSupplier(supplier);
        payment.setReceipt(linkedReceipt);
        payment.setPaymentDate(paymentDate);
        payment.setAmount(amount);
        payment.setPaymentMethod(method);
        payment.setReferenceNo(refNo);
        payment.setPaidBy(paidBy);
        payment.setNotes(notes);
        String rawSig = trimToNull(dto.getSignatureData());
        if (rawSig != null) {
            payment.setSignatureUrl(com.cvdcms.util.InventoryFileStorageUtil.saveSignatureDataUrl(
                    rawSig, "supplier-payment"));
        }

        SupplierPayment saved = paymentRepository.save(payment);
        return toDTO(saved != null ? saved : payment);
    }

    /**
     * Sinh mã phiếu thanh toán duy nhất theo định dạng {@code PC<số tăng dần theo timestamp>},
     * kiểm tra vòng lặp {@link SupplierPaymentRepository#existsByPaymentCode} để đảm bảo không trùng trong DB.
     */
    private String generateUniquePaymentCode() {
        String code;
        do {
            long next = CODE_SEQ.updateAndGet(prev -> Math.max(System.currentTimeMillis(), prev + 1));
            code = "PC" + next;
        } while (paymentRepository.existsByPaymentCode(code));
        return code;
    }

    /**
     * Tìm hồ sơ nhân viên ({@link StaffProfile}) gắn với {@code userId} để ghi nhận người lập phiếu chi ({@code paidBy}).
     * Nếu chưa có hồ sơ và người dùng là Admin, tự động khởi tạo một {@link StaffProfile} quản trị viên tương ứng;
     * nếu không phải Admin thì ném HTTP {@code 400 Bad Request}.
     */
    private StaffProfile resolveOrCreateStaffProfile(Long userId) {
        return staffRepository.findByUser_UserId(userId).orElseGet(() -> {
            if (!accessService.isAdmin(userId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tài khoản hiện tại chưa được liên kết với hồ sơ nhân viên.");
            }
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Không tìm thấy tài khoản."));
            StaffProfile adminStaff = new StaffProfile();
            adminStaff.setUser(user);
            adminStaff.setStaffCode("NV-ADM-" + user.getUserId());
            adminStaff.setFullName("Quản trị viên (" + user.getUsername() + ")");
            adminStaff.setPhoneNumber(user.getPhoneNumber());
            adminStaff.setEmail(user.getEmail());
            adminStaff.setDepartment("Quản trị hệ thống");
            adminStaff.setPositionTitle("Quản trị viên");
            adminStaff.setStatus(StaffStatus.ACTIVE);
            return staffRepository.save(adminStaff);
        });
    }

    /**
     * Ánh xạ entity {@link SupplierPayment} (đã fetch sẵn branch, supplier, receipt, paidBy) sang {@link SupplierPaymentResponseDTO}.
     */
    private SupplierPaymentResponseDTO toDTO(SupplierPayment sp) {
        SupplierPaymentResponseDTO dto = new SupplierPaymentResponseDTO();
        dto.setSupplierPaymentId(sp.getSupplierPaymentId());
        dto.setPaymentCode(sp.getPaymentCode());
        dto.setBranchId(sp.getBranch().getBranchId());
        dto.setBranchName(sp.getBranch().getBranchName());
        dto.setSupplierId(sp.getSupplier().getSupplierId());
        dto.setSupplierCode(sp.getSupplier().getSupplierCode());
        dto.setSupplierName(sp.getSupplier().getSupplierName());
        if (sp.getReceipt() != null) {
            dto.setReceiptId(sp.getReceipt().getReceiptId());
            dto.setReceiptCode(sp.getReceipt().getReceiptCode());
        }
        dto.setPaymentDate(sp.getPaymentDate());
        dto.setAmount(sp.getAmount());
        dto.setPaymentMethod(sp.getPaymentMethod());
        dto.setReferenceNo(sp.getReferenceNo());
        dto.setPaidByName(sp.getPaidBy() != null ? sp.getPaidBy().getFullName() : null);
        dto.setNotes(sp.getNotes());
        dto.setSignatureUrl(sp.getSignatureUrl());
        dto.setCreatedAt(sp.getCreatedAt());
        return dto;
    }

    /**
     * [Hàm hỗ trợ]: Cắt khoảng trắng đầu cuối chuỗi đầu vào và trả về null nếu chuỗi rỗng hoặc toàn khoảng trắng.
     */
    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * [Hàm hỗ trợ]: Ném ResponseStatusException với mã 400 BAD_REQUEST và thông điệp lỗi truyền vào.
     */
    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
