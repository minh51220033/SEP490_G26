package com.cvdcms.emr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmrRecordRepository extends JpaRepository<EmrRecord, Long> {
    boolean existsByVisit_VisitId(Long visitId);

    Optional<EmrRecord> findByVisit_VisitId(Long visitId);

    @Query("""
        select r from EmrRecord r
        join fetch r.visit v
        join fetch r.patientProfile p
        join fetch r.branch b
        left join fetch v.primaryDoctor
        where p.profileId = :profileId
        order by r.updatedAt desc, r.recordId desc
        """)
    List<EmrRecord> findAllByPatientProfileId(@Param("profileId") Long profileId);

    @Query("""
        select distinct r from EmrRecord r
        join fetch r.visit v
        join fetch r.patientProfile p
        join fetch r.branch b
        left join fetch v.primaryDoctor
        where r.recordId = :recordId
        """)
    Optional<EmrRecord> findDetailsById(@Param("recordId") Long recordId);

    @Query("""
        select r from EmrRecord r
        join fetch r.visit v
        join fetch r.patientProfile p
        join fetch r.branch b
        left join fetch v.primaryDoctor
        order by r.updatedAt desc, r.recordId desc
        """)
    List<EmrRecord> findAllWithDetails();
}
