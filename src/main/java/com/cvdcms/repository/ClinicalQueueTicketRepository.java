package com.cvdcms.repository;

import com.cvdcms.entity.QueueTicket;
import com.cvdcms.entity.enums.QueueType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClinicalQueueTicketRepository extends JpaRepository<QueueTicket, Long> {
    List<QueueTicket> findByVisitVisitId(Long visitId);

    List<QueueTicket> findByVisitVisitIdAndQueueType(Long visitId, QueueType type);

    List<QueueTicket> findByVisitVisitIdInAndQueueTypeOrderByTicketIdDesc(Collection<Long> visitIds, QueueType type);

    @Query("select coalesce(max(t.ticketNumber), 0) from QueueTicket t where t.branch.branchId = :branchId and t.queueDate = :queueDate and t.queueType = :type and t.isPriority = :priority")
    int maxTicketNumber(@Param("branchId") Long branchId, @Param("queueDate") LocalDate queueDate, @Param("type") QueueType type, @Param("priority") boolean priority);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM QueueTicket t WHERE t.ticketId = :id")
    java.util.Optional<QueueTicket> lockById(@Param("id") Long id);

    @Query("SELECT t FROM QueueTicket t WHERE t.visit.visitId = :visitId AND t.queueType = :type ORDER BY t.ticketId DESC")
    List<QueueTicket> findByVisitIdAndTypeOrderByDesc(@Param("visitId") Long visitId, @Param("type") QueueType type);

    @Query("""
            SELECT t FROM QueueTicket t
            JOIN FETCH t.visit v
            JOIN FETCH v.patientProfile p
            LEFT JOIN FETCH v.primaryDoctor d
            JOIN FETCH t.branch b
            WHERE t.branch.branchId = :branchId
              AND t.queueDate = :queueDate
              AND t.queueType = :type
            ORDER BY t.ticketNumber ASC, t.ticketId ASC
            """)
    List<QueueTicket> findForBoard(@Param("branchId") Long branchId,
                                   @Param("queueDate") LocalDate queueDate,
                                   @Param("type") QueueType type);

    @Query("""
            SELECT t FROM QueueTicket t
            JOIN FETCH t.visit v
            JOIN FETCH v.patientProfile p
            LEFT JOIN FETCH v.primaryDoctor d
            JOIN FETCH t.branch b
            WHERE (t.branch.branchId = :branchId OR v.branch.branchId = :branchId)
              AND t.queueDate = :queueDate
              AND t.queueType = :type
              AND d.staffId = :doctorId
            ORDER BY t.ticketNumber ASC, t.ticketId ASC
            """)
    List<QueueTicket> findForDoctorOnDay(@Param("branchId") Long branchId,
                                         @Param("queueDate") LocalDate queueDate,
                                         @Param("type") QueueType type,
                                         @Param("doctorId") Long doctorId);

    @Query("""
            SELECT t FROM QueueTicket t
            JOIN FETCH t.visit v
            JOIN FETCH v.patientProfile p
            LEFT JOIN FETCH v.primaryDoctor d
            JOIN FETCH t.branch b
            WHERE t.queueDate = :queueDate
              AND t.queueType = :type
              AND d.staffId = :doctorId
            ORDER BY t.ticketNumber ASC, t.ticketId ASC
            """)
    List<QueueTicket> findForDoctorAcrossAllBranchesOnDay(@Param("queueDate") LocalDate queueDate,
                                                          @Param("type") QueueType type,
                                                          @Param("doctorId") Long doctorId);

    @Query("""
            SELECT t FROM QueueTicket t
            JOIN FETCH t.visit v
            LEFT JOIN FETCH v.primaryDoctor d
            JOIN FETCH t.branch b
            WHERE t.queueDate = :queueDate
              AND t.queueType = :type
              AND t.status = com.cvdcms.entity.enums.QueueStatus.WAITING
              AND b.isActive = true
            ORDER BY t.ticketNumber ASC, t.ticketId ASC
            """)
    List<QueueTicket> findWaitingWithDoctorOnDay(@Param("queueDate") LocalDate queueDate,
                                                 @Param("type") QueueType type);

    @Query("""
            SELECT t FROM QueueTicket t
            JOIN FETCH t.visit v
            LEFT JOIN FETCH v.primaryDoctor d
            JOIN FETCH t.branch b
            WHERE t.queueDate = :queueDate
              AND t.queueType = :type
              AND t.status = com.cvdcms.entity.enums.QueueStatus.CALLED
              AND b.isActive = true
            ORDER BY t.ticketId ASC
            """)
    List<QueueTicket> findCalledOnDay(@Param("queueDate") LocalDate queueDate,
                                      @Param("type") QueueType type);

    @Query("""
            SELECT COUNT(t) FROM QueueTicket t
            WHERE t.branch.branchId = :branchId
              AND t.queueDate = :queueDate
              AND t.queueType = :type
              AND t.visit.primaryDoctor.staffId = :doctorId
              AND t.status IN :statuses
              AND t.ticketId <> :excludeId
            """)
    long countOtherForDoctor(@Param("branchId") Long branchId,
                             @Param("queueDate") LocalDate queueDate,
                             @Param("type") QueueType type,
                             @Param("doctorId") Long doctorId,
                             @Param("statuses") Collection<com.cvdcms.entity.enums.QueueStatus> statuses,
                             @Param("excludeId") Long excludeId);
}
