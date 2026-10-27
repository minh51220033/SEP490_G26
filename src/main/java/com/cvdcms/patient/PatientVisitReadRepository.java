package com.cvdcms.patient;

import com.cvdcms.entity.Visit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientVisitReadRepository extends Repository<Visit, Long> {
    @Query("""
            SELECT v FROM Visit v JOIN FETCH v.branch
            LEFT JOIN FETCH v.primaryDoctor
            WHERE v.patientProfile.profileId = :profileId
            ORDER BY v.checkInAt DESC, v.visitId DESC
            """)
    List<Visit> forPatient(@Param("profileId") Long profileId);

    @Query("""
            SELECT v FROM Visit v JOIN FETCH v.branch
            LEFT JOIN FETCH v.primaryDoctor
            WHERE v.patientProfile.profileId = :profileId AND v.visitId = :visitId
            """)
    Optional<Visit> oneForPatient(@Param("profileId") Long profileId,
                                  @Param("visitId") Long visitId);
}
