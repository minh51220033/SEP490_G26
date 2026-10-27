package com.cvdcms.repository;

import com.cvdcms.entity.InvoiceAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceAuditLogRepository extends JpaRepository<InvoiceAuditLog, Long> {

    @Query("""
        SELECT log FROM InvoiceAuditLog log
        LEFT JOIN FETCH log.performedBy p
        WHERE log.invoice.invoiceId = :invoiceId
        ORDER BY log.performedAt DESC
    """)
    List<InvoiceAuditLog> findByInvoiceIdWithStaff(@Param("invoiceId") Long invoiceId);
}
