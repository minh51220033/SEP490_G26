package com.cvdcms.reception;
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
public interface ReceptionCheckInAppointmentRepository extends JpaRepository<Appointment, Long> {
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
    @Query("""
            SELECT a FROM Appointment a JOIN FETCH a.patientProfile p JOIN FETCH a.branch b LEFT JOIN FETCH a.doctor d
            WHERE a.appointmentDate = :day AND b.branchId = :branchId AND a.status IN :statuses
            ORDER BY a.expectedTime, a.appointmentId""")
    List<Appointment> forDay(@Param("branchId") Long branchId, @Param("day") LocalDate day,
                             @Param("statuses") List<AppointmentStatus> statuses);
}
