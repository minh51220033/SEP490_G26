package com.cvdcms.repository;

import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VisitRepository extends JpaRepository<Visit, Long> {

    Optional<Visit> findByVisitCode(String visitCode);

    @Query("""
        SELECT v FROM Visit v
        LEFT JOIN FETCH v.patientProfile pp
        LEFT JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE v.visitId = :visitId
    """)
    Optional<Visit> findWithDetailsById(@Param("visitId") Long visitId);

    @Query("""
        SELECT v FROM Visit v
        LEFT JOIN FETCH v.patientProfile pp
        LEFT JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE v.status IN (:statuses)
        ORDER BY v.checkInAt DESC
    """)
    List<Visit> findRecentVisitsByStatuses(@Param("statuses") List<VisitStatus> statuses);

    List<Visit> findByPatientProfile_ProfileIdOrderByCheckInAtDesc(Long profileId);

    Optional<Visit> findFirstByPatientProfile_ProfileIdOrderByCheckInAtDesc(Long profileId);

    Optional<Visit> findFirstByPatientProfile_ProfileIdAndStatusIn(Long profileId, List<VisitStatus> statuses);

    @Query("""
        SELECT v FROM Visit v
        LEFT JOIN FETCH v.patientProfile pp
        LEFT JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE (:start IS NULL OR v.checkInAt >= :start)
          AND (:end IS NULL OR v.checkInAt <= :end)
          AND (:status IS NULL OR v.status = :status)
          AND (
              :search IS NULL OR :search = ''
              OR LOWER(pp.fullName) LIKE LOWER(CONCAT('%', :search, '%'))
              OR LOWER(pp.patientCode) LIKE LOWER(CONCAT('%', :search, '%'))
              OR pp.phoneNumber LIKE CONCAT('%', :search, '%')
              OR LOWER(v.visitCode) LIKE LOWER(CONCAT('%', :search, '%'))
          )
        ORDER BY v.checkInAt DESC
    """)
    List<Visit> findFilteredVisits(
            @Param("start") java.time.LocalDateTime start,
            @Param("end") java.time.LocalDateTime end,
            @Param("status") VisitStatus status,
            @Param("search") String search
    );

    @Query("""
        SELECT v FROM Visit v
        LEFT JOIN FETCH v.patientProfile pp
        LEFT JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE v.status = :status
          AND (:branchId IS NULL OR b.branchId = :branchId)
        ORDER BY v.checkInAt DESC
    """)
    List<Visit> findVisitsByStatusAndBranch(
            @Param("status") VisitStatus status,
            @Param("branchId") Long branchId
    );
}
