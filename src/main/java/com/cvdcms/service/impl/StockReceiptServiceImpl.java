package com.cvdcms.service.impl;

import com.cvdcms.DTO.StockReceiptCreateDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.BatchStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.repository.*;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.StockReceiptService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Xử lý nghiệp vụ Phiếu nhập kho (Stock Receipt):
 * - Kiểm tra phân quyền chi nhánh, nhà cung cấp, định dạng số hóa đơn và số lô (regex).
 * - Tự động tạo mới hoặc cộng dồn lô vật tư (MaterialBatch) và kiểm tra khớp hạn sử dụng.
 * - Cộng dồn số lượng tồn kho tổng hợp tại chi nhánh (BranchInventory) trong cùng transaction.
 * - Tính toán tổng tiền phiếu nhập, số tiền đã thanh toán và công nợ còn lại.
 */
@Service
public class StockReceiptServiceImpl implements StockReceiptService {

    // Biểu thức chính quy kiểm tra định dạng Số lô (chữ, số, ., _, /, - từ 1 đến 50 ký tự)
    private static final Pattern BATCH_NUMBER_PATTERN =
            Pattern.compile("^[A-Za-z0-9._/-]{1,50}$");

    // Biểu thức chính quy kiểm tra định dạng Số hóa đơn nhà cung cấp
    private static final Pattern INVOICE_NO_PATTERN =
            Pattern.compile("^[A-Za-z0-9._/-]{1,50}$");

    // Bộ đếm tuần tự đảm bảo sinh mã phiếu nhập (PN...) duy nhất ngay cả khi gọi đồng thời trong cùng mili-giây
    private static final AtomicLong CODE_SEQ = new AtomicLong(System.currentTimeMillis());

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

        String attachmentUrl = trimToNull(dto.getAttachmentUrl());
        if (attachmentUrl != null && attachmentUrl.length() > 500) {
            bad("Đường dẫn chứng từ đính kèm tối đa 500 ký tự.");
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

        // Bộ nhớ đệm trong cùng transaction để xử lý trường hợp nhiều dòng cùng vật tư hoặc cùng số lô
        Map<String, MaterialBatch> batchCache = new HashMap<>();
        Map<Long, BranchInventory> inventoryCache = new HashMap<>();

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

            StockReceiptItem item = new StockReceiptItem();
            item.setReceipt(receipt);
            item.setMaterial(material);
            item.setBatchNumber(batchNo);
            item.setExpiryDate(input.getExpiryDate());
            item.setQuantity(quantity);
            item.setUnitPrice(unitPrice);
            items.add(item);

            String batchKey = material.getMaterialId() + "::" + batchNo;
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

        receipt.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        receipt.setItems(items);
        StockReceipt saved = receiptRepository.save(receipt);

        return toDetailDTO(saved);
    }

    private String generateUniqueReceiptCode() {
        String code;
        do {
            long next = CODE_SEQ.updateAndGet(prev -> Math.max(System.currentTimeMillis(), prev + 1));
            code = "PN" + next;
        } while (receiptRepository.existsByReceiptCode(code));
        return code;
    }

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

        return page.map(this::toSummaryDTO);
    }

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

    private StockReceiptResponseDTO toSummaryDTO(StockReceipt r) {
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

        BigDecimal paid = supplierPaymentRepository.sumPaidAmountByReceipt(r.getReceiptId());
        if (paid == null) paid = BigDecimal.ZERO;
        BigDecimal total = r.getTotalAmount() != null ? r.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal debt = total.subtract(paid);
        if (debt.compareTo(BigDecimal.ZERO) < 0) debt = BigDecimal.ZERO;

        dto.setPaidAmount(paid);
        dto.setRemainingDebt(debt);
        return dto;
    }

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

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
