package com.cvdcms.repository;

import com.cvdcms.entity.Material;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    boolean existsByMaterialCodeIgnoreCase(String materialCode);

    boolean existsByMaterialCodeIgnoreCaseAndMaterialIdNot(String materialCode, Long materialId);

    Optional<Material> findByMaterialCodeIgnoreCase(String materialCode);

    List<Material> findByIsActiveTrueOrderByMaterialNameAsc();

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

    @Query("""
        SELECT DISTINCT m.category
        FROM Material m
        WHERE m.category IS NOT NULL
          AND TRIM(m.category) <> ''
        ORDER BY m.category ASC
        """)
    List<String> findAllDistinctCategories();

    @Query("""
        SELECT m.category, COUNT(m), SUM(CASE WHEN m.isActive = TRUE THEN 1 ELSE 0 END)
        FROM Material m
        WHERE m.category IS NOT NULL AND TRIM(m.category) <> ''
        GROUP BY m.category
        ORDER BY m.category ASC
        """)
    List<Object[]> countMaterialsByCategory();

    long countByCategoryIgnoreCase(String category);
}
