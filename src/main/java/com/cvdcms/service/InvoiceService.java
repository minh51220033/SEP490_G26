package com.cvdcms.service;

import com.cvdcms.DTO.FinancialMetricsDTO;
import com.cvdcms.DTO.InvoiceAdjustmentRequestDTO;
import com.cvdcms.DTO.InvoiceCalculationPreviewDTO;
import com.cvdcms.DTO.InvoiceCreateRequestDTO;
import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.InvoiceAuditLog;
import com.cvdcms.entity.PerformedService;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface InvoiceService {

    Invoice createInvoice(InvoiceCreateRequestDTO request, Long cashierUserId);

    InvoiceCalculationPreviewDTO calculatePreview(InvoiceCreateRequestDTO request);

    Page<Invoice> getInvoices(
            Long branchId,
            InvoicePaymentStatus paymentStatus,
            InvoiceStatus status,
            String keyword,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    );

    Invoice getInvoiceById(Long invoiceId);

    Invoice getInvoiceWithDetails(Long invoiceId);

    Invoice adjustInvoice(Long invoiceId, InvoiceAdjustmentRequestDTO request, Long staffUserId);

    Invoice cancelInvoice(Long invoiceId, String reason, Long staffUserId);

    List<InvoiceAuditLog> getInvoiceAuditLogs(Long invoiceId);

    FinancialMetricsDTO getFinancialMetrics(Long branchId, LocalDateTime startDate, LocalDateTime endDate);

    List<PerformedService> getUnbilledPerformedServices(Long visitId);
 
    void finalizeInvoiceBenefits(Invoice invoice);

    Invoice createMedicineInvoice(com.cvdcms.DTO.MedicineInvoiceCreateRequestDTO request, Long cashierUserId);

    void recordPrescriptionChoice(Long prescriptionId, String choice, String note, Long cashierUserId);

    List<com.cvdcms.DTO.PrescriptionResponseDTO> getHandedOverPrescriptions(Long branchId, Long visitId, Long profileId);
}
