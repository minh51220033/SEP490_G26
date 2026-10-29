package com.cvdcms.repository;

import com.cvdcms.entity.NanopixXraySyncLog;
import com.cvdcms.entity.enums.NanopixSyncStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NanopixXraySyncLogRepository extends JpaRepository<NanopixXraySyncLog, Long> {

    Optional<NanopixXraySyncLog> findByExternalStudyUid(String externalStudyUid);

    boolean existsByExternalStudyUid(String externalStudyUid);

    List<NanopixXraySyncLog> findBySyncStatusOrderByCapturedAtDesc(NanopixSyncStatus syncStatus);

    List<NanopixXraySyncLog> findByMatchedProfile_ProfileIdOrderByCapturedAtDesc(Long profileId);

    List<NanopixXraySyncLog> findByMatchedVisit_VisitIdOrderByCapturedAtDesc(Long visitId);

    long countBySyncStatus(NanopixSyncStatus syncStatus);

    @Query("SELECT n FROM NanopixXraySyncLog n " +
           "LEFT JOIN FETCH n.matchedProfile p " +
           "LEFT JOIN FETCH n.matchedVisit v " +
           "LEFT JOIN FETCH n.matchedItem item " +
           "LEFT JOIN FETCH n.linkedBy staff " +
           "LEFT JOIN FETCH n.branch b " +
           "WHERE n.syncLogId = :id")
    Optional<NanopixXraySyncLog> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT n FROM NanopixXraySyncLog n " +
           "LEFT JOIN n.matchedProfile p " +
           "LEFT JOIN n.matchedVisit v " +
           "WHERE (:status IS NULL OR n.syncStatus = :status) " +
           "AND (:fromDate IS NULL OR n.capturedAt >= :fromDate) " +
           "AND (:toDate IS NULL OR n.capturedAt <= :toDate) " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(n.externalStudyUid) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(n.rawPatientName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     (p IS NOT NULL AND (LOWER(p.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(p.patientCode) LIKE LOWER(CONCAT('%', :keyword, '%')))) OR " +
           "     (v IS NOT NULL AND LOWER(v.visitCode) LIKE LOWER(CONCAT('%', :keyword, '%')))) " +
           "AND (:branchId IS NULL OR (n.branch IS NOT NULL AND n.branch.branchId = :branchId) OR (v IS NOT NULL AND v.branch.branchId = :branchId)) " +
           "ORDER BY n.capturedAt DESC")
    Page<NanopixXraySyncLog> findWithFilters(
            @Param("status") NanopixSyncStatus status,
            @Param("keyword") String keyword,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("branchId") Long branchId,
            Pageable pageable
    );

    @Query("SELECT n FROM NanopixXraySyncLog n " +
           "LEFT JOIN n.matchedVisit v " +
           "WHERE n.syncStatus = :status " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(n.externalStudyUid) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(n.rawPatientName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(n.externalPatientId) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:branchId IS NULL OR (n.branch IS NOT NULL AND n.branch.branchId = :branchId) OR (v IS NOT NULL AND v.branch.branchId = :branchId)) " +
           "ORDER BY n.capturedAt DESC")
    List<NanopixXraySyncLog> findUnmatchedWithFilter(
            @Param("status") NanopixSyncStatus status,
            @Param("keyword") String keyword,
            @Param("branchId") Long branchId
    );
}
