package com.cvdcms.service.impl;

import com.cvdcms.DTO.StockReceiptCreateDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.BatchStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.StockReceiptService;
import com.cvdcms.util.InventoryFileStorageUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Xử lý nghiệp vụ Phiếu nhập kho (Stock Receipt):
 * - Bắt buộc có ảnh/chứng từ hóa đơn hợp lệ đã tải lên (/uploads/invoices/...).
 * - Kiểm tra phân quyền chi nhánh, nhà cung cấp, định dạng số hóa đơn và số lô (regex).
 * - Tự động tạo mới hoặc cộng dồn lô vật tư (MaterialBatch) và kiểm tra khớp hạn sử dụng.
 * - Cộng dồn số lượng tồn kho tổng hợp tại chi nhánh (BranchInventory) trong cùng transaction.
 * - Tính toán độc lập tổng tiền phiếu nhập ở Backend, ghi nhận thanh toán ban đầu (SupplierPayment) và công nợ còn lại.
 */
@Service
public class StockReceiptServiceImpl implements StockReceiptService {

    /** Regex kiểm tra số lô hợp lệ (1–50 ký tự chữ, số và ., _, /, -). */
    private static final Pattern BATCH_NUMBER_PATTERN =
            Pattern.compile("^[A-Za-z0-9._/-]{1,50}$");

    /** Regex kiểm tra số hóa đơn nhà cung cấp hợp lệ (1–50 ký tự). */
    private static final Pattern INVOICE_NO_PATTERN =
            Pattern.compile("^[A-Za-z0-9._/-]{1,50}$");

    /** Regex kiểm tra số chứng từ thanh toán hợp lệ (1–100 ký tự). */
    private static final Pattern REFERENCE_NO_PATTERN =
            Pattern.compile("^[A-Za-z0-9._/-]{1,100}$");

    /** Tập phương thức thanh toán hợp lệ khi trả tiền ngay lúc nhập kho. */
    private static final Set<String> ALLOWED_PAYMENT_METHODS = Set.of("CASH", "BANK_TRANSFER");

    /** Bộ sinh số tuần tự an toàn đa luồng cho mã phiếu nhập ({@code PN...}). */
    private static final AtomicLong CODE_SEQ = new AtomicLong(System.currentTimeMillis());
    /** Bộ sinh số tuần tự an toàn đa luồng cho mã phiếu chi nhà cung cấp ({@code PC...}). */
    private static final AtomicLong PAYMENT_CODE_SEQ = new AtomicLong(System.currentTimeMillis());

    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final MaterialRepository materialRepository;
    private final StockReceiptRepository receiptRepository;
    private final MaterialBatchRepository batchRepository;
    private final BranchInventoryRepository inventoryRepository;
    private final StaffProfileRepository staffRepository;
    private final UserRepository userRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final BranchAccessService accessService;

    /**
     * Khởi tạo service với đầy đủ các repository liên quan tới nhập kho, lô vật tư, tồn kho, nhân sự và thanh toán NCC.
     */
    public StockReceiptServiceImpl(
            BranchRepository branchRepository,
            SupplierRepository supplierRepository,
            MaterialRepository materialRepository,
            StockReceiptRepository receiptRepository,
            MaterialBatchRepository batchRepository,
            BranchInventoryRepository inventoryRepository,
            StaffProfileRepository staffRepository,
            UserRepository userRepository,
            SupplierPaymentRepository supplierPaymentRepository,
            BranchAccessService accessService) {
        this.branchRepository = branchRepository;
        this.supplierRepository = supplierRepository;
        this.materialRepository = materialRepository;
        this.receiptRepository = receiptRepository;
        this.batchRepository = batchRepository;
        this.inventoryRepository = inventoryRepository;
        this.staffRepository = staffRepository;
        this.userRepository = userRepository;
        this.supplierPaymentRepository = supplierPaymentRepository;
        this.accessService = accessService;
    }

    /**
     * Tạo mới phiếu nhập kho trong một giao dịch duy nhất ({@code @Transactional}).
     * <p>Quy trình xử lý:</p>
     * <ol>
     *   <li>Kiểm tra quyền chi nhánh, nhà cung cấp, danh sách vật tư, chứng từ hóa đơn bắt buộc và ngày nhập không ở tương lai.</li>
     *   <li>Xác thực chi nhánh, nhà cung cấp đang hoạt động và lấy/tạo hồ sơ nhân viên tiếp nhận ({@link #resolveOrCreateStaffProfile(Long)}).</li>
     *   <li>Duyệt từng dòng vật tư: kiểm tra số lô (chuẩn hóa chữ hoa, không trùng lặp trong cùng phiếu), HSD &gt;= ngày nhập, SL &gt; 0, đơn giá &gt;= 0.</li>
     *   <li>Khóa bi quan ({@code findForUpdate}) để tạo mới hoặc cộng dồn {@link MaterialBatch} (đảm bảo khớp HSD nếu lô đã tồn tại) và cộng tồn {@link BranchInventory}.</li>
     *   <li>Tính tổng tiền phiếu nhập, kiểm tra số tiền thanh toán ngay ({@code paidAmount <= totalAmount}), lưu {@link StockReceipt} và sinh {@link SupplierPayment} nếu {@code paidAmount > 0}.</li>
     * </ol>
     * <p>Được gọi từ: {@link com.cvdcms.controller.StockReceiptController#create} và {@code createMultipart}.</p>
     */
    @Override
    @Transactional
    public StockReceiptResponseDTO create(StockReceiptCreateDTO dto, Long userId) {
        if (dto == null || dto.getBranchId() == null || dto.getBranchId() <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!accessService.canAccessBranch(userId, dto.getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền nhập kho tại chi nhánh này.");
        }
        if (dto.getSupplierId() == null || dto.getSupplierId() <= 0) {
            bad("Vui lòng chọn nhà cung cấp.");
        }
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            bad("Phiếu nhập phải có ít nhất một vật tư.");
        }

        // Bắt buộc phải có ảnh/chứng từ hóa đơn hợp lệ đã được tải lên
        String attachmentUrl = InventoryFileStorageUtil.validateRequiredInvoiceUrl(dto.getAttachmentUrl());

        LocalDate receiptDate = dto.getReceiptDate() == null ? LocalDate.now() : dto.getReceiptDate();
        if (receiptDate.isAfter(LocalDate.now())) {
            bad("Ngày nhập không được ở tương lai.");
        }

        String invoiceNo = trimToNull(dto.getSupplierInvoiceNo());
        if (invoiceNo != null && !INVOICE_NO_PATTERN.matcher(invoiceNo).matches()) {
            bad("Số hóa đơn nhà cung cấp tối đa 50 ký tự và chỉ chứa chữ, số, ., _, /, -.");
        }

        String notes = trimToNull(dto.getNotes());
        if (notes != null && notes.length() > 255) {
            bad("Ghi chú phiếu nhập tối đa 255 ký tự.");
        }

        Branch branch = branchRepository.findById(dto.getBranchId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh."));
        if (!Boolean.TRUE.equals(branch.getIsActive())) {
            bad("Chi nhánh đã ngừng hoạt động.");
        }

        Supplier supplier = supplierRepository.findById(dto.getSupplierId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà cung cấp."));
        if (!Boolean.TRUE.equals(supplier.getIsActive())) {
            bad("Nhà cung cấp đã ngừng hoạt động.");
        }

        StaffProfile receiver = resolveOrCreateStaffProfile(userId);

        StockReceipt receipt = new StockReceipt();
        receipt.setReceiptCode(generateUniqueReceiptCode());
        receipt.setBranch(branch);
        receipt.setSupplier(supplier);
        receipt.setSupplierInvoiceNo(invoiceNo);
        receipt.setReceiptDate(receiptDate);
        receipt.setReceiver(receiver);
        receipt.setNotes(notes);
        receipt.setAttachmentUrl(attachmentUrl);

        BigDecimal total = BigDecimal.ZERO;
        List<StockReceiptItem> items = new ArrayList<>();

        Map<String, MaterialBatch> batchCache = new HashMap<>();
        Map<Long, BranchInventory> inventoryCache = new HashMap<>();
        Set<String> seenMaterialBatchesInReceipt = new HashSet<>();

        for (StockReceiptCreateDTO.Item input : dto.getItems()) {
            if (input == null || input.getMaterialId() == null || input.getMaterialId() <= 0) {
                bad("Vật tư không hợp lệ.");
            }
            String rawBatchNo = input.getBatchNumber() == null ? "" : input.getBatchNumber().trim();
            if (rawBatchNo.isEmpty() || !BATCH_NUMBER_PATTERN.matcher(rawBatchNo).matches()) {
                bad("Số lô là bắt buộc (1-50 ký tự) và chỉ được chứa chữ, số, dấu chấm (.), gạch dưới (_), gạch chéo (/) hoặc gạch ngang (-).");
            }
            String batchNo = rawBatchNo.toUpperCase(Locale.ROOT);

            if (input.getExpiryDate() == null || input.getExpiryDate().isBefore(receiptDate)) {
                bad("Hạn sử dụng phải có và không được trước ngày nhập.");
            }
            if (input.getQuantity() == null || input.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                bad("Số lượng nhập phải lớn hơn 0.");
            }
            if (input.getUnitPrice() == null || input.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                bad("Đơn giá không được âm.");
            }

            BigDecimal quantity = input.getQuantity().setScale(3, RoundingMode.HALF_UP);
            BigDecimal unitPrice = input.getUnitPrice().setScale(2, RoundingMode.HALF_UP);

            Material material = materialRepository.findById(input.getMaterialId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Không tìm thấy vật tư ID " + input.getMaterialId()));
            if (!Boolean.TRUE.equals(material.getIsActive())) {
                bad("Vật tư " + material.getMaterialCode() + " đã ngừng hoạt động.");
            }

            String batchKey = material.getMaterialId() + "::" + batchNo;
            if (!seenMaterialBatchesInReceipt.add(batchKey)) {
                bad("Vật tư " + material.getMaterialCode() + " (" + material.getMaterialName()
                        + ") với số lô " + batchNo + " bị trùng lặp trong cùng phiếu nhập. Vui lòng gộp số lượng hoặc nhập số lô khác nhau.");
            }

            StockReceiptItem item = new StockReceiptItem();
            item.setReceipt(receipt);
            item.setMaterial(material);
            item.setBatchNumber(batchNo);
            item.setExpiryDate(input.getExpiryDate());
            item.setQuantity(quantity);
            item.setUnitPrice(unitPrice);
            items.add(item);

            MaterialBatch batch = batchCache.get(batchKey);
            if (batch == null) {
                batch = batchRepository.findForUpdate(branch.getBranchId(), material.getMaterialId(), batchNo)
                        .orElse(null);
            }

            if (batch == null) {
                batch = new MaterialBatch();
                batch.setBranch(branch);
                batch.setMaterial(material);
                batch.setBatchNumber(batchNo);
                batch.setExpiryDate(input.getExpiryDate());
                batch.setQuantityRemaining(quantity);
                batch.setImportPrice(unitPrice);
                batch.setSupplier(supplier);
            } else {
                if (!batch.getExpiryDate().equals(input.getExpiryDate())) {
                    bad("Số lô " + batchNo + " của vật tư " + material.getMaterialCode()
                            + " đã tồn tại với hạn sử dụng " + batch.getExpiryDate() + " (không khớp hạn mới).");
                }
                batch.setQuantityRemaining(batch.getQuantityRemaining().add(quantity));
                batch.setImportPrice(unitPrice);
                batch.setSupplier(supplier);
            }

            if (batch.getQuantityRemaining().compareTo(BigDecimal.ZERO) <= 0) {
                batch.setStatus(BatchStatus.OUT_OF_STOCK);
            } else if (batch.getExpiryDate().isBefore(LocalDate.now())) {
                batch.setStatus(BatchStatus.EXPIRED);
            } else {
                batch.setStatus(BatchStatus.ACTIVE);
            }

            MaterialBatch savedBatch = batchRepository.save(batch);
            if (savedBatch != null) {
                batch = savedBatch;
            }
            batchCache.put(batchKey, batch);

            BranchInventory inventory = inventoryCache.get(material.getMaterialId());
            if (inventory == null) {
                inventory = inventoryRepository.findForUpdate(branch.getBranchId(), material.getMaterialId())
                        .orElseGet(() -> {
                            BranchInventory bi = new BranchInventory();
                            bi.setBranch(branch);
                            bi.setMaterial(material);
                            bi.setCurrentStock(BigDecimal.ZERO);
                            return bi;
                        });
            }
            inventory.setCurrentStock(inventory.getCurrentStock().add(quantity));
            BranchInventory savedInv = inventoryRepository.save(inventory);
            if (savedInv != null) {
                inventory = savedInv;
            }
            inventoryCache.put(material.getMaterialId(), inventory);

            BigDecimal lineTotal = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
            total = total.add(lineTotal);
        }

        BigDecimal computedTotal = total.setScale(2, RoundingMode.HALF_UP);
        receipt.setTotalAmount(computedTotal);
        receipt.setItems(items);

        // Kiểm tra số tiền thanh toán ngay khi nhập kho (A2)
        BigDecimal paidAmount = BigDecimal.ZERO;
        if (dto.getPaidAmount() != null) {
            if (dto.getPaidAmount().compareTo(BigDecimal.ZERO) < 0) {
                bad("Số tiền đã thanh toán không được nhỏ hơn 0.");
            }
            paidAmount = dto.getPaidAmount().setScale(2, RoundingMode.HALF_UP);
            if (paidAmount.compareTo(computedTotal) > 0) {
                bad("Số tiền đã thanh toán (" + paidAmount + ") không được vượt quá tổng tiền phiếu nhập (" + computedTotal + ").");
            }
        }

        String paymentMethod = "BANK_TRANSFER";
        String paymentRefNo = null;
        if (paidAmount.compareTo(BigDecimal.ZERO) > 0) {
            if (dto.getPaymentMethod() != null && !dto.getPaymentMethod().isBlank()) {
                paymentMethod = dto.getPaymentMethod().trim().toUpperCase(Locale.ROOT);
            }
            if (!ALLOWED_PAYMENT_METHODS.contains(paymentMethod)) {
                bad("Hình thức thanh toán chỉ chấp nhận CASH (Tiền mặt) hoặc BANK_TRANSFER (Chuyển khoản).");
            }
            paymentRefNo = trimToNull(dto.getPaymentReferenceNo());
            if (paymentRefNo != null && !REFERENCE_NO_PATTERN.matcher(paymentRefNo).matches()) {
                bad("Số chứng từ thanh toán tối đa 100 ký tự và chỉ chứa chữ, số, ., _, /, -.");
            }
        }

        StockReceipt saved = receiptRepository.save(receipt);
        if (saved == null) {
            saved = receipt;
        }

        // Tự động sinh phiếu chi nhà cung cấp gắn với phiếu nhập nếu có thanh toán ban đầu > 0 (A2)
        if (paidAmount.compareTo(BigDecimal.ZERO) > 0) {
            SupplierPayment initialPayment = new SupplierPayment();
            initialPayment.setPaymentCode(generateUniquePaymentCode());
            initialPayment.setBranch(branch);
            initialPayment.setSupplier(supplier);
            initialPayment.setReceipt(saved);
            initialPayment.setPaymentDate(receiptDate);
            initialPayment.setAmount(paidAmount);
            initialPayment.setPaymentMethod(paymentMethod);
            initialPayment.setReferenceNo(paymentRefNo != null ? paymentRefNo : invoiceNo);
            initialPayment.setPaidBy(receiver);
            initialPayment.setNotes("Thanh toán khi nhập kho phiếu " + saved.getReceiptCode());
            supplierPaymentRepository.save(initialPayment);
        }

        StockReceiptResponseDTO response = toDetailDTO(saved);
        // Nếu vừa tạo thanh toán trong cùng transaction mà mock repository chưa phản ánh trong sumPaidAmountByReceipt
        if (paidAmount.compareTo(BigDecimal.ZERO) > 0
                && (response.getPaidAmount() == null || response.getPaidAmount().compareTo(BigDecimal.ZERO) == 0)) {
            response.setPaidAmount(paidAmount);
            BigDecimal rem = computedTotal.subtract(paidAmount);
            response.setRemainingDebt(rem.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : rem);
        }
        return response;
    }

    /** Sinh mã phiếu nhập kho duy nhất có tiền tố {@code PN}, kiểm tra chống trùng trong CSDL. */
    private String generateUniqueReceiptCode() {
        String code;
        do {
            long next = CODE_SEQ.updateAndGet(prev -> Math.max(System.currentTimeMillis(), prev + 1));
            code = "PN" + next;
        } while (receiptRepository.existsByReceiptCode(code));
        return code;
    }

    /** Sinh mã phiếu chi nhà cung cấp duy nhất có tiền tố {@code PC}, kiểm tra chống trùng trong CSDL. */
    private String generateUniquePaymentCode() {
        String code;
        do {
            long next = PAYMENT_CODE_SEQ.updateAndGet(prev -> Math.max(System.currentTimeMillis(), prev + 1));
            code = "PC" + next;
        } while (supplierPaymentRepository.existsByPaymentCode(code));
        return code;
    }

    /**
     * Tìm kiếm và phân trang lịch sử phiếu nhập kho theo chi nhánh, nhà cung cấp, từ khóa và khoảng ngày.
     * <p>Tối ưu N+1: gom toàn bộ {@code receiptId} của trang hiện tại và truy vấn tổng tiền đã thanh toán
     * trong 1 câu query duy nhất ({@link SupplierPaymentRepository#sumPaidAmountByReceipts}) trước khi ánh xạ DTO.</p>
     * <p>Được gọi từ: {@link com.cvdcms.controller.StockReceiptController#list}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Page<StockReceiptResponseDTO> search(
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem phiếu nhập của chi nhánh này.");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            bad("Từ ngày không được sau đến ngày.");
        }

        String normalizedKeyword = trimToNull(keyword);
        Page<StockReceipt> page = receiptRepository.searchReceipts(
                branchId, supplierId, normalizedKeyword, fromDate, toDate, pageable);

        Map<Long, BigDecimal> paidMap = new HashMap<>();
        if (page != null && !page.isEmpty()) {
            List<Long> rids = page.getContent().stream()
                    .map(StockReceipt::getReceiptId)
                    .filter(id -> id != null)
                    .toList();
            if (!rids.isEmpty()) {
                List<Object[]> rows = supplierPaymentRepository.sumPaidAmountByReceipts(rids);
                if (rows != null) {
                    for (Object[] row : rows) {
                        if (row != null && row.length >= 2 && row[0] instanceof Number idNum) {
                            BigDecimal sum = row[1] instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
                            paidMap.put(idNum.longValue(), sum);
                        }
                    }
                }
            }
        }

        return page.map(r -> toSummaryDTOWithPreloadedPaid(r, paidMap.get(r.getReceiptId())));
    }

    /**
     * Truy vấn chi tiết đầy đủ một phiếu nhập kho kèm danh sách dòng vật tư/lô và kiểm tra quyền truy cập chi nhánh.
     * <p>Được gọi từ: {@link com.cvdcms.controller.StockReceiptController#detail}, {@code exportPdf}
     * và {@link com.cvdcms.controller.InventoryPageController#printReceiptPage}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public StockReceiptResponseDTO getById(Long receiptId, Long userId) {
        if (receiptId == null || receiptId <= 0) {
            bad("Mã phiếu nhập không hợp lệ.");
        }
        StockReceipt receipt = receiptRepository.findByIdWithDetails(receiptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu nhập kho."));

        if (!accessService.canAccessBranch(userId, receipt.getBranch().getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem phiếu nhập của chi nhánh này.");
        }

        return toDetailDTO(receipt);
    }

    /**
     * Tìm hồ sơ nhân viên ({@link StaffProfile}) gắn với {@code userId}.
     * Nếu tài khoản là Quản trị viên ({@code isAdmin}) nhưng chưa có hồ sơ nhân viên, tự động khởi tạo hồ sơ mặc định để lưu vết người lập phiếu.
     */
    private StaffProfile resolveOrCreateStaffProfile(Long userId) {
        return staffRepository.findByUser_UserId(userId).orElseGet(() -> {
            if (!accessService.isAdmin(userId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tài khoản hiện tại chưa được liên kết với hồ sơ nhân viên; không thể tạo phiếu kho.");
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

    /** Ánh xạ {@link StockReceipt} sang DTO tóm tắt kèm tổng tiền đã thanh toán truy vấn trực tiếp theo {@code receiptId}. */
    private StockReceiptResponseDTO toSummaryDTO(StockReceipt r) {
        BigDecimal paid = r.getReceiptId() != null
                ? supplierPaymentRepository.sumPaidAmountByReceipt(r.getReceiptId())
                : BigDecimal.ZERO;
        return toSummaryDTOWithPreloadedPaid(r, paid);
    }

    /** Ánh xạ {@link StockReceipt} sang DTO tóm tắt với số tiền đã trả được nạp sẵn và tự tính {@code remainingDebt = max(0, total - paid)}. */
    private StockReceiptResponseDTO toSummaryDTOWithPreloadedPaid(StockReceipt r, BigDecimal preloadedPaid) {
        StockReceiptResponseDTO dto = new StockReceiptResponseDTO(
                r.getReceiptId(),
                r.getReceiptCode(),
                r.getBranch().getBranchId(),
                r.getSupplier().getSupplierId(),
                r.getReceiptDate(),
                r.getTotalAmount()
        );
        dto.setBranchName(r.getBranch().getBranchName());
        dto.setSupplierCode(r.getSupplier().getSupplierCode());
        dto.setSupplierName(r.getSupplier().getSupplierName());
        dto.setSupplierInvoiceNo(r.getSupplierInvoiceNo());
        dto.setReceiverName(r.getReceiver() != null ? r.getReceiver().getFullName() : null);
        dto.setNotes(r.getNotes());
        dto.setAttachmentUrl(r.getAttachmentUrl());
        dto.setItemCount(r.getItems() != null ? r.getItems().size() : 0);

        BigDecimal paid = preloadedPaid != null ? preloadedPaid : BigDecimal.ZERO;
        BigDecimal total = r.getTotalAmount() != null ? r.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal debt = total.subtract(paid);
        if (debt.compareTo(BigDecimal.ZERO) < 0) debt = BigDecimal.ZERO;

        dto.setPaidAmount(paid);
        dto.setRemainingDebt(debt);
        return dto;
    }

    /** Ánh xạ {@link StockReceipt} sang DTO chi tiết đầy đủ bao gồm danh sách {@link StockReceiptResponseDTO.ItemDetail} và thành tiền từng dòng. */
    private StockReceiptResponseDTO toDetailDTO(StockReceipt r) {
        StockReceiptResponseDTO dto = toSummaryDTO(r);
        List<StockReceiptResponseDTO.ItemDetail> detailList = new ArrayList<>();
        if (r.getItems() != null) {
            for (StockReceiptItem item : r.getItems()) {
                BigDecimal lineTotal = item.getQuantity()
                        .multiply(item.getUnitPrice())
                        .setScale(2, RoundingMode.HALF_UP);
                detailList.add(new StockReceiptResponseDTO.ItemDetail(
                        item.getReceiptItemId(),
                        item.getMaterial().getMaterialId(),
                        item.getMaterial().getMaterialCode(),
                        item.getMaterial().getMaterialName(),
                        item.getMaterial().getUnit(),
                        item.getBatchNumber(),
                        item.getExpiryDate(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        lineTotal
                ));
            }
        }
        dto.setItemCount(detailList.size());
        dto.setItems(detailList);
        return dto;
    }

    /** Cắt khoảng trắng đầu cuối chuỗi; trả về {@code null} nếu chuỗi rỗng hoặc chỉ chứa khoảng trắng. */
    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Ném ngoại lệ HTTP 400 Bad Request kèm thông báo nghiệp vụ tiếng Việt. */
    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
