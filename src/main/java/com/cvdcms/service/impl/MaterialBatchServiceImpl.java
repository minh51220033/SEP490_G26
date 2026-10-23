package com.cvdcms.service.impl;

import com.cvdcms.DTO.MaterialBatchDTO;
import com.cvdcms.entity.MaterialBatch;
import com.cvdcms.repository.MaterialBatchRepository;
import com.cvdcms.service.MaterialBatchService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MaterialBatchServiceImpl implements MaterialBatchService {

    private static final int EXPIRING_SOON_DAYS = 30;

    private final MaterialBatchRepository batchRepository;

    public MaterialBatchServiceImpl(
            MaterialBatchRepository batchRepository) {
        this.batchRepository = batchRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MaterialBatchDTO> searchBatches(
            Long branchId,
            String keyword,
            LocalDate expiryFrom,
            LocalDate expiryTo,
            Pageable pageable) {

        if (branchId == null || branchId <= 0) {
            throw new IllegalArgumentException(
                    "Chi nhánh không hợp lệ.");
        }

        if (expiryFrom != null && expiryTo != null
                && expiryFrom.isAfter(expiryTo)) {
            throw new IllegalArgumentException(
                    "Ngày bắt đầu không được sau ngày kết thúc.");
        }

        String normalizedKeyword =
                keyword == null || keyword.isBlank()
                        ? null
                        : keyword.trim();

        LocalDate today = LocalDate.now();

        Page<MaterialBatch> batches =
                batchRepository.searchBatches(
                        branchId,
                        normalizedKeyword,
                        expiryFrom,
                        expiryTo,
                        pageable
                );

        return batches.map(batch -> toDTO(batch, today));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MaterialBatchDTO> getBatchesWithStock(Long branchId, Long materialId) {
        if (branchId == null || branchId <= 0) {
            throw new IllegalArgumentException("Chi nhánh không hợp lệ.");
        }
        LocalDate today = LocalDate.now();
        return batchRepository.findBatchesWithStockByBranch(branchId, materialId)
                .stream()
                .map(batch -> toDTO(batch, today))
                .collect(Collectors.toList());
    }

    private MaterialBatchDTO toDTO(MaterialBatch batch, LocalDate today) {
        LocalDate expiryDate = batch.getExpiryDate();
        long daysToExpiry = ChronoUnit.DAYS.between(today, expiryDate);
        String warning;

        if (expiryDate.isBefore(today)) {
            warning = "EXPIRED";
        } else if (!expiryDate.isAfter(today.plusDays(EXPIRING_SOON_DAYS))) {
            warning = "EXPIRING_SOON";
        } else {
            warning = "NONE";
        }

        return new MaterialBatchDTO(
                batch.getBatchId(),
                batch.getBranch().getBranchId(),
                batch.getBranch().getBranchName(),
                batch.getMaterial().getMaterialId(),
                batch.getMaterial().getMaterialCode(),
                batch.getMaterial().getMaterialName(),
                batch.getMaterial().getUnit(),
                batch.getBatchNumber(),
                expiryDate,
                batch.getQuantityRemaining(),
                batch.getImportPrice(),
                batch.getSupplier() == null ? null : batch.getSupplier().getSupplierId(),
                batch.getSupplier() == null ? null : batch.getSupplier().getSupplierName(),
                batch.getStatus() == null ? null : batch.getStatus().name(),
                daysToExpiry,
                warning
        );
    }
}
