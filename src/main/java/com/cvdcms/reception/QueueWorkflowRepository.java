package com.cvdcms.reception;

import com.cvdcms.entity.QueueTicket;
import com.cvdcms.entity.enums.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.*;

interface QueueWorkflowRepository extends JpaRepository<QueueTicket, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from QueueTicket t where t.ticketId = :id")
    Optional<QueueTicket> lock(@Param("id") Long id);

    @Query("select t from QueueTicket t join fetch t.visit v join fetch v.patientProfile join fetch t.branch where t.branch.branchId = :branch and t.queueDate = :day and t.queueType = :type")
    List<QueueTicket> forBoard(@Param("branch") Long branch, @Param("day") LocalDate day, @Param("type") QueueType type);

    @Query("select count(t) from QueueTicket t where t.branch.branchId = :branch and t.queueDate = :day and t.queueType = :type and t.status = :status and t.ticketId <> :id")
    long countOther(@Param("branch") Long branch, @Param("day") LocalDate day,
                    @Param("type") QueueType type, @Param("status") QueueStatus status, @Param("id") Long id);
}
