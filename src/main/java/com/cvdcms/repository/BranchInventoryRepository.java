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

/**
 * Repository truy vấn tồn kho tổng hợp theo cơ sở ({@link BranchInventory}).
 * <p>Cung cấp truy vấn tìm kiếm phân trang, khóa bi quan {@code PESSIMISTIC_WRITE} chống xung đột khi nhập/xuất/điều chuyển
 * và các truy vấn đếm KPI đầu trang Quản lý kho.</p>
 */
public interface BranchInventoryRepository
        extends JpaRepository<BranchInventory, Long> {

    /**
     * Tìm kiếm phân trang tồn kho của một cơ sở theo từ khóa (mã, tên, nhóm vật tư)
     * và tùy chọn lọc trạng thái chạm ngưỡng tồn tối thiểu ({@code currentStock <= minStockThreshold}).
     * <p>Được gọi từ: {@link com.cvdcms.service.impl.BranchInventoryServiceImpl#search}.</p>
     */
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

    /** Tìm bản ghi tồn kho theo cặp khóa duy nhất ({@code branchId}, {@code materialId}). */
    Optional<BranchInventory> findByBranch_BranchIdAndMaterial_MaterialId(
            Long branchId,
            Long materialId
    );

    /**
     * Khóa bi quan ({@code PESSIMISTIC_WRITE}) bản ghi tồn kho của một vật tư tại cơ sở
     * để đảm bảo cộng/trừ tồn kho an toàn khi có nhiều phiếu nhập/xuất/điều chuyển chạy đồng thời.
     */
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

    /**
     * Lấy toàn bộ bản ghi tồn kho của các vật tư đang hoạt động tại một cơ sở (JOIN FETCH {@code material}),
     * sắp xếp theo tên vật tư A–Z. Phục vụ API Material Picker.
     */
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
