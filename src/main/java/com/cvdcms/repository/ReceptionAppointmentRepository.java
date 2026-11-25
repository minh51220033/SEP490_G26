package com.cvdcms.repository;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.repository.AppointmentLockSnapshot;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReceptionAppointmentRepository extends JpaRepository<Appointment, Long> {
    @Query("""
        SELECT a FROM Appointment a JOIN FETCH a.patientProfile p JOIN FETCH a.branch b
        LEFT JOIN FETCH a.doctor d LEFT JOIN FETCH a.service s
        WHERE a.appointmentDate BETWEEN :start AND :end
          AND (:branchId IS NULL OR b.branchId = :branchId)
          AND (:doctorId IS NULL OR d.staffId = :doctorId)
          AND (:status IS NULL OR a.status = :status)
        ORDER BY a.appointmentDate, a.preferredSession, a.appointmentId
        """)
    List<Appointment> findInRange(@Param("start") LocalDate start, @Param("end") LocalDate end,
            @Param("branchId") Long branchId, @Param("doctorId") Long doctorId,
            @Param("status") AppointmentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT a FROM Appointment a WHERE a.appointmentId = :id")
    Optional<Appointment> lockById(@Param("id") Long id);

    @Query("""
        SELECT new com.cvdcms.repository.AppointmentLockSnapshot(
            a.appointmentId, p.profileId, b.branchId, d.staffId,
            a.appointmentDate, a.expectedTime, a.preferredSession, a.status)
        FROM Appointment a
        LEFT JOIN a.patientProfile p
        LEFT JOIN a.branch b
        LEFT JOIN a.doctor d
        WHERE a.appointmentId = :id
        """)
    Optional<AppointmentLockSnapshot> findLockSnapshotById(@Param("id") Long id);

    @Query("SELECT a.appointmentId FROM Appointment a WHERE a.appointmentDate BETWEEN :start AND :end AND a.status = :status ORDER BY a.appointmentId")
    List<Long> findNoShowCandidates(@Param("start") LocalDate start, @Param("end") LocalDate end,
            @Param("status") AppointmentStatus status);

    @Query("SELECT a.appointmentId FROM Appointment a WHERE a.appointmentDate BETWEEN :start AND :end AND a.status IN :statuses ORDER BY a.appointmentId")
    List<Long> findNoShowCandidatesByStatuses(@Param("start") LocalDate start, @Param("end") LocalDate end,
            @Param("statuses") List<AppointmentStatus> statuses);
}
