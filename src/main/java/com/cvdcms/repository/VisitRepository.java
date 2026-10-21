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
}
