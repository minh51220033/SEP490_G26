package com.cvdcms.service.impl;

import com.cvdcms.DTO.StockIssueCreateDTO;
import com.cvdcms.DTO.StockIssueResponseDTO;
import com.cvdcms.entity.*;
import com.cvdcms.entity.enums.BatchStatus;
import com.cvdcms.entity.enums.StaffStatus;
import com.cvdcms.entity.enums.StockIssueType;
import com.cvdcms.repository.*;
import com.cvdcms.service.BranchAccessService;
import com.cvdcms.service.StockIssueService;
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

/**
 * Xử lý nghiệp vụ Phiếu xuất kho nội bộ / hủy hết hạn (Stock Issue) và Điều chuyển liên chi nhánh (Transfer):
 * - Hỗ trợ xuất đích danh theo lô hoặc tự động phân bổ theo FEFO (First Expired, First Out).
 * - Chặn xuất sử dụng hoặc điều chuyển đối với lô đã hết hạn (chỉ cho phép xuất hủy DISCARD_EXPIRED).
 * - Sử dụng bộ nhớ đệm (cache) trong cùng transaction để cộng/trừ tồn kho chính xác khi phiếu có nhiều dòng trùng vật tư.
 * - Tự động trừ kho nguồn và cộng kho đích (kèm tạo/cập nhật lô tương ứng tại chi nhánh đích) khi điều chuyển.
 */
@Service
public class StockIssueServiceImpl implements StockIssueService {

    // Bộ đếm tuần tự đảm bảo sinh mã phiếu xuất (PX...) và điều chuyển (DC...) không bị trùng lặp
    private static final AtomicLong CODE_SEQ = new AtomicLong(System.currentTimeMillis());

    private final BranchRepository branchRepository;
    private final MaterialRepository materialRepository;
    private final MaterialBatchRepository batchRepository;
    private final BranchInventoryRepository inventoryRepository;
    private final StockIssueRepository issueRepository;
    private final StaffProfileRepository staffRepository;
    private final UserRepository userRepository;
    private final BranchAccessService accessService;

    public StockIssueServiceImpl(
            BranchRepository branchRepository,
            MaterialRepository materialRepository,
            MaterialBatchRepository batchRepository,
            BranchInventoryRepository inventoryRepository,
            StockIssueRepository issueRepository,
            StaffProfileRepository staffRepository,
            UserRepository userRepository,
            BranchAccessService accessService) {
        this.branchRepository = branchRepository;
        this.materialRepository = materialRepository;
        this.batchRepository = batchRepository;
        this.inventoryRepository = inventoryRepository;
        this.issueRepository = issueRepository;
        this.staffRepository = staffRepository;
        this.userRepository = userRepository;
        this.accessService = accessService;
    }

    @Override
    @Transactional
    public StockIssueResponseDTO create(StockIssueCreateDTO dto, Long userId) {
        if (dto == null || dto.getBranchId() == null || dto.getBranchId() <= 0) {
            bad("Chi nhánh xuất không hợp lệ.");
        }
        if (!accessService.canAccessBranch(userId, dto.getBranchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xuất/điều chuyển kho tại chi nhánh này.");
        }
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            bad("Phiếu phải có ít nhất một dòng vật tư.");
        }

        StockIssueType type = parseIssueType(dto.getIssueType());
        LocalDate today = LocalDate.now();
        LocalDate issueDate = dto.getIssueDate() == null ? today : dto.getIssueDate();
        if (issueDate.isAfter(today)) {
            bad("Ngày lập phiếu không được ở tương lai.");
        }

        String notes = trimToNull(dto.getNotes());
        if (notes != null && notes.length() > 255) {
            bad("Ghi chú phiếu tối đa 255 ký tự.");
        }

        Branch sourceBranch = branchRepository.findById(dto.getBranchId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh nguồn."));
        if (!Boolean.TRUE.equals(sourceBranch.getIsActive())) {
            bad("Chi nhánh nguồn đã ngừng hoạt động.");
        }

        Branch destinationBranch = null;
        if (type == StockIssueType.TRANSFER) {
            if (dto.getDestinationBranchId() == null || dto.getDestinationBranchId() <= 0) {
                bad("Vui lòng chọn chi nhánh đích khi điều chuyển kho.");
            }
            if (dto.getDestinationBranchId().equals(sourceBranch.getBranchId())) {
                bad("Chi nhánh đích phải khác chi nhánh nguồn.");
            }
            destinationBranch = branchRepository.findById(dto.getDestinationBranchId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh đích."));
            if (!Boolean.TRUE.equals(destinationBranch.getIsActive())) {
                bad("Chi nhánh đích đã ngừng hoạt động.");
            }
        }

        StaffProfile issuer = resolveOrCreateStaffProfile(userId);

        StockIssue issue = new StockIssue();
        issue.setBranch(sourceBranch);
        issue.setIssueType(type);
        issue.setIssueDate(issueDate);
        issue.setDestinationBranch(destinationBranch);
        issue.setIssuer(issuer);
        issue.setNotes(notes);
        issue.setIssueCode(generateUniqueIssueCode(type == StockIssueType.TRANSFER ? "DC" : "PX"));

        List<StockIssueItem> issueItems = new ArrayList<>();

        // Bộ nhớ đệm trong cùng transaction để xử lý chính xác khi nhiều dòng cùng vật tư hoặc cùng lô
        Map<Long, BranchInventory> sourceInventoryCache = new HashMap<>();
        Map<Long, MaterialBatch> sourceBatchCache = new HashMap<>();
        Map<Long, BranchInventory> destInventoryCache = new HashMap<>();
        Map<String, MaterialBatch> destBatchCache = new HashMap<>();

        for (StockIssueCreateDTO.Item input : dto.getItems()) {
            if (input == null || input.getMaterialId() == null || input.getMaterialId() <= 0) {
                bad("Vật tư không hợp lệ.");
            }
            if (input.getQuantity() == null || input.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                bad("Số lượng xuất phải lớn hơn 0.");
            }

            BigDecimal requestedQty = input.getQuantity().setScale(3, RoundingMode.HALF_UP);
            String itemNote = trimToNull(input.getNotes());
            if (itemNote != null && itemNote.length() > 255) {
                bad("Ghi chú dòng vật tư tối đa 255 ký tự.");
            }

            Material material = materialRepository.findById(input.getMaterialId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Không tìm thấy vật tư ID " + input.getMaterialId()));

            BranchInventory sourceInv = sourceInventoryCache.get(material.getMaterialId());
            if (sourceInv == null) {
                sourceInv = inventoryRepository.findForUpdate(sourceBranch.getBranchId(), material.getMaterialId())
                        .orElse(null);
            }

            if (sourceInv == null || sourceInv.getCurrentStock().compareTo(requestedQty) < 0) {
                BigDecimal current = sourceInv == null ? BigDecimal.ZERO : sourceInv.getCurrentStock();
                bad("Vật tư " + material.getMaterialCode() + " (" + material.getMaterialName()
                        + ") không đủ tồn kho tại chi nhánh (Hiện có: " + current + ", Yêu cầu: " + requestedQty + ").");
            }

            if (input.getBatchId() != null && input.getBatchId() > 0) {
                // Xuất đích danh từ 1 lô cụ thể
                MaterialBatch batch = sourceBatchCache.get(input.getBatchId());
                if (batch == null) {
                    batch = batchRepository.findByIdForUpdate(input.getBatchId())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                    "Không tìm thấy lô vật tư ID " + input.getBatchId()));
                }

                if (!batch.getBranch().getBranchId().equals(sourceBranch.getBranchId())
                        || !batch.getMaterial().getMaterialId().equals(material.getMaterialId())) {
                    bad("Lô " + batch.getBatchNumber() + " không thuộc vật tư hoặc chi nhánh đã chọn.");
                }

                if ((type == StockIssueType.INTERNAL_USE || type == StockIssueType.TRANSFER)
                        && batch.getExpiryDate().isBefore(today)) {
                    bad("Lô " + batch.getBatchNumber() + " của vật tư " + material.getMaterialCode()
                            + " đã hết hạn (" + batch.getExpiryDate()
                            + "). Không được xuất sử dụng hoặc điều chuyển lô hết hạn.");
                }

                if (batch.getQuantityRemaining().compareTo(requestedQty) < 0) {
                    bad("Số lượng xuất (" + requestedQty + ") vượt quá tồn khả dụng của lô "
                            + batch.getBatchNumber() + " (Còn lại: " + batch.getQuantityRemaining() + ").");
                }

                batch = deductFromBatch(batch, requestedQty, today);
                sourceBatchCache.put(batch.getBatchId(), batch);

                StockIssueItem issueItem = new StockIssueItem();
                issueItem.setIssue(issue);
                issueItem.setMaterial(material);
                issueItem.setBatch(batch);
                issueItem.setQuantity(requestedQty);
                issueItem.setNotes(itemNote);
                issueItems.add(issueItem);

                if (type == StockIssueType.TRANSFER && destinationBranch != null) {
                    creditDestinationBranch(destinationBranch, material, batch, requestedQty, today, destBatchCache, destInventoryCache);
                }
            } else {
                // Tự động phân bổ lô theo chuẩn FEFO (Hạn sử dụng gần nhất xuất trước)
                List<MaterialBatch> candidateBatches;
                if (type == StockIssueType.DISCARD_EXPIRED) {
                    candidateBatches = batchRepository.findExpiredBatchesForUpdate(
                            sourceBranch.getBranchId(), material.getMaterialId(), today);
                } else {
                    candidateBatches = batchRepository.findAvailableBatchesFefoForUpdate(
                            sourceBranch.getBranchId(), material.getMaterialId(), today);
                }

                // Đồng bộ với các lô đã được trừ trước đó trong cùng phiếu (nếu có)
                List<MaterialBatch> syncedCandidates = new ArrayList<>();
                for (MaterialBatch b : candidateBatches) {
                    MaterialBatch cached = sourceBatchCache.get(b.getBatchId());
                    syncedCandidates.add(cached != null ? cached : b);
                }

                BigDecimal totalAvailableInBatches = syncedCandidates.stream()
                        .map(MaterialBatch::getQuantityRemaining)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                if (totalAvailableInBatches.compareTo(requestedQty) < 0) {
                    if (type == StockIssueType.DISCARD_EXPIRED) {
                        bad("Tổng lượng tồn các lô đã hết hạn của vật tư " + material.getMaterialCode()
                                + " (" + totalAvailableInBatches + ") nhỏ hơn số lượng yêu cầu hủy (" + requestedQty
                                + "). Vui lòng chọn lô cụ thể nếu muốn hủy lô chưa hết hạn.");
                    } else {
                        bad("Tổng lượng tồn còn hạn sử dụng của vật tư " + material.getMaterialCode()
                                + " (" + totalAvailableInBatches + ") không đủ để xuất (" + requestedQty + ").");
                    }
                }

                BigDecimal remainingToDeduct = requestedQty;
                for (MaterialBatch batch : syncedCandidates) {
                    if (remainingToDeduct.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                    BigDecimal avail = batch.getQuantityRemaining();
                    if (avail.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    BigDecimal alloc = remainingToDeduct.min(avail);
                    batch = deductFromBatch(batch, alloc, today);
                    sourceBatchCache.put(batch.getBatchId(), batch);

                    StockIssueItem issueItem = new StockIssueItem();
                    issueItem.setIssue(issue);
                    issueItem.setMaterial(material);
                    issueItem.setBatch(batch);
                    issueItem.setQuantity(alloc);
                    issueItem.setNotes(itemNote);
                    issueItems.add(issueItem);

                    if (type == StockIssueType.TRANSFER && destinationBranch != null) {
                        creditDestinationBranch(destinationBranch, material, batch, alloc, today, destBatchCache, destInventoryCache);
                    }

                    remainingToDeduct = remainingToDeduct.subtract(alloc);
                }
            }

            // Trừ tồn tổng hợp tại chi nhánh nguồn
            sourceInv.setCurrentStock(sourceInv.getCurrentStock().subtract(requestedQty));
            if (sourceInv.getCurrentStock().compareTo(BigDecimal.ZERO) < 0) {
                bad("Tồn kho tổng hợp của vật tư " + material.getMaterialCode() + " không được âm.");
            }
            BranchInventory savedSourceInv = inventoryRepository.save(sourceInv);
            if (savedSourceInv != null) {
                sourceInv = savedSourceInv;
            }
            sourceInventoryCache.put(material.getMaterialId(), sourceInv);
        }

        issue.setItems(issueItems);
        StockIssue saved = issueRepository.save(issue);
        return toDetailDTO(saved != null ? saved : issue);
    }

    private String generateUniqueIssueCode(String prefix) {
        String code;
        do {
            long next = CODE_SEQ.updateAndGet(prev -> Math.max(System.currentTimeMillis(), prev + 1));
            code = prefix + next;
        } while (issueRepository.existsByIssueCode(code));
        return code;
    }

    private MaterialBatch deductFromBatch(MaterialBatch batch, BigDecimal qty, LocalDate today) {
        BigDecimal nextQty = batch.getQuantityRemaining().subtract(qty);
        if (nextQty.compareTo(BigDecimal.ZERO) < 0) {
            bad("Số lượng còn lại của lô " + batch.getBatchNumber() + " không được âm.");
        }
        batch.setQuantityRemaining(nextQty);
        if (nextQty.compareTo(BigDecimal.ZERO) == 0) {
            batch.setStatus(BatchStatus.OUT_OF_STOCK);
        } else if (batch.getExpiryDate().isBefore(today)) {
            batch.setStatus(BatchStatus.EXPIRED);
        } else {
            batch.setStatus(BatchStatus.ACTIVE);
        }
        MaterialBatch savedBatch = batchRepository.save(batch);
        return savedBatch != null ? savedBatch : batch;
    }

    private void creditDestinationBranch(
            Branch destinationBranch,
            Material material,
            MaterialBatch sourceBatch,
            BigDecimal qty,
            LocalDate today,
            Map<String, MaterialBatch> destBatchCache,
            Map<Long, BranchInventory> destInventoryCache) {

        String destBatchKey = destinationBranch.getBranchId() + "::" + material.getMaterialId() + "::"
                + sourceBatch.getBatchNumber().toUpperCase(Locale.ROOT);

        MaterialBatch destBatch = destBatchCache.get(destBatchKey);
        if (destBatch == null) {
            destBatch = batchRepository.findForUpdate(
                            destinationBranch.getBranchId(),
                            material.getMaterialId(),
                            sourceBatch.getBatchNumber())
                    .orElse(null);
        }

        if (destBatch == null) {
            destBatch = new MaterialBatch();
            destBatch.setBranch(destinationBranch);
            destBatch.setMaterial(material);
            destBatch.setBatchNumber(sourceBatch.getBatchNumber());
            destBatch.setExpiryDate(sourceBatch.getExpiryDate());
            destBatch.setQuantityRemaining(qty);
            destBatch.setImportPrice(sourceBatch.getImportPrice() != null ? sourceBatch.getImportPrice() : BigDecimal.ZERO);
            destBatch.setSupplier(sourceBatch.getSupplier());
        } else {
            destBatch.setQuantityRemaining(destBatch.getQuantityRemaining().add(qty));
            if (destBatch.getImportPrice() == null || destBatch.getImportPrice().compareTo(BigDecimal.ZERO) == 0) {
                destBatch.setImportPrice(sourceBatch.getImportPrice() != null ? sourceBatch.getImportPrice() : BigDecimal.ZERO);
            }
            if (destBatch.getSupplier() == null && sourceBatch.getSupplier() != null) {
                destBatch.setSupplier(sourceBatch.getSupplier());
            }
        }

        if (destBatch.getQuantityRemaining().compareTo(BigDecimal.ZERO) <= 0) {
            destBatch.setStatus(BatchStatus.OUT_OF_STOCK);
        } else if (destBatch.getExpiryDate().isBefore(today)) {
            destBatch.setStatus(BatchStatus.EXPIRED);
        } else {
            destBatch.setStatus(BatchStatus.ACTIVE);
        }
        MaterialBatch savedDestBatch = batchRepository.save(destBatch);
        if (savedDestBatch != null) {
            destBatch = savedDestBatch;
        }
        destBatchCache.put(destBatchKey, destBatch);

        BranchInventory destInv = destInventoryCache.get(material.getMaterialId());
        if (destInv == null) {
            destInv = inventoryRepository.findForUpdate(
                            destinationBranch.getBranchId(),
                            material.getMaterialId())
                    .orElseGet(() -> {
                        BranchInventory bi = new BranchInventory();
                        bi.setBranch(destinationBranch);
                        bi.setMaterial(material);
                        bi.setCurrentStock(BigDecimal.ZERO);
                        return bi;
                    });
        }
        destInv.setCurrentStock(destInv.getCurrentStock().add(qty));
        BranchInventory savedDestInv = inventoryRepository.save(destInv);
        if (savedDestInv != null) {
            destInv = savedDestInv;
        }
        destInventoryCache.put(material.getMaterialId(), destInv);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockIssueResponseDTO> searchIssues(
            Long branchId,
            String issueType,
            boolean excludeTransfer,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId) {

        if (branchId == null || branchId <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!accessService.canAccessBranch(userId, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập kho của chi nhánh này.");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            bad("Từ ngày không được sau đến ngày.");
        }

        StockIssueType parsedType = null;
        if (issueType != null && !issueType.isBlank()) {
            parsedType = parseIssueType(issueType);
        }

        Page<StockIssue> page = issueRepository.searchIssues(
                branchId, parsedType, excludeTransfer, trimToNull(keyword), fromDate, toDate, pageable);

        return page.map(this::toSummaryDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockIssueResponseDTO> searchTransfers(
            Long branchId,
            Long destinationBranchId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId) {

        if (branchId == null || branchId <= 0) {
            bad("Chi nhánh không hợp lệ.");
        }
        if (!accessService.canAccessBranch(userId, branchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập kho của chi nhánh này.");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            bad("Từ ngày không được sau đến ngày.");
        }

        Page<StockIssue> page = issueRepository.searchTransfers(
                branchId, destinationBranchId, trimToNull(keyword), fromDate, toDate, pageable);

        return page.map(this::toSummaryDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public StockIssueResponseDTO getById(Long issueId, Long userId) {
        if (issueId == null || issueId <= 0) {
            bad("Mã phiếu xuất không hợp lệ.");
        }
        StockIssue issue = issueRepository.findByIdWithDetails(issueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu xuất/điều chuyển."));

        boolean canAccessSource = accessService.canAccessBranch(userId, issue.getBranch().getBranchId());
        boolean canAccessDest = issue.getDestinationBranch() != null
                && accessService.canAccessBranch(userId, issue.getDestinationBranch().getBranchId());

        if (!canAccessSource && !canAccessDest) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem phiếu này.");
        }

        return toDetailDTO(issue);
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

    private StockIssueType parseIssueType(String value) {
        if (value == null || value.isBlank()) {
            return StockIssueType.INTERNAL_USE;
        }
        try {
            return StockIssueType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            bad("Loại phiếu xuất không hợp lệ (chỉ chấp nhận INTERNAL_USE, TRANSFER, DISCARD_EXPIRED).");
            return StockIssueType.INTERNAL_USE;
        }
    }

    private StockIssueResponseDTO toSummaryDTO(StockIssue si) {
        StockIssueResponseDTO dto = new StockIssueResponseDTO();
        dto.setIssueId(si.getIssueId());
        dto.setIssueCode(si.getIssueCode());
        dto.setBranchId(si.getBranch().getBranchId());
        dto.setBranchName(si.getBranch().getBranchName());
        dto.setIssueType(si.getIssueType() != null ? si.getIssueType().name() : null);
        dto.setIssueDate(si.getIssueDate());
        if (si.getDestinationBranch() != null) {
            dto.setDestinationBranchId(si.getDestinationBranch().getBranchId());
            dto.setDestinationBranchName(si.getDestinationBranch().getBranchName());
        }
        dto.setIssuerName(si.getIssuer() != null ? si.getIssuer().getFullName() : null);
        dto.setNotes(si.getNotes());

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalVal = BigDecimal.ZERO;
        int count = 0;
        if (si.getItems() != null) {
            count = si.getItems().size();
            for (StockIssueItem item : si.getItems()) {
                BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
                totalQty = totalQty.add(qty);
                BigDecimal price = (item.getBatch() != null && item.getBatch().getImportPrice() != null)
                        ? item.getBatch().getImportPrice()
                        : BigDecimal.ZERO;
                totalVal = totalVal.add(qty.multiply(price).setScale(2, RoundingMode.HALF_UP));
            }
        }
        dto.setItemCount(count);
        dto.setTotalQuantity(totalQty);
        dto.setTotalEstimatedValue(totalVal);
        return dto;
    }

    private StockIssueResponseDTO toDetailDTO(StockIssue si) {
        StockIssueResponseDTO dto = toSummaryDTO(si);
        List<StockIssueResponseDTO.ItemDetail> details = new ArrayList<>();
        if (si.getItems() != null) {
            for (StockIssueItem item : si.getItems()) {
                StockIssueResponseDTO.ItemDetail d = new StockIssueResponseDTO.ItemDetail();
                d.setIssueItemId(item.getIssueItemId());
                d.setMaterialId(item.getMaterial().getMaterialId());
                d.setMaterialCode(item.getMaterial().getMaterialCode());
                d.setMaterialName(item.getMaterial().getMaterialName());
                d.setUnit(item.getMaterial().getUnit());
                if (item.getBatch() != null) {
                    d.setBatchId(item.getBatch().getBatchId());
                    d.setBatchNumber(item.getBatch().getBatchNumber());
                    d.setExpiryDate(item.getBatch().getExpiryDate());
                    d.setImportPrice(item.getBatch().getImportPrice());
                    BigDecimal price = item.getBatch().getImportPrice() != null
                            ? item.getBatch().getImportPrice()
                            : BigDecimal.ZERO;
                    d.setLineValue(item.getQuantity().multiply(price).setScale(2, RoundingMode.HALF_UP));
                } else {
                    d.setImportPrice(BigDecimal.ZERO);
                    d.setLineValue(BigDecimal.ZERO);
                }
                d.setQuantity(item.getQuantity());
                d.setNotes(item.getNotes());
                details.add(d);
            }
        }
        dto.setItems(details);
        dto.setItemCount(details.size());
        return dto;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
