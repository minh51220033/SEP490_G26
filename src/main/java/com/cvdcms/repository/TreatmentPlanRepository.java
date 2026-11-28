package com.cvdcms.repository;

import com.cvdcms.entity.TreatmentPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TreatmentPlanRepository extends JpaRepository<TreatmentPlan, Long> {

    @Query("SELECT DISTINCT p FROM TreatmentPlan p " +
           "LEFT JOIN FETCH p.stages s " +
           "LEFT JOIN FETCH p.doctor d " +
           "LEFT JOIN FETCH p.visit v " +
           "WHERE p.patientProfile.profileId = :profileId " +
           "ORDER BY p.createdAt DESC")
    List<TreatmentPlan> findByProfileIdWithDetails(@Param("profileId") Long profileId);

    @Query("SELECT DISTINCT p FROM TreatmentPlan p " +
           "LEFT JOIN FETCH p.stages s " +
           "LEFT JOIN FETCH p.doctor d " +
           "LEFT JOIN FETCH p.visit v " +
           "WHERE p.planId = :planId")
    Optional<TreatmentPlan> findByIdWithDetails(@Param("planId") Long planId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM TreatmentPlan p WHERE p.planId = :planId")
    Optional<TreatmentPlan> findByIdForUpdate(@Param("planId") Long planId);
}
