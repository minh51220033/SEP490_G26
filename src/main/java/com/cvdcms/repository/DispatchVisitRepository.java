package com.cvdcms.repository;

import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface DispatchVisitRepository extends JpaRepository<Visit, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Visit v where v.visitId = :id")
    Optional<Visit> lock(@Param("id") Long id);

    @Query("select v from Visit v join fetch v.patientProfile where v.branch.branchId = :branch and v.checkInAt >= :start and v.checkInAt < :end order by v.checkInAt, v.visitId")
    List<Visit> today(@Param("branch") Long branch, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("select v from Visit v join fetch v.patientProfile where v.branch.branchId = :branch order by v.checkInAt desc, v.visitId desc")
    List<Visit> allByBranch(@Param("branch") Long branch);

    @Query("select count(v) from Visit v where v.primaryDoctor.staffId = :doctor and v.branch.branchId = :branch and v.checkInAt >= :start and v.checkInAt < :end and v.status in (com.cvdcms.entity.enums.VisitStatus.WAITING, com.cvdcms.entity.enums.VisitStatus.WAITING_RESULT) and v.visitId <> :id")
    long doctorLoad(@Param("doctor") Long doctor, @Param("branch") Long branch,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("id") Long id);

    @Query("select count(v) from Visit v where v.chairId = :chair and v.visitId <> :id and v.status in :statuses")
    long occupiedChair(@Param("chair") Long chair, @Param("id") Long id, @Param("statuses") Set<VisitStatus> statuses);
}