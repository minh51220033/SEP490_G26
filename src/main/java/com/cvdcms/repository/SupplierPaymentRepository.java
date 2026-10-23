package com.cvdcms.repository;

import com.cvdcms.entity.SupplierPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {

    boolean existsByPaymentCode(String paymentCode);

    @Query(
            value = """
                SELECT sp
                FROM SupplierPayment sp
                JOIN FETCH sp.branch b
                JOIN FETCH sp.supplier s
                LEFT JOIN FETCH sp.receipt r
                JOIN FETCH sp.paidBy pb
                WHERE b.branchId = :branchId
                  AND (:supplierId IS NULL OR s.supplierId = :supplierId)
                  AND (:keyword IS NULL
                       OR LOWER(sp.paymentCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(sp.referenceNo, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(r.receiptCode, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:fromDate IS NULL OR sp.paymentDate >= :fromDate)
                  AND (:toDate IS NULL OR sp.paymentDate <= :toDate)
                """,
            countQuery = """
                SELECT COUNT(sp)
                FROM SupplierPayment sp
                JOIN sp.branch b
                JOIN sp.supplier s
                LEFT JOIN sp.receipt r
                WHERE b.branchId = :branchId
                  AND (:supplierId IS NULL OR s.supplierId = :supplierId)
                  AND (:keyword IS NULL
                       OR LOWER(sp.paymentCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(sp.referenceNo, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(r.receiptCode, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:fromDate IS NULL OR sp.paymentDate >= :fromDate)
                  AND (:toDate IS NULL OR sp.paymentDate <= :toDate)
                """
    )
    Page<SupplierPayment> searchPayments(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId,
            @Param("keyword") String keyword,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable
    );

    @Query("""
        SELECT COALESCE(SUM(sp.amount), 0)
        FROM SupplierPayment sp
        WHERE sp.branch.branchId = :branchId
          AND sp.supplier.supplierId = :supplierId
        """)
    BigDecimal sumPaidAmountByBranchAndSupplier(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId
    );

    @Query("""
        SELECT COALESCE(SUM(sp.amount), 0)
        FROM SupplierPayment sp
        WHERE sp.receipt.receiptId = :receiptId
        """)
    BigDecimal sumPaidAmountByReceipt(@Param("receiptId") Long receiptId);
}
