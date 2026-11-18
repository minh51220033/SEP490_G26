package com.cvdcms.repository;

import com.cvdcms.entity.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
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

    @Query("""
        SELECT pa.payment.branch.branchId,
               UPPER(pa.itemType),
               COALESCE(SUM(pa.allocatedAmount), 0)
        FROM PaymentAllocation pa
        WHERE (:branchIds IS NULL OR pa.payment.branch.branchId IN :branchIds)
          AND pa.payment.paymentType = com.cvdcms.entity.enums.PaymentType.PAYMENT
          AND (:startDate IS NULL OR pa.payment.paidAt >= :startDate)
          AND (:endDate IS NULL OR pa.payment.paidAt <= :endDate)
        GROUP BY pa.payment.branch.branchId, UPPER(pa.itemType)
    """)
    List<Object[]> sumAllocatedByBranchAndItemType(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("""
        SELECT pa.payment.branch.branchId,
               pa.invoice.invoiceId,
               COALESCE(SUM(pa.allocatedAmount), 0)
        FROM PaymentAllocation pa
        WHERE (:branchIds IS NULL OR pa.payment.branch.branchId IN :branchIds)
          AND pa.payment.paymentType = com.cvdcms.entity.enums.PaymentType.PAYMENT
          AND (:startDate IS NULL OR pa.payment.paidAt >= :startDate)
          AND (:endDate IS NULL OR pa.payment.paidAt <= :endDate)
        GROUP BY pa.payment.branch.branchId, pa.invoice.invoiceId
    """)
    List<Object[]> sumAllocatedByBranchAndInvoice(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );
}

