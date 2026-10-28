package com.cvdcms.patientportal;

import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.enums.AppointmentStatus;
import com.cvdcms.entity.enums.PreferredSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PortalAppointmentRepository extends JpaRepository<Appointment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Appointment a WHERE a.appointmentId = :appointmentId")
    Optional<Appointment> findByIdForUpdate(@Param("appointmentId") Long appointmentId);

    List<Appointment> findByPatientProfile_ProfileIdOrderByAppointmentDateDescAppointmentIdDesc(Long profileId);

    @Query("SELECT a FROM Appointment a WHERE a.branch.branchId = :branchId AND a.appointmentDate = :date AND a.preferredSession = :session AND a.status IN :statuses")
    List<Appointment> findActiveForSession(@Param("branchId") Long branchId, @Param("date") LocalDate date,
            @Param("session") PreferredSession session, @Param("statuses") List<AppointmentStatus> statuses);

    @Query("SELECT a FROM Appointment a WHERE a.patientProfile.profileId = :profileId AND a.branch.branchId = :branchId AND a.appointmentDate = :date AND a.status IN :statuses")
    List<Appointment> findActiveForPatientDay(@Param("profileId") Long profileId, @Param("branchId") Long branchId,
            @Param("date") LocalDate date, @Param("statuses") List<AppointmentStatus> statuses);
}
