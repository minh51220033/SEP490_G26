package com.cvdcms.repository;

import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface VisitRepository extends JpaRepository<Visit, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Visit v WHERE v.visitId = :visitId")
    Optional<Visit> lockById(@Param("visitId") Long visitId);

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

    @Query("""
        SELECT v FROM Visit v
        LEFT JOIN FETCH v.patientProfile pp
        LEFT JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE v.status IN (:statuses)
          AND (:branchId IS NULL OR b.branchId = :branchId)
        ORDER BY v.checkInAt DESC
    """)
    List<Visit> findRecentVisitsByStatusesAndBranch(
            @Param("statuses") List<VisitStatus> statuses,
            @Param("branchId") Long branchId
    );

    List<Visit> findByPatientProfile_ProfileIdOrderByCheckInAtDesc(Long profileId);

    List<Visit> findByPatientProfile_ProfileIdAndStatusOrderByCheckInAtDesc(Long profileId, VisitStatus status);

    Optional<Visit> findFirstByPatientProfile_ProfileIdOrderByCheckInAtDesc(Long profileId);

    Optional<Visit> findFirstByPatientProfile_ProfileIdAndStatusIn(Long profileId, List<VisitStatus> statuses);

    @Query("""
        SELECT v FROM Visit v
        LEFT JOIN FETCH v.patientProfile pp
        LEFT JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE (:start IS NULL OR v.checkInAt >= :start)
          AND (:end IS NULL OR v.checkInAt <= :end)
          AND ((:status IS NOT NULL AND v.status = :status) OR (:status IS NULL AND v.status != com.cvdcms.entity.enums.VisitStatus.CANCELLED))
          AND (:branchId IS NULL OR b.branchId = :branchId)
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
            @Param("branchId") Long branchId,
            @Param("search") String search
    );

    default List<Visit> findFilteredVisits(
            java.time.LocalDateTime start,
            java.time.LocalDateTime end,
            VisitStatus status,
            String search
    ) {
        return findFilteredVisits(start, end, status, null, search);
    }

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

    @Query("""
        SELECT COUNT(v) FROM Visit v
        WHERE (:branchIds IS NULL OR v.branch.branchId IN :branchIds)
          AND v.status = com.cvdcms.entity.enums.VisitStatus.WAITING
          AND v.checkInAt >= :startOfDay AND v.checkInAt <= :endOfDay
    """)
    long countWaitingVisitsByBranchesAndDate(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startOfDay") java.time.LocalDateTime startOfDay,
            @Param("endOfDay") java.time.LocalDateTime endOfDay
    );

    @Query("""
        SELECT v.status, COUNT(v)
        FROM Visit v
        WHERE (:branchIds IS NULL OR v.branch.branchId IN :branchIds)
          AND (:startDate IS NULL OR v.checkInAt >= :startDate)
          AND (:endDate IS NULL OR v.checkInAt <= :endDate)
        GROUP BY v.status
    """)
    List<Object[]> countVisitsByStatusAndBranches(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startDate") java.time.LocalDateTime startDate,
            @Param("endDate") java.time.LocalDateTime endDate
    );

    @Query("""
        SELECT v.visitType, COUNT(v)
        FROM Visit v
        WHERE (:branchIds IS NULL OR v.branch.branchId IN :branchIds)
          AND (:startDate IS NULL OR v.checkInAt >= :startDate)
          AND (:endDate IS NULL OR v.checkInAt <= :endDate)
        GROUP BY v.visitType
    """)
    List<Object[]> countVisitsByTypeAndBranches(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startDate") java.time.LocalDateTime startDate,
            @Param("endDate") java.time.LocalDateTime endDate
    );

    @Query("""
        SELECT pp.customerSource, COUNT(DISTINCT pp.profileId)
        FROM Visit v
        JOIN v.patientProfile pp
        WHERE (:branchIds IS NULL OR v.branch.branchId IN :branchIds)
          AND (:startDate IS NULL OR v.checkInAt >= :startDate)
          AND (:endDate IS NULL OR v.checkInAt <= :endDate)
        GROUP BY pp.customerSource
    """)
    List<Object[]> countPatientSourcesByVisitsAndBranches(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startDate") java.time.LocalDateTime startDate,
            @Param("endDate") java.time.LocalDateTime endDate
    );

    @Query("""
        SELECT v.checkInAt
        FROM Visit v
        WHERE (:branchIds IS NULL OR v.branch.branchId IN :branchIds)
          AND (:startDate IS NULL OR v.checkInAt >= :startDate)
          AND (:endDate IS NULL OR v.checkInAt <= :endDate)
        ORDER BY v.checkInAt ASC
    """)
    List<java.time.LocalDateTime> findVisitCheckInDates(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startDate") java.time.LocalDateTime startDate,
            @Param("endDate") java.time.LocalDateTime endDate
    );

    @Query("""
        SELECT v FROM Visit v
        JOIN FETCH v.patientProfile pp
        JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE (:branchIds IS NULL OR b.branchId IN :branchIds)
          AND (:startDate IS NULL OR v.checkInAt >= :startDate)
          AND (:endDate IS NULL OR v.checkInAt <= :endDate)
        ORDER BY v.checkInAt DESC
    """)
    List<Visit> findAllVisitsForReport(
            @Param("branchIds") Collection<Long> branchIds,
            @Param("startDate") java.time.LocalDateTime startDate,
            @Param("endDate") java.time.LocalDateTime endDate
    );

    @Query("""
        SELECT DISTINCT v.patientProfile.profileId FROM Visit v
        WHERE v.status = :status
          AND (:branchIds IS NULL OR v.branch.branchId IN :branchIds)
    """)
    List<Long> findPatientProfileIdsByStatusAndBranches(
            @Param("status") VisitStatus status,
            @Param("branchIds") Collection<Long> branchIds
    );

    @Query("""
        SELECT v FROM Visit v
        LEFT JOIN FETCH v.patientProfile pp
        LEFT JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE v.patientProfile.profileId = :profileId
          AND v.status = :status
          AND (:branchIds IS NULL OR b.branchId IN :branchIds)
        ORDER BY v.checkInAt DESC
    """)
    List<Visit> findWaitingVisitsByProfileIdAndBranches(
            @Param("profileId") Long profileId,
            @Param("status") VisitStatus status,
            @Param("branchIds") Collection<Long> branchIds
    );

    List<Visit> findByAppointmentIdIn(Collection<Long> appointmentIds);

    Optional<Visit> findByAppointmentId(Long appointmentId);

    /**
     * Hàng đợi 4 cấp ưu tiên của từng bác sĩ (chỉ lấy trạng thái WAITING - bệnh nhân chờ khám mới,
     * tách biệt rõ với WAITING_RESULT quay lại đọc kết quả cận lâm sàng):
     * 1. Cấp cứu (is_emergency = true hoặc visit_type = EMERGENCY)
     * 2. Lịch hẹn check-in đúng giờ trong Grace Period 15 phút (is_booked_and_on_time = true)
     * 3. Walk-in (appointment_id IS NULL và visit_type != APPOINTMENT) -> check_in_at ASC
     * 4. Lịch hẹn check-in trễ hơn 15 phút -> check_in_at ASC
     */
    @Query("""
        SELECT v FROM Visit v
        JOIN FETCH v.patientProfile pp
        JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE d.staffId = :doctorId
          AND v.status = com.cvdcms.entity.enums.VisitStatus.WAITING
        ORDER BY
            CASE
                WHEN v.isEmergency = true OR v.visitType = com.cvdcms.entity.enums.VisitType.EMERGENCY THEN 1
                WHEN v.isBookedAndOnTime = true AND (v.appointmentId IS NOT NULL OR v.visitType = com.cvdcms.entity.enums.VisitType.APPOINTMENT) THEN 2
                WHEN v.appointmentId IS NULL AND v.visitType <> com.cvdcms.entity.enums.VisitType.APPOINTMENT THEN 3
                ELSE 4
            END ASC,
            v.checkInAt ASC,
            v.visitId ASC
    """)
    List<Visit> findWaitingQueueByDoctorId(@Param("doctorId") Long doctorId);

    @Query("""
        SELECT v FROM Visit v
        JOIN FETCH v.patientProfile pp
        JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE d.staffId = :doctorId
          AND v.status = com.cvdcms.entity.enums.VisitStatus.WAITING_RESULT
        ORDER BY v.checkInAt ASC, v.visitId ASC
    """)
    List<Visit> findWaitingResultQueueByDoctorId(@Param("doctorId") Long doctorId);

    @Query("""
        SELECT v FROM Visit v
        LEFT JOIN FETCH v.patientProfile pp
        LEFT JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE b.branchId = :branchId
          AND d.staffId = :doctorId
          AND v.status IN :statuses
        ORDER BY v.actualStartTime DESC, v.visitId DESC
    """)
    List<Visit> findActiveExamsForDoctorAtBranch(
            @Param("branchId") Long branchId,
            @Param("doctorId") Long doctorId,
            @Param("statuses") Collection<VisitStatus> statuses
    );

    @Query("""
        SELECT v FROM Visit v
        LEFT JOIN FETCH v.patientProfile pp
        LEFT JOIN FETCH v.branch b
        LEFT JOIN FETCH v.primaryDoctor d
        WHERE b.branchId = :branchId
          AND d.staffId = :doctorId
          AND v.status IN :statuses
          AND v.checkInAt >= :startOfDay
          AND v.checkInAt < :endOfDay
        ORDER BY v.actualStartTime DESC, v.visitId DESC
    """)
    List<Visit> findActiveExamsForDoctorAtBranchOnDay(
            @Param("branchId") Long branchId,
            @Param("doctorId") Long doctorId,
            @Param("statuses") Collection<VisitStatus> statuses,
            @Param("startOfDay") java.time.LocalDateTime startOfDay,
            @Param("endOfDay") java.time.LocalDateTime endOfDay
    );

    @Query("""
        SELECT COUNT(v) FROM Visit v
        WHERE v.chairId = :chairId
          AND v.visitId <> :excludeVisitId
          AND v.status IN :statuses
    """)
    long countOccupiedChair(@Param("chairId") Long chairId,
                            @Param("excludeVisitId") Long excludeVisitId,
                            @Param("statuses") java.util.Collection<VisitStatus> statuses);

    @Query("""
        SELECT COUNT(v) FROM Visit v
        WHERE v.chairId = :chairId
          AND v.visitId <> :excludeVisitId
          AND v.status IN :statuses
          AND v.checkInAt >= :startOfDay
          AND v.checkInAt < :endOfDay
    """)
    long countOccupiedChairOnDay(@Param("chairId") Long chairId,
                                 @Param("excludeVisitId") Long excludeVisitId,
                                 @Param("statuses") java.util.Collection<VisitStatus> statuses,
                                 @Param("startOfDay") java.time.LocalDateTime startOfDay,
                                 @Param("endOfDay") java.time.LocalDateTime endOfDay);

    @Query("""
        SELECT COUNT(v) FROM Visit v
        WHERE v.chairId = :chairId
          AND v.visitId <> :excludeVisitId
          AND v.status IN :statuses
          AND v.checkInAt < :cutoff
    """)
    long countOccupiedChairBefore(@Param("chairId") Long chairId,
                                  @Param("excludeVisitId") Long excludeVisitId,
                                  @Param("statuses") java.util.Collection<VisitStatus> statuses,
                                  @Param("cutoff") java.time.LocalDateTime cutoff);

    @Query("""
        SELECT COUNT(t) FROM QueueTicket t
        WHERE t.branch.branchId = :branchId
          AND t.queueDate = :queueDate
          AND t.queueType = com.cvdcms.entity.enums.QueueType.EXAM
          AND t.status IN :statuses
          AND t.visit.chairId = :chairId
          AND t.visit.visitId <> :excludeVisitId
    """)
    long countHoldingChairByTicketsOnDay(@Param("branchId") Long branchId,
                                         @Param("queueDate") java.time.LocalDate queueDate,
                                         @Param("statuses") java.util.Collection<com.cvdcms.entity.enums.QueueStatus> statuses,
                                         @Param("chairId") Long chairId,
                                         @Param("excludeVisitId") Long excludeVisitId);
}
