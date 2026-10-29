package com.cvdcms.repository;

import com.cvdcms.entity.Refund;
import com.cvdcms.entity.enums.RefundMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByRefundCode(String refundCode);

    List<Refund> findByInvoice_InvoiceIdOrderByRefundedAtDesc(Long invoiceId);

    List<Refund> findByPatientProfile_ProfileIdOrderByRefundedAtDesc(Long profileId);

    @Query("""
        SELECT r FROM Refund r
        JOIN FETCH r.patientProfile pp
        JOIN FETCH r.branch b
        JOIN FETCH r.refundedBy st
        LEFT JOIN FETCH r.invoice inv
        WHERE r.refundId = :refundId
    """)
    Optional<Refund> findWithDetailsById(@Param("refundId") Long refundId);

    @Query("""
        SELECT r FROM Refund r
        JOIN r.patientProfile pp
        JOIN r.branch b
        LEFT JOIN r.invoice inv
        WHERE (:branchId IS NULL OR b.branchId = :branchId)
          AND (:refundMethod IS NULL OR r.refundMethod = :refundMethod)
          AND (:startDate IS NULL OR r.refundedAt >= :startDate)
          AND (:endDate IS NULL OR r.refundedAt <= :endDate)
          AND (
              :keyword IS NULL OR :keyword = ''
              OR LOWER(r.refundCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(pp.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(pp.patientCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR pp.phoneNumber LIKE CONCAT('%', :keyword, '%')
              OR (inv IS NOT NULL AND LOWER(inv.invoiceCode) LIKE LOWER(CONCAT('%', :keyword, '%')))
          )
    """)
    Page<Refund> findFilteredRefunds(
            @Param("branchId") Long branchId,
            @Param("refundMethod") RefundMethod refundMethod,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
        SELECT COALESCE(SUM(r.amount), 0) FROM Refund r
        WHERE (:branchId IS NULL OR r.branch.branchId = :branchId)
          AND (:startDate IS NULL OR r.refundedAt >= :startDate)
          AND (:endDate IS NULL OR r.refundedAt <= :endDate)
    """)
    BigDecimal sumRefundAmount(
            @Param("branchId") Long branchId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );
}
