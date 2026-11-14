package com.cvdcms.repository;

import com.cvdcms.entity.TreatmentPlanInstallment;
import com.cvdcms.entity.enums.TreatmentPlanInstallmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface TreatmentPlanInstallmentRepository extends JpaRepository<TreatmentPlanInstallment, Long> {

    List<TreatmentPlanInstallment> findByPlan_PlanIdOrderByInstallmentOrderAsc(Long planId);

    List<TreatmentPlanInstallment> findByPlan_PatientProfile_ProfileIdOrderByExpectedDateAsc(Long profileId);

    @Query("SELECT i FROM TreatmentPlanInstallment i " +
            "JOIN FETCH i.plan p " +
            "JOIN FETCH p.patientProfile pat " +
            "WHERE (:branchId IS NULL OR p.branch.branchId = :branchId) " +
            "AND (:startDate IS NULL OR i.expectedDate >= :startDate) " +
            "AND (:endDate IS NULL OR i.expectedDate <= :endDate) " +
            "AND (:status IS NULL OR i.status = :status) " +
            "AND (:keyword IS NULL OR LOWER(pat.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "     OR LOWER(pat.phoneNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "     OR LOWER(p.planCode) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY i.expectedDate ASC, i.installmentOrder ASC")
    Page<TreatmentPlanInstallment> searchUpcomingInstallments(
            @Param("branchId") Long branchId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("status") TreatmentPlanInstallmentStatus status,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("SELECT i FROM TreatmentPlanInstallment i " +
            "JOIN FETCH i.plan p " +
            "JOIN FETCH p.patientProfile pat " +
            "WHERE p.patientProfile.profileId = :profileId " +
            "ORDER BY p.planId DESC, i.installmentOrder ASC")
    List<TreatmentPlanInstallment> findByProfileIdWithDetails(@Param("profileId") Long profileId);

    long countByExpectedDateBetweenAndStatusIn(LocalDate startDate, LocalDate endDate, Collection<TreatmentPlanInstallmentStatus> statuses);
}

