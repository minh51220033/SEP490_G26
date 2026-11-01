package com.cvdcms.repository;

import com.cvdcms.entity.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    List<PaymentAllocation> findByPayment_PaymentIdOrderByLineOrderAsc(Long paymentId);

    List<PaymentAllocation> findByInvoice_InvoiceIdOrderByLineOrderAscCreatedAtAsc(Long invoiceId);

    List<PaymentAllocation> findByInvoiceItem_ItemIdOrderByCreatedAtAsc(Long itemId);

    boolean existsByInvoice_InvoiceId(Long invoiceId);

    @Query("SELECT COALESCE(SUM(pa.allocatedAmount), 0) FROM PaymentAllocation pa WHERE pa.payment.paymentId = :paymentId")
    BigDecimal sumAllocatedByPaymentId(@Param("paymentId") Long paymentId);

    @Query("SELECT COALESCE(SUM(pa.allocatedAmount), 0) FROM PaymentAllocation pa WHERE pa.invoiceItem.itemId = :itemId")
    BigDecimal sumAllocatedByItemId(@Param("itemId") Long itemId);

    @Query("SELECT COALESCE(SUM(pa.allocatedAmount), 0) FROM PaymentAllocation pa WHERE pa.invoice.invoiceId = :invoiceId AND pa.itemType = 'SURCHARGE'")
    BigDecimal sumAllocatedByInvoiceSurcharge(@Param("invoiceId") Long invoiceId);
}

