package com.cvdcms.reception;
import com.cvdcms.entity.Visit;
import com.cvdcms.entity.enums.VisitStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
public interface ReceptionVisitRepository extends JpaRepository<Visit, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Visit v WHERE v.appointmentId = :appointmentId AND v.status <> com.cvdcms.entity.enums.VisitStatus.CANCELLED ORDER BY v.visitId DESC")
    List<Visit> findActiveByAppointmentIdForUpdate(@Param("appointmentId") Long appointmentId);

    @Query("SELECT v FROM Visit v WHERE v.appointmentId = :appointmentId ORDER BY v.visitId DESC")
    List<Visit> findAllByAppointmentIdOrderByVisitIdDesc(@Param("appointmentId") Long appointmentId);

    default Optional<Visit> findByAppointmentId(Long appointmentId) {
        List<Visit> active = findActiveByAppointmentIdForUpdate(appointmentId);
        return active == null || active.isEmpty() ? Optional.empty() : Optional.of(active.get(0));
    }
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Visit v WHERE v.patientProfile.profileId = :profileId AND v.status IN :statuses")
    List<Visit> activeForProfile(@Param("profileId") Long profileId, @Param("statuses") Set<VisitStatus> statuses);
    @Query("""
            SELECT v FROM Visit v JOIN FETCH v.patientProfile p JOIN FETCH v.branch b LEFT JOIN FETCH v.primaryDoctor d
            WHERE b.branchId = :branchId AND v.checkInAt >= :start AND v.checkInAt < :end ORDER BY v.checkInAt DESC, v.visitId DESC""")
    List<Visit> forDay(@Param("branchId") Long branchId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            SELECT v FROM Visit v JOIN FETCH v.patientProfile p JOIN FETCH v.branch b LEFT JOIN FETCH v.primaryDoctor d
            WHERE b.branchId = :branchId ORDER BY v.checkInAt DESC, v.visitId DESC""")
    List<Visit> forAllDays(@Param("branchId") Long branchId);

    @Query(value = """
            SELECT v FROM Visit v JOIN FETCH v.patientProfile p JOIN FETCH v.branch b LEFT JOIN FETCH v.primaryDoctor d
            WHERE b.branchId = :branchId
              AND v.checkInAt >= :start
              AND v.checkInAt < :end
              AND v.status NOT IN :excludedStatuses
            ORDER BY v.checkInAt DESC, v.visitId DESC""",
           countQuery = """
            SELECT COUNT(v) FROM Visit v
            WHERE v.branch.branchId = :branchId
              AND v.checkInAt >= :start
              AND v.checkInAt < :end
              AND v.status NOT IN :excludedStatuses""")
    Page<Visit> findReceptionHistoryByCheckInAtAndExcludedStatuses(
            @Param("branchId") Long branchId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("excludedStatuses") Collection<VisitStatus> excludedStatuses,
            Pageable pageable);

    @Query(value = """
            SELECT v FROM Visit v JOIN FETCH v.patientProfile p JOIN FETCH v.branch b LEFT JOIN FETCH v.primaryDoctor d
            WHERE b.branchId = :branchId
              AND v.checkInAt >= :start
              AND v.checkInAt < :end
            ORDER BY v.checkInAt DESC, v.visitId DESC""",
           countQuery = """
            SELECT COUNT(v) FROM Visit v
            WHERE v.branch.branchId = :branchId
              AND v.checkInAt >= :start
              AND v.checkInAt < :end""")
    Page<Visit> findReceptionHistoryByCheckInAt(
            @Param("branchId") Long branchId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable);

    default Page<Visit> findReceptionHistory(
            Long branchId,
            LocalDateTime start,
            LocalDateTime end,
            Collection<VisitStatus> excludedStatuses,
            Pageable pageable) {
        if (excludedStatuses == null || excludedStatuses.isEmpty()) {
            return findReceptionHistoryByCheckInAt(branchId, start, end, pageable);
        }
        return findReceptionHistoryByCheckInAtAndExcludedStatuses(branchId, start, end, excludedStatuses, pageable);
    }
}
