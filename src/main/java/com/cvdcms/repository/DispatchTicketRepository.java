package com.cvdcms.repository;

import com.cvdcms.entity.QueueTicket;
import com.cvdcms.entity.enums.QueueType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DispatchTicketRepository extends JpaRepository<QueueTicket, Long> {
    @Query("select t from QueueTicket t where t.visit.visitId = :visit and t.queueType = :type order by t.ticketId desc")
    List<QueueTicket> findAllByVisitAndType(@Param("visit") Long visit, @Param("type") QueueType type);

    default Optional<QueueTicket> findByVisitVisitIdAndQueueType(Long visit, QueueType type) {
        List<QueueTicket> list = findAllByVisitAndType(visit, type);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Query("select coalesce(max(t.ticketNumber), 0) from QueueTicket t where t.branch.branchId = :branch and t.queueDate = :day and t.queueType = :type and t.isPriority = :priority")
    int maxNumber(@Param("branch") Long branch, @Param("day") LocalDate day, @Param("type") QueueType type, @Param("priority") boolean priority);

    @Query("select t from QueueTicket t join fetch t.visit v join fetch v.patientProfile where t.branch.branchId = :branch and t.queueDate = :day and t.queueType = :type order by t.isEmergency desc, t.isPriority desc, t.ticketNumber")
    List<QueueTicket> board(@Param("branch") Long branch, @Param("day") LocalDate day, @Param("type") QueueType type);
}