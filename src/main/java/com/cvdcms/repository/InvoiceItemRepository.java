package com.cvdcms.repository;

import com.cvdcms.entity.InvoiceItem;
import com.cvdcms.entity.enums.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {

    List<InvoiceItem> findByInvoice_InvoiceId(Long invoiceId);

    @Query("""
        SELECT ii FROM InvoiceItem ii
        LEFT JOIN FETCH ii.service s
        LEFT JOIN FETCH ii.performedService ps
        LEFT JOIN FETCH ii.prescriptionItem pi
        LEFT JOIN FETCH ii.medication m
        LEFT JOIN FETCH ii.batch b
        WHERE ii.invoice.invoiceId = :invoiceId
    """)
    List<InvoiceItem> findWithDetailsByInvoiceId(@Param("invoiceId") Long invoiceId);

    @Query("""
        SELECT ii.performedService.performedId FROM InvoiceItem ii
        WHERE ii.performedService IS NOT NULL
          AND ii.invoice.status != :cancelledStatus
          AND ii.invoice.visit.visitId = :visitId
    """)
    List<Long> findBilledPerformedServiceIdsByVisitId(
            @Param("visitId") Long visitId,
            @Param("cancelledStatus") InvoiceStatus cancelledStatus
    );

    @Query("""
        SELECT COUNT(ii) > 0 FROM InvoiceItem ii
        WHERE ii.performedService.performedId = :performedId
          AND ii.invoice.status != :cancelledStatus
    """)
    boolean existsByPerformedIdAndInvoiceNotCancelled(
            @Param("performedId") Long performedId,
            @Param("cancelledStatus") InvoiceStatus cancelledStatus
    );

    default boolean existsByPerformedIdAndInvoiceNotCancelled(Long performedId) {
        return existsByPerformedIdAndInvoiceNotCancelled(performedId, InvoiceStatus.CANCELLED);
    }

    default List<Long> findBilledPerformedServiceIdsByVisitId(Long visitId) {
        return findBilledPerformedServiceIdsByVisitId(visitId, InvoiceStatus.CANCELLED);
    }
}
