package com.cvdcms.repository;

import com.cvdcms.entity.BookingConsultationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingConsultationLogRepository extends JpaRepository<BookingConsultationLog, Long> {

    @Query("""
            SELECT l FROM BookingConsultationLog l
            JOIN FETCH l.actorUser actor
            LEFT JOIN FETCH l.targetUser target
            WHERE l.consultationRequest.requestId = :requestId
            ORDER BY l.createdAt ASC, l.logId ASC
            """)
    List<BookingConsultationLog> findHistoryByRequestId(@Param("requestId") Long requestId);
}
