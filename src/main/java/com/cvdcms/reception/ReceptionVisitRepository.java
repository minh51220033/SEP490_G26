package com.cvdcms.reception;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
public interface ReceptionVisitRepository extends JpaRepository<Visit, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<Visit> findByAppointmentId(Long appointmentId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Visit v WHERE v.patientProfile.profileId = :profileId AND v.status IN :statuses")
    List<Visit> activeForProfile(@Param("profileId") Long profileId, @Param("statuses") Set<VisitStatus> statuses);
    @Query("""
            SELECT v FROM Visit v JOIN FETCH v.patientProfile p JOIN FETCH v.branch b LEFT JOIN FETCH v.primaryDoctor d
            WHERE b.branchId = :branchId AND v.checkInAt >= :start AND v.checkInAt < :end ORDER BY v.checkInAt DESC, v.visitId DESC""")
    List<Visit> forDay(@Param("branchId") Long branchId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
