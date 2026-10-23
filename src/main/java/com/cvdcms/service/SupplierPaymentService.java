package com.cvdcms.service;

import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.DTO.SupplierPaymentCreateDTO;
import com.cvdcms.DTO.SupplierPaymentResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface SupplierPaymentService {

    List<SupplierDebtSummaryDTO> getDebtSummaries(
            Long branchId,
            String keyword,
            Long userId
    );

    Page<SupplierPaymentResponseDTO> searchPayments(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    );

    SupplierPaymentResponseDTO createPayment(
            SupplierPaymentCreateDTO dto,
            Long userId
    );
}
