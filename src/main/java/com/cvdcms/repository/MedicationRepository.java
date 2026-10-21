package com.cvdcms.repository;

import com.cvdcms.entity.Medication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MedicationRepository extends JpaRepository<Medication, Long> {

    List<Medication> findByIsActiveTrueOrderByDrugNameAsc();

    List<Medication> findByDrugNameContainingIgnoreCaseAndIsActiveTrue(String drugName);
}
