package com.cvdcms.repository;

import com.cvdcms.entity.MaterialBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MaterialBatchRepository
        extends JpaRepository<MaterialBatch, Long> {

    @Query("""
        SELECT b
        FROM MaterialBatch b
        JOIN b.material m
        WHERE b.branch.branchId = :branchId
          AND (:keyword IS NULL
               OR LOWER(m.materialCode)
                    LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(m.materialName)
                    LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(b.batchNumber)
                    LIKE LOWER(CONCAT('%', :keyword, '%')))
          AND (:expiryFrom IS NULL OR b.expiryDate >= :expiryFrom)
          AND (:expiryTo IS NULL OR b.expiryDate <= :expiryTo)
        """)
    Page<MaterialBatch> searchBatches(
            @Param("branchId") Long branchId,
            @Param("keyword") String keyword,
            @Param("expiryFrom") LocalDate expiryFrom,
            @Param("expiryTo") LocalDate expiryTo,
            Pageable pageable
    );

    /**
     * Khóa bi quan (PESSIMISTIC_WRITE) bản ghi lô theo chi nhánh, vật tư và số lô (không phân biệt hoa thường)
     * để chống ghi đè khi nhập kho hoặc điều chuyển đồng thời.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from MaterialBatch b where b.branch.branchId=:branchId and b.material.materialId=:materialId and LOWER(b.batchNumber)=LOWER(:batchNumber)")
    Optional<MaterialBatch> findForUpdate(@Param("branchId") Long branchId,
                                          @Param("materialId") Long materialId, @Param("batchNumber") String batchNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT b
        FROM MaterialBatch b
        JOIN FETCH b.material m
        LEFT JOIN FETCH b.supplier s
        WHERE b.batchId = :batchId
        """)
    Optional<MaterialBatch> findByIdForUpdate(@Param("batchId") Long batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT b
        FROM MaterialBatch b
        JOIN FETCH b.material m
        LEFT JOIN FETCH b.supplier s
        WHERE b.branch.branchId = :branchId
          AND b.material.materialId = :materialId
          AND b.quantityRemaining > 0
          AND b.expiryDate >= :today
        ORDER BY b.expiryDate ASC, b.batchId ASC
        """)
    List<MaterialBatch> findAvailableBatchesFefoForUpdate(
            @Param("branchId") Long branchId,
            @Param("materialId") Long materialId,
            @Param("today") LocalDate today
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT b
        FROM MaterialBatch b
        JOIN FETCH b.material m
        LEFT JOIN FETCH b.supplier s
        WHERE b.branch.branchId = :branchId
          AND b.material.materialId = :materialId
          AND b.quantityRemaining > 0
          AND b.expiryDate < :today
        ORDER BY b.expiryDate ASC, b.batchId ASC
        """)
    List<MaterialBatch> findExpiredBatchesForUpdate(
            @Param("branchId") Long branchId,
            @Param("materialId") Long materialId,
            @Param("today") LocalDate today
    );

    @Query("""
        SELECT b
        FROM MaterialBatch b
        JOIN FETCH b.material m
        LEFT JOIN FETCH b.supplier s
        WHERE b.branch.branchId = :branchId
          AND (:materialId IS NULL OR b.material.materialId = :materialId)
          AND b.quantityRemaining > 0
        ORDER BY b.expiryDate ASC, b.batchId ASC
        """)
    List<MaterialBatch> findBatchesWithStockByBranch(
            @Param("branchId") Long branchId,
            @Param("materialId") Long materialId
    );

    @Query("""
        SELECT COALESCE(SUM(b.quantityRemaining), 0)
        FROM MaterialBatch b
        WHERE b.branch.branchId = :branchId
          AND b.material.materialId = :materialId
        """)
    BigDecimal sumRemainingByBranchAndMaterial(
            @Param("branchId") Long branchId,
            @Param("materialId") Long materialId
    );

    /**
     * Đếm số lô vật tư còn tồn (> 0) nhưng đã quá hạn sử dụng tại chi nhánh (phục vụ KPI Card 3).
     */
    @Query("""
        SELECT COUNT(b)
        FROM MaterialBatch b
        WHERE b.branch.branchId = :branchId
          AND b.quantityRemaining > 0
          AND b.expiryDate < :today
        """)
    long countExpiredBatchesWithStock(
            @Param("branchId") Long branchId,
            @Param("today") LocalDate today
    );

    /**
     * Đếm số lô vật tư còn tồn (> 0) sắp hết hạn trong vòng 30 ngày tới tại chi nhánh (phục vụ KPI Card 3).
     */
    @Query("""
        SELECT COUNT(b)
        FROM MaterialBatch b
        WHERE b.branch.branchId = :branchId
          AND b.quantityRemaining > 0
          AND b.expiryDate >= :today
          AND b.expiryDate <= :thresholdDate
        """)
    long countExpiringSoonBatchesWithStock(
            @Param("branchId") Long branchId,
            @Param("today") LocalDate today,
            @Param("thresholdDate") LocalDate thresholdDate
    );
}
