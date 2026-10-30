package com.cvdcms.reception;
import com.cvdcms.entity.Appointment;
import com.cvdcms.entity.enums.AppointmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
public interface ReceptionCheckInAppointmentRepository extends JpaRepository<Appointment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("SELECT a FROM Appointment a WHERE a.appointmentId = :id")
    Optional<Appointment> lockById(@Param("id") Long id);
    @Query("""
            SELECT a FROM Appointment a JOIN FETCH a.patientProfile p JOIN FETCH a.branch b LEFT JOIN FETCH a.doctor d
            WHERE a.appointmentDate = :day AND b.branchId = :branchId AND a.status IN :statuses
            ORDER BY a.expectedTime, a.appointmentId""")
    List<Appointment> forDay(@Param("branchId") Long branchId, @Param("day") LocalDate day,
                             @Param("statuses") List<AppointmentStatus> statuses);
}
