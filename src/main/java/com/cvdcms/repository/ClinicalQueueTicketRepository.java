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
    List<QueueTicket> findByVisitVisitIdAndQueueType(Long visitId, QueueType type);

    List<QueueTicket> findByVisitVisitIdInAndQueueTypeOrderByTicketIdDesc(Collection<Long> visitIds, QueueType type);

    @Query("select coalesce(max(t.ticketNumber), 0) from QueueTicket t where t.branch.branchId = :branchId and t.queueDate = :queueDate and t.queueType = :type and t.isPriority = :priority")
    int maxTicketNumber(@Param("branchId") Long branchId, @Param("queueDate") LocalDate queueDate, @Param("type") QueueType type, @Param("priority") boolean priority);
}
