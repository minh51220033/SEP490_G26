package com.cvdcms.repository;

import com.cvdcms.entity.Icd10Disease;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface Icd10DiseaseRepository extends JpaRepository<Icd10Disease, Long> {

    Optional<Icd10Disease> findByIcd10Code(String icd10Code);

    boolean existsByIcd10Code(String icd10Code);

    boolean existsByIcd10CodeAndIcd10IdNot(String icd10Code, Long icd10Id);

    List<Icd10Disease> findAllByOrderByIcd10CodeAsc();

    List<Icd10Disease> findByIsDentalCommonTrueAndIsActiveTrueOrderByIcd10CodeAsc();

    @Query("""
        SELECT d FROM Icd10Disease d
        WHERE d.isActive = true
          AND (
               LOWER(d.icd10Code) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(d.diseaseNameVi) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(d.diseaseNameEn) LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
        ORDER BY d.isDentalCommon DESC, d.icd10Code ASC
    """)
    List<Icd10Disease> searchDiseases(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
        SELECT d FROM Icd10Disease d
        WHERE d.isActive = true
          AND (
               LOWER(d.icd10Code) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(d.diseaseNameVi) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(d.diseaseNameEn) LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
        ORDER BY d.isDentalCommon DESC, d.icd10Code ASC
    """)
    Page<Icd10Disease> searchDiseasesPage(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
        SELECT d FROM Icd10Disease d
        WHERE LOWER(d.icd10Code) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(d.diseaseNameVi) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(d.diseaseNameEn) LIKE LOWER(CONCAT('%', :keyword, '%'))
        ORDER BY d.icd10Code ASC
    """)
    List<Icd10Disease> adminSearch(@Param("keyword") String keyword);
}
