package com.cvdcms.repository;

import com.cvdcms.entity.QueueTicket;
import com.cvdcms.entity.enums.QueueType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicalQueueTicketRepository extends JpaRepository<QueueTicket, Long> {
    List<QueueTicket> findByVisitVisitIdAndQueueType(Long visitId, QueueType type);
}
