package com.cvdcms.service;

import com.cvdcms.DTO.PaymentRequestDTO;
import com.cvdcms.entity.Payment;
import com.cvdcms.entity.enums.PaymentMethod;
import com.cvdcms.entity.enums.PaymentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface PaymentService {

    Payment processPayment(PaymentRequestDTO request, Long cashierUserId);

    Payment processAdvanceDeposit(PaymentRequestDTO request, Long cashierUserId);

    Page<Payment> getPayments(
            Long branchId,
            PaymentType paymentType,
            PaymentMethod paymentMethod,
            String keyword,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    );

    List<Payment> getPaymentsByInvoiceId(Long invoiceId);

    List<Payment> getPatientDepositHistory(Long profileId);

    Payment getPaymentById(Long paymentId);
}
