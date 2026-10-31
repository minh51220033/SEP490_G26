package com.cvdcms.service;

import com.cvdcms.DTO.StockReceiptCreateDTO;
import com.cvdcms.DTO.StockReceiptResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

public interface StockReceiptService {
    StockReceiptResponseDTO create(StockReceiptCreateDTO dto, Long userId);

    Page<StockReceiptResponseDTO> search(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    );

    StockReceiptResponseDTO getById(Long receiptId, Long userId);

    StockReceiptResponseDTO signReceipt(Long receiptId, String signatureData, Long userId);
}
