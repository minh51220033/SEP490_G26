package com.cvdcms.repository;

import com.cvdcms.entity.StockReceipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockReceiptRepository extends JpaRepository<StockReceipt, Long> {
    boolean existsByReceiptCode(String receiptCode);

    @Query(
            value = """
                SELECT r
                FROM StockReceipt r
                JOIN FETCH r.branch b
                JOIN FETCH r.supplier s
                JOIN FETCH r.receiver rec
                WHERE b.branchId = :branchId
                  AND (:supplierId IS NULL OR s.supplierId = :supplierId)
                  AND (:keyword IS NULL
                       OR LOWER(r.receiptCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(r.supplierInvoiceNo, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierCode) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:fromDate IS NULL OR r.receiptDate >= :fromDate)
                  AND (:toDate IS NULL OR r.receiptDate <= :toDate)
                """,
            countQuery = """
                SELECT COUNT(r)
                FROM StockReceipt r
                JOIN r.branch b
                JOIN r.supplier s
                WHERE b.branchId = :branchId
                  AND (:supplierId IS NULL OR s.supplierId = :supplierId)
                  AND (:keyword IS NULL
                       OR LOWER(r.receiptCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(COALESCE(r.supplierInvoiceNo, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       OR LOWER(s.supplierCode) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:fromDate IS NULL OR r.receiptDate >= :fromDate)
                  AND (:toDate IS NULL OR r.receiptDate <= :toDate)
                """
    )
    Page<StockReceipt> searchReceipts(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId,
            @Param("keyword") String keyword,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable
    );

    @Query("""
        SELECT DISTINCT r
        FROM StockReceipt r
        JOIN FETCH r.branch b
        JOIN FETCH r.supplier s
        JOIN FETCH r.receiver rec
        LEFT JOIN FETCH r.items i
        LEFT JOIN FETCH i.material m
        WHERE r.receiptId = :receiptId
        """)
    Optional<StockReceipt> findByIdWithDetails(@Param("receiptId") Long receiptId);

    /**
     * Tính tổng tiền nhập kho của một nhà cung cấp tại một chi nhánh để tính công nợ.
     */
    @Query("""
        SELECT COALESCE(SUM(r.totalAmount), 0)
        FROM StockReceipt r
        WHERE r.branch.branchId = :branchId
          AND r.supplier.supplierId = :supplierId
        """)
    BigDecimal sumTotalAmountByBranchAndSupplier(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId
    );

    /**
     * Đếm số lượng phiếu nhập kho của một nhà cung cấp tại một chi nhánh.
     */
    @Query("""
        SELECT COUNT(r)
        FROM StockReceipt r
        WHERE r.branch.branchId = :branchId
          AND r.supplier.supplierId = :supplierId
        """)
    long countReceiptsByBranchAndSupplier(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId
    );

    /**
     * Lấy danh sách phiếu nhập kho theo chi nhánh và nhà cung cấp (mới nhất trước).
     */
    @Query("""
        SELECT r
        FROM StockReceipt r
        JOIN FETCH r.supplier s
        WHERE r.branch.branchId = :branchId
          AND (:supplierId IS NULL OR s.supplierId = :supplierId)
        ORDER BY r.receiptDate DESC, r.receiptId DESC
        """)
    List<StockReceipt> findByBranchAndSupplierOrderByReceiptDateDesc(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId
    );
}
