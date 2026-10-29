package com.cvdcms.repository;

import com.cvdcms.entity.PrescriptionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItem, Long> {

    long countByMedication_MedicationId(Long medicationId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT pi FROM PrescriptionItem pi WHERE pi.itemId = :itemId")
    java.util.Optional<PrescriptionItem> findByIdForUpdate(@org.springframework.data.repository.query.Param("itemId") Long itemId);
}
