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

/**
 * Repository truy vấn quản lý Lô vật tư &amp; Hạn sử dụng ({@link MaterialBatch}).
 * <p>Hỗ trợ tìm kiếm phân trang theo khoảng HSD, khóa bi quan {@code PESSIMISTIC_WRITE} cho xuất kho FEFO/hủy hết hạn/nhập kho,
 * và các truy vấn đếm cảnh báo lô sắp hết hạn/hết hạn phục vụ KPI Dashboard.</p>
 */
public interface MaterialBatchRepository
        extends JpaRepository<MaterialBatch, Long> {

    /**
     * Tìm kiếm phân trang danh sách lô vật tư tại chi nhánh theo từ khóa (mã/tên vật tư, số lô) và khoảng hạn sử dụng.
     * <p>Được gọi từ: {@link com.cvdcms.service.impl.MaterialBatchServiceImpl#searchBatches}.</p>
     */
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

    /** Khóa bi quan một lô vật tư theo {@code batchId} (kèm JOIN FETCH vật tư và NCC) khi xuất đích danh theo lô. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT b
        FROM MaterialBatch b
        JOIN FETCH b.material m
        LEFT JOIN FETCH b.supplier s
        WHERE b.batchId = :batchId
        """)
    Optional<MaterialBatch> findByIdForUpdate(@Param("batchId") Long batchId);

    /**
     * Khóa bi quan và lấy danh sách lô còn tồn, còn hạn ({@code expiryDate >= today}) và trạng thái {@code ACTIVE}
     * sắp xếp theo nguyên tắc FEFO ({@code expiryDate ASC, batchId ASC}) để tự động phân bổ xuất kho.
     */
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
          AND b.status = com.cvdcms.entity.enums.BatchStatus.ACTIVE
        ORDER BY b.expiryDate ASC, b.batchId ASC
        """)
    List<MaterialBatch> findAvailableBatchesFefoForUpdate(
            @Param("branchId") Long branchId,
            @Param("materialId") Long materialId,
            @Param("today") LocalDate today
    );

    /**
     * Lấy nhanh danh sách lô còn tồn và còn hạn theo danh sách {@code materialIds} tại chi nhánh (sắp theo FEFO).
     * Dùng cho kiểm tra khả dụng hàng loạt (ví dụ cấp phát thuốc/vật tư).
     */
    @Query("""
        SELECT b
        FROM MaterialBatch b
        JOIN FETCH b.material m
        WHERE b.branch.branchId = :branchId
          AND b.material.materialId IN :materialIds
          AND b.quantityRemaining > 0
          AND b.expiryDate >= :today
          AND b.status = com.cvdcms.entity.enums.BatchStatus.ACTIVE
        ORDER BY b.expiryDate ASC, b.batchId ASC
        """)
    List<MaterialBatch> findActiveAvailableBatchesByBranchAndMaterialIds(
            @Param("branchId") Long branchId,
            @Param("materialIds") java.util.Collection<Long> materialIds,
            @Param("today") LocalDate today
    );

    /**
     * Tính tổng số lượng tồn đã hết hạn theo từng {@code materialId} trong danh sách chỉ định tại chi nhánh.
     * Trả về danh sách cặp {@code [materialId, sumExpiredQty]}.
     */
    @Query("""
        SELECT b.material.materialId, COALESCE(SUM(b.quantityRemaining), 0)
        FROM MaterialBatch b
        WHERE b.branch.branchId = :branchId
          AND b.material.materialId IN :materialIds
          AND b.quantityRemaining > 0
          AND (b.expiryDate < :today OR b.status = com.cvdcms.entity.enums.BatchStatus.EXPIRED)
        GROUP BY b.material.materialId
        """)
    List<Object[]> sumExpiredStockByBranchAndMaterialIds(
            @Param("branchId") Long branchId,
            @Param("materialIds") java.util.Collection<Long> materialIds,
            @Param("today") LocalDate today
    );

    /**
     * Khóa bi quan và lấy các lô còn tồn nhưng đã quá hạn ({@code expiryDate < today}) sắp theo hạn cũ nhất trước,
     * phục vụ tự động trừ lô khi lập phiếu xuất hủy ({@code DISCARD_EXPIRED}).
     */
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

    /**
     * Lấy danh sách lô còn tồn kho ({@code quantityRemaining > 0}) tại chi nhánh (tùy chọn lọc theo {@code materialId}),
     * sắp xếp theo FEFO. Dùng cho Material Picker và dropdown chọn lô xuất kho.
     */
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

    /** Tính tổng số lượng còn lại trên tất cả các lô của một vật tư tại một chi nhánh. */
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
