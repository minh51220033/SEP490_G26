package com.cvdcms.patientportal;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.PreferredSession;
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

public interface PortalAppointmentRepository extends JpaRepository<Appointment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT a FROM Appointment a WHERE a.appointmentId = :appointmentId")
    Optional<Appointment> findByIdForUpdate(@Param("appointmentId") Long appointmentId);

    @Query("""
        SELECT new com.cvdcms.repository.AppointmentLockSnapshot(
            a.appointmentId, p.profileId, b.branchId, d.staffId,
            a.appointmentDate, a.expectedTime, a.preferredSession, a.status)
        FROM Appointment a
        LEFT JOIN a.patientProfile p
        LEFT JOIN a.branch b
        LEFT JOIN a.doctor d
        WHERE a.appointmentId = :appointmentId
        """)
    Optional<AppointmentLockSnapshot> findLockSnapshotById(@Param("appointmentId") Long appointmentId);

    List<Appointment> findByPatientProfile_ProfileIdOrderByAppointmentDateDescAppointmentIdDesc(Long profileId);

    @Query("SELECT a FROM Appointment a WHERE a.branch.branchId = :branchId AND a.appointmentDate = :date AND a.preferredSession = :session AND a.status IN :statuses")
    List<Appointment> findActiveForSession(@Param("branchId") Long branchId, @Param("date") LocalDate date,
            @Param("session") PreferredSession session, @Param("statuses") List<AppointmentStatus> statuses);

    @Query("SELECT a FROM Appointment a WHERE a.patientProfile.profileId = :profileId AND a.branch.branchId = :branchId AND a.appointmentDate = :date AND a.status IN :statuses")
    List<Appointment> findActiveForPatientDay(@Param("profileId") Long profileId, @Param("branchId") Long branchId,
            @Param("date") LocalDate date, @Param("statuses") List<AppointmentStatus> statuses);
}
