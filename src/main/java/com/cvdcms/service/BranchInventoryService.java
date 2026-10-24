package com.cvdcms.service;

import com.cvdcms.DTO.InventoryItemDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BranchInventoryService {

    Page<InventoryItemDTO> search(
            Long branchId,
            String keyword,
            Boolean lowStock,
            Pageable pageable
    );
}
