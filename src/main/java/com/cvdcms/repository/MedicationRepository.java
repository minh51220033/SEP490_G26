package com.cvdcms.repository;

import com.cvdcms.entity.Medication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MedicationRepository extends JpaRepository<Medication, Long> {

    List<Medication> findByIsActiveTrueOrderByDrugNameAsc();

    List<Medication> findByDrugNameContainingIgnoreCaseAndIsActiveTrue(String drugName);

    List<Medication> findAllByOrderByDrugNameAsc();

    boolean existsByDrugCodeIgnoreCase(String drugCode);

    Optional<Medication> findByDrugCodeIgnoreCase(String drugCode);

    @Query("SELECT m FROM Medication m WHERE " +
           "LOWER(m.drugCode) LIKE LOWER(CONCAT('%', :kw, '%')) OR " +
           "LOWER(m.drugName) LIKE LOWER(CONCAT('%', :kw, '%')) OR " +
           "LOWER(m.activeIngredient) LIKE LOWER(CONCAT('%', :kw, '%')) " +
           "ORDER BY m.drugName ASC")
    List<Medication> searchByKeyword(@Param("kw") String kw);
}

