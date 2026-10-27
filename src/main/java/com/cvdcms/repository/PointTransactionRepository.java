package com.cvdcms.repository;

import com.cvdcms.entity.PointTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

    List<PointTransaction> findByPatientProfile_ProfileIdOrderByCreatedAtDesc(Long profileId);

    List<PointTransaction> findByInvoice_InvoiceId(Long invoiceId);

    List<PointTransaction> findByPayment_PaymentId(Long paymentId);

    boolean existsByPayment_PaymentId(Long paymentId);

    boolean existsByInvoice_InvoiceIdAndTransactionType(Long invoiceId, com.cvdcms.entity.enums.PointTransactionType transactionType);

    boolean existsByInvoice_InvoiceIdAndTransactionTypeAndPointsGreaterThan(Long invoiceId, com.cvdcms.entity.enums.PointTransactionType transactionType, Integer points);
}
