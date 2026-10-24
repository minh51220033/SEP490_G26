package com.cvdcms.repository;

import com.cvdcms.entity.PrescriptionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItem, Long> {

    long countByMedication_MedicationId(Long medicationId);
}
