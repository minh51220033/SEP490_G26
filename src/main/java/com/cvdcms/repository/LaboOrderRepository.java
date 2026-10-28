package com.cvdcms.repository;

import com.cvdcms.entity.LaboOrder;
import com.cvdcms.entity.enums.LaboOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LaboOrderRepository extends JpaRepository<LaboOrder, Long> {

    Optional<LaboOrder> findByLaboCodeIgnoreCase(String laboCode);

    List<LaboOrder> findByVisit_VisitIdOrderBySentDateDesc(Long visitId);

    List<LaboOrder> findByPatientProfile_ProfileIdOrderBySentDateDesc(Long profileId);

    List<LaboOrder> findByStatusOrderBySentDateDesc(LaboOrderStatus status);

    List<LaboOrder> findBySupplier_SupplierIdOrderBySentDateDesc(Long supplierId);

    @Query("""
        SELECT lo FROM LaboOrder lo
        LEFT JOIN lo.patientProfile p
        LEFT JOIN lo.supplier s
        WHERE (:visitId IS NULL OR lo.visit.visitId = :visitId)
          AND (:profileId IS NULL OR lo.patientProfile.profileId = :profileId)
          AND (:status IS NULL OR lo.status = :status)
          AND (:supplierId IS NULL OR lo.supplier.supplierId = :supplierId)
          AND (:keyword IS NULL OR :keyword = ''
               OR LOWER(lo.laboCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(lo.materialType) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(lo.toothPositions) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(p.phoneNumber) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY lo.sentDate DESC, lo.laboId DESC
    """)
    List<LaboOrder> searchLaboOrders(
            @Param("keyword") String keyword,
            @Param("status") LaboOrderStatus status,
            @Param("supplierId") Long supplierId,
            @Param("visitId") Long visitId,
            @Param("profileId") Long profileId
    );

    long countByStatus(LaboOrderStatus status);
}
