package com.cvdcms.repository;

import com.cvdcms.entity.Material;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository truy vấn Danh mục vật tư nha khoa dùng chung ({@link Material}).
 * <p>Cung cấp truy vấn tìm kiếm phân trang, thống kê nhóm vật tư, kiểm tra trùng mã và các truy vấn đếm liên kết
 * phục vụ đồng bộ giữa Danh mục thuốc và Kho vật tư.</p>
 */
public interface MaterialRepository extends JpaRepository<Material, Long> {

    /** Kiểm tra mã vật tư đã tồn tại hay chưa (không phân biệt hoa thường). */
    boolean existsByMaterialCodeIgnoreCase(String materialCode);

    /** Kiểm tra mã vật tư có trùng với bản ghi khác khi cập nhật hay không. */
    boolean existsByMaterialCodeIgnoreCaseAndMaterialIdNot(String materialCode, Long materialId);

    /** Tìm vật tư theo mã vật tư (không phân biệt hoa thường). */
    Optional<Material> findByMaterialCodeIgnoreCase(String materialCode);

    /** Lấy toàn bộ vật tư đang hoạt động ({@code isActive = true}) sắp xếp theo tên tăng dần. */
    List<Material> findByIsActiveTrueOrderByMaterialNameAsc();

    /**
     * Tìm kiếm phân trang danh mục vật tư theo từ khóa (mã, tên, nhóm, đơn vị), nhóm vật tư và trạng thái hoạt động.
     * <p>Được gọi từ: {@link com.cvdcms.service.impl.MaterialServiceImpl#search}.</p>
     */
    @Query("""
        SELECT m
        FROM Material m
        WHERE (:keyword IS NULL
               OR LOWER(m.materialCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(m.materialName) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(m.category) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(m.unit) LIKE LOWER(CONCAT('%', :keyword, '%')))
          AND (:category IS NULL OR LOWER(m.category) = LOWER(:category))
          AND (:active IS NULL OR m.isActive = :active)
        """)
    Page<Material> searchMaterials(
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("active") Boolean active,
            Pageable pageable
    );

    /** Lấy danh sách tên nhóm vật tư không trùng lặp, không rỗng, sắp xếp theo bảng chữ cái. */
    @Query("""
        SELECT DISTINCT m.category
        FROM Material m
        WHERE m.category IS NOT NULL
          AND TRIM(m.category) <> ''
        ORDER BY m.category ASC
        """)
    List<String> findAllDistinctCategories();

    /**
     * Thống kê số lượng vật tư theo từng nhóm: trả về mảng {@code [category, totalCount, activeCount]}.
     * <p>Được gọi từ: {@link com.cvdcms.service.impl.MaterialServiceImpl#getCategories}.</p>
     */
    @Query("""
        SELECT m.category, COUNT(m), SUM(CASE WHEN m.isActive = TRUE THEN 1 ELSE 0 END)
        FROM Material m
        WHERE m.category IS NOT NULL AND TRIM(m.category) <> ''
        GROUP BY m.category
        ORDER BY m.category ASC
        """)
    List<Object[]> countMaterialsByCategory();

    /** Đếm số vật tư thuộc một nhóm cụ thể (không phân biệt hoa thường). */
    long countByCategoryIgnoreCase(String category);

    // ─── Đồng bộ danh mục thuốc ↔ kho vật tư ──────────────────────────────────

    /** Mặt hàng kho đang liên kết với thuốc (tối đa 1 bản ghi nhờ UNIQUE medication_id). */
    Optional<Material> findByMedication_MedicationId(Long medicationId);

    boolean existsByMedication_MedicationId(Long medicationId);

    /** Số dòng phiếu nhập đã dùng mặt hàng này (lịch sử nhập kho). */
    @Query("SELECT COUNT(i) FROM StockReceiptItem i WHERE i.material.materialId = :materialId")
    long countReceiptItems(@Param("materialId") Long materialId);

    /** Số dòng phiếu xuất đã dùng mặt hàng này. */
    @Query("SELECT COUNT(i) FROM StockIssueItem i WHERE i.material.materialId = :materialId")
    long countIssueItems(@Param("materialId") Long materialId);

    /** Số bản ghi tồn kho chi nhánh của mặt hàng này. */
    @Query("SELECT COUNT(b) FROM BranchInventory b WHERE b.material.materialId = :materialId")
    long countInventoryRows(@Param("materialId") Long materialId);

    /** Số lô hàng của mặt hàng này. */
    @Query("SELECT COUNT(b) FROM MaterialBatch b WHERE b.material.materialId = :materialId")
    long countBatches(@Param("materialId") Long materialId);

    /** Số bản ghi tiêu hao vật tư lâm sàng của mặt hàng này. */
    @Query("SELECT COUNT(c) FROM MaterialConsumption c WHERE c.material.materialId = :materialId")
    long countConsumptions(@Param("materialId") Long materialId);
}
