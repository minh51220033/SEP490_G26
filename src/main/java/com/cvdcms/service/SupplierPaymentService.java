package com.cvdcms.service;

import com.cvdcms.DTO.StockReceiptResponseDTO;
import com.cvdcms.DTO.SupplierDebtSummaryDTO;
import com.cvdcms.DTO.SupplierPaymentCreateDTO;
import com.cvdcms.DTO.SupplierPaymentResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collections;
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

    default List<SupplierDebtSummaryDTO> getDebtSummariesForFinance(
            Long branchId,
            String keyword,
            Long userId
    ) {
        return getDebtSummaries(branchId, keyword, userId);
    }

    default Page<SupplierPaymentResponseDTO> searchPaymentsForFinance(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    ) {
        return searchPayments(branchId, supplierId, keyword, fromDate, toDate, pageable, userId);
    }

    default SupplierPaymentResponseDTO createPaymentForFinance(
            SupplierPaymentCreateDTO dto,
            Long userId
    ) {
        return createPayment(dto, userId);
    }

    default List<StockReceiptResponseDTO> getUnpaidReceiptsForFinance(
            Long branchId,
            Long supplierId,
            Long userId
    ) {
        return Collections.emptyList();
    }

    default SupplierDebtSummaryDTO getSupplierDebtDetailForFinance(
            Long branchId,
            Long supplierId,
            Long userId
    ) {
        return null;
    }

    default Page<StockReceiptResponseDTO> getReceiptsForFinance(
            Long branchId,
            Long supplierId,
            String keyword,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable,
            Long userId
    ) {
        return Page.empty(pageable);
    }
}
