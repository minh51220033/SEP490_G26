package com.cvdcms.repository;

import com.cvdcms.entity.BranchInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BranchInventoryRepository
        extends JpaRepository<BranchInventory, Long> {

    @Query("""
    SELECT i
    FROM BranchInventory i
    JOIN i.material m
    JOIN i.branch b
    WHERE b.branchId = :branchId
      AND (
          :keyword IS NULL
          OR LOWER(m.materialCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
          OR LOWER(m.materialName) LIKE LOWER(CONCAT('%', :keyword, '%'))
          OR LOWER(m.category) LIKE LOWER(CONCAT('%', :keyword, '%'))
      )
      AND (
          :lowStock IS NULL
          OR (
              :lowStock = TRUE
              AND i.currentStock <= m.minStockThreshold
          )
          OR (
              :lowStock = FALSE
              AND i.currentStock > m.minStockThreshold
          )
      )
    """)
    Page<BranchInventory> searchByBranch(
            @Param("branchId") Long branchId,
            @Param("keyword") String keyword,
            @Param("lowStock") Boolean lowStock,
            Pageable pageable
    );

    Optional<BranchInventory> findByBranch_BranchIdAndMaterial_MaterialId(
            Long branchId,
            Long materialId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT i
        FROM BranchInventory i
        WHERE i.branch.branchId = :branchId
          AND i.material.materialId = :materialId
        """)
    Optional<BranchInventory> findForUpdate(
            @Param("branchId") Long branchId,
            @Param("materialId") Long materialId
    );

    @Query("""
        SELECT i
        FROM BranchInventory i
        JOIN FETCH i.material m
        WHERE i.branch.branchId = :branchId
          AND m.isActive = TRUE
        ORDER BY m.materialName ASC
        """)
    List<BranchInventory> findAllActiveByBranch(@Param("branchId") Long branchId);

    /**
     * Đếm tổng số mặt hàng vật tư đã phát sinh trong kho của chi nhánh (phục vụ KPI Card 1).
     */
    long countByBranch_BranchId(Long branchId);

    /**
     * Đếm số mặt hàng chạm hoặc dưới ngưỡng tồn tối thiểu tại chi nhánh (phục vụ KPI Card 2).
     */
    @Query("""
        SELECT COUNT(i)
        FROM BranchInventory i
        JOIN i.material m
        WHERE i.branch.branchId = :branchId
          AND i.currentStock <= m.minStockThreshold
        """)
    long countLowStockByBranch(@Param("branchId") Long branchId);

    /**
     * Đếm số mặt hàng đã hết sạch tồn kho (<= 0) tại chi nhánh (phục vụ phụ đề KPI Card 2).
     */
    @Query("""
        SELECT COUNT(i)
        FROM BranchInventory i
        WHERE i.branch.branchId = :branchId
          AND i.currentStock <= 0
        """)
    long countOutOfStockByBranch(@Param("branchId") Long branchId);
}
