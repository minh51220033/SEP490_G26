package com.cvdcms.service.impl;

import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.DTO.SupplierPaymentCreateDTO;
import com.cvdcms.DTO.SupplierPaymentResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.SupplierPaymentService;
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
 * Xử lý nghiệp vụ Công nợ & Thanh toán Nhà cung cấp (Supplier Payment):
 * - Tổng hợp công nợ nhà cung cấp theo từng chi nhánh (Tổng tiền nhập - Tổng tiền đã thanh toán) tối ưu chống N+1 query.
 * - Khóa bi quan (Pessimistic Write Lock) trên Nhà cung cấp & Phiếu nhập khi thanh toán để chống race condition.
 * - Chặn gửi trùng thanh toán (clientRequestId idempotency + kiểm tra giao dịch trùng trong 5 giây).
 * - Kiểm tra số tiền thanh toán không vượt quá công nợ còn lại của phiếu nhập VÀ tổng công nợ nhà cung cấp tại chi nhánh.
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

    @Override
    @Transactional(readOnly = true)
    public List<SupplierDebtSummaryDTO> getDebtSummaries(Long branchId, String keyword, Long userId) {
        if (branchId == null || branchId <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!accessService.canAccessBranch(userId, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem công nợ nhà cung cấp của chi nhánh này.");
        }

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
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            bad("Từ ngày không được sau đến ngày.");
        }

        Page<SupplierPayment> page = paymentRepository.searchPayments(
                branchId, supplierId, trimToNull(keyword), fromDate, toDate, pageable);

        return page.map(this::toDTO);
    }

    @Override
    @Transactional
    public SupplierPaymentResponseDTO createPayment(SupplierPaymentCreateDTO dto, Long userId) {
        if (dto == null || dto.getBranchId() == null || dto.getBranchId() <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!accessService.canAccessBranch(userId, dto.getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền tạo thanh toán tại chi nhánh này.");
        }
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

        SupplierPayment saved = paymentRepository.save(payment);
        return toDTO(saved != null ? saved : payment);
    }

    private String generateUniquePaymentCode() {
        String code;
        do {
            long next = CODE_SEQ.updateAndGet(prev -> Math.max(System.currentTimeMillis(), prev + 1));
            code = "PC" + next;
        } while (paymentRepository.existsByPaymentCode(code));
        return code;
    }

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
        dto.setCreatedAt(sp.getCreatedAt());
        return dto;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
