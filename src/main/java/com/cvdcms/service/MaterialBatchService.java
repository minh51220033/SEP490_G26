package com.cvdcms.service;

import com.cvdcms.DTO.MaterialBatchDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface MaterialBatchService {

    Page<MaterialBatchDTO> searchBatches(
            Long branchId,
            String keyword,
            LocalDate expiryFrom,
            LocalDate expiryTo,
            Pageable pageable
    );

    List<MaterialBatchDTO> getBatchesWithStock(
            Long branchId,
            Long materialId
    );
}
