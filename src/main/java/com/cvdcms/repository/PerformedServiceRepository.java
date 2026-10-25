package com.cvdcms.repository;

import com.cvdcms.entity.PerformedService;
import com.cvdcms.entity.enums.PerformedServiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PerformedServiceRepository extends JpaRepository<PerformedService, Long> {

    @Query("SELECT ps FROM PerformedService ps " +
           "JOIN FETCH ps.service s " +
           "LEFT JOIN FETCH s.category " +
           "JOIN FETCH ps.doctor d " +
           "LEFT JOIN FETCH ps.assistant a " +
           "LEFT JOIN FETCH ps.planItem pi " +
           "LEFT JOIN FETCH pi.plan p " +
           "LEFT JOIN FETCH pi.stage st " +
           "WHERE ps.visit.visitId = :visitId " +
           "ORDER BY ps.completedAt DESC, ps.performedId DESC")
    List<PerformedService> findByVisitIdWithDetails(@Param("visitId") Long visitId);

    List<PerformedService> findByVisit_VisitIdOrderByCompletedAtDesc(Long visitId);

    List<PerformedService> findByPatientProfile_ProfileIdOrderByCompletedAtDesc(Long profileId);

    List<PerformedService> findByVisit_VisitIdAndStatus(Long visitId, PerformedServiceStatus status);
}
