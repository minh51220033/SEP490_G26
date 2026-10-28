package com.cvdcms.repository;

import com.cvdcms.entity.Invoice;
import com.cvdcms.entity.enums.InvoicePaymentStatus;
import com.cvdcms.entity.enums.InvoiceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByInvoiceCode(String invoiceCode);

    List<Invoice> findByVisit_VisitId(Long visitId);

    boolean existsByVisit_VisitIdAndStatusNot(Long visitId, InvoiceStatus status);

    boolean existsByVoucher_VoucherIdAndStatusNot(Long voucherId, InvoiceStatus status);

    List<Invoice> findByPatientProfile_ProfileIdOrderByIssuedAtDesc(Long profileId);

    List<Invoice> findByPatientProfile_ProfileIdAndStatusNotOrderByIssuedAtDesc(Long profileId, InvoiceStatus status);

    List<Invoice> findByPatientProfile_ProfileIdAndPaymentStatusIn(
            Long profileId,
            List<InvoicePaymentStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.invoiceId = :invoiceId")
    Optional<Invoice> findByIdForUpdate(@Param("invoiceId") Long invoiceId);

    @Query("""
        SELECT i FROM Invoice i
        LEFT JOIN FETCH i.patientProfile pp
        LEFT JOIN FETCH i.branch b
        LEFT JOIN FETCH i.cashier c
        LEFT JOIN FETCH i.visit v
        WHERE i.invoiceId = :invoiceId
    """)
    Optional<Invoice> findWithDetailsById(@Param("invoiceId") Long invoiceId);

    @Query("""
        SELECT i FROM Invoice i
        JOIN i.patientProfile pp
        JOIN i.branch b
        WHERE (:branchId IS NULL OR b.branchId = :branchId)
          AND (:paymentStatus IS NULL OR i.paymentStatus = :paymentStatus)
          AND (:status IS NULL OR i.status = :status)
          AND (:startDate IS NULL OR i.issuedAt >= :startDate)
          AND (:endDate IS NULL OR i.issuedAt <= :endDate)
          AND (
              :keyword IS NULL OR :keyword = ''
              OR LOWER(i.invoiceCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(pp.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR LOWER(pp.patientCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
              OR pp.phoneNumber LIKE CONCAT('%', :keyword, '%')
          )
    """)
    Page<Invoice> findFilteredInvoices(
            @Param("branchId") Long branchId,
            @Param("paymentStatus") InvoicePaymentStatus paymentStatus,
            @Param("status") InvoiceStatus status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
        SELECT COALESCE(SUM(i.totalAmount), 0) FROM Invoice i
        WHERE (:branchId IS NULL OR i.branch.branchId = :branchId)
          AND i.status != 'CANCELLED'
          AND (:startDate IS NULL OR i.issuedAt >= :startDate)
          AND (:endDate IS NULL OR i.issuedAt <= :endDate)
    """)
    BigDecimal sumTotalAmount(
            @Param("branchId") Long branchId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("""
        SELECT COALESCE(SUM(i.paidAmount), 0) FROM Invoice i
        WHERE (:branchId IS NULL OR i.branch.branchId = :branchId)
          AND i.status != 'CANCELLED'
          AND (:startDate IS NULL OR i.issuedAt >= :startDate)
          AND (:endDate IS NULL OR i.issuedAt <= :endDate)
    """)
    BigDecimal sumPaidAmount(
            @Param("branchId") Long branchId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("""
        SELECT COALESCE(SUM(i.remainingDebt), 0) FROM Invoice i
        WHERE (:branchId IS NULL OR i.branch.branchId = :branchId)
          AND i.status != 'CANCELLED'
          AND i.paymentStatus IN ('UNPAID', 'PARTIAL')
          AND (:startDate IS NULL OR i.issuedAt >= :startDate)
          AND (:endDate IS NULL OR i.issuedAt <= :endDate)
    """)
    BigDecimal sumRemainingDebt(
            @Param("branchId") Long branchId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("""
        SELECT COUNT(i) FROM Invoice i
        WHERE (:branchId IS NULL OR i.branch.branchId = :branchId)
          AND (:startDate IS NULL OR i.issuedAt >= :startDate)
          AND (:endDate IS NULL OR i.issuedAt <= :endDate)
    """)
    long countInvoices(
            @Param("branchId") Long branchId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("""
        SELECT DISTINCT i.patientProfile.profileId FROM Invoice i
        WHERE (:branchId IS NULL OR i.branch.branchId = :branchId)
          AND i.status != 'CANCELLED'
          AND i.remainingDebt > 0
    """)
    List<Long> findPatientProfileIdsWithDebt(@Param("branchId") Long branchId);

    // Truy vấn tổng tài chính cho 1 bệnh nhân cụ thể (SCR-22.1)
    @Query("""
        SELECT COALESCE(SUM(i.totalAmount), 0) FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != 'CANCELLED'
    """)
    BigDecimal sumTotalInvoicedByPatient(@Param("profileId") Long profileId);

    @Query("""
        SELECT COALESCE(SUM(i.paidAmount), 0) FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != 'CANCELLED'
    """)
    BigDecimal sumTotalPaidByPatient(@Param("profileId") Long profileId);

    @Query("""
        SELECT COALESCE(SUM(i.remainingDebt), 0) FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != 'CANCELLED'
          AND i.paymentStatus IN ('UNPAID', 'PARTIAL')
    """)
    BigDecimal sumTotalDebtByPatient(@Param("profileId") Long profileId);

    // Truy vấn theo danh sách chi nhánh được phép (Bảo vệ dữ liệu chi nhánh)
    @Query("""
        SELECT COALESCE(SUM(i.totalAmount), 0) FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != 'CANCELLED'
          AND (:branchIds IS NULL OR i.branch.branchId IN :branchIds)
    """)
    BigDecimal sumTotalInvoicedByPatientAndBranches(
            @Param("profileId") Long profileId,
            @Param("branchIds") List<Long> branchIds
    );

    @Query("""
        SELECT COALESCE(SUM(i.paidAmount), 0) FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != 'CANCELLED'
          AND (:branchIds IS NULL OR i.branch.branchId IN :branchIds)
    """)
    BigDecimal sumTotalPaidByPatientAndBranches(
            @Param("profileId") Long profileId,
            @Param("branchIds") List<Long> branchIds
    );

    @Query("""
        SELECT COALESCE(SUM(i.remainingDebt), 0) FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != 'CANCELLED'
          AND i.paymentStatus IN ('UNPAID', 'PARTIAL')
          AND (:branchIds IS NULL OR i.branch.branchId IN :branchIds)
    """)
    BigDecimal sumTotalDebtByPatientAndBranches(
            @Param("profileId") Long profileId,
            @Param("branchIds") List<Long> branchIds
    );

    @Query("""
        SELECT i FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != 'CANCELLED'
          AND (:branchIds IS NULL OR i.branch.branchId IN :branchIds)
        ORDER BY i.issuedAt DESC
    """)
    List<Invoice> findInvoicesByPatientAndBranches(
            @Param("profileId") Long profileId,
            @Param("branchIds") List<Long> branchIds
    );

    @Query("""
        SELECT i FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != 'CANCELLED'
          AND i.paymentStatus IN ('UNPAID', 'PARTIAL')
          AND (:branchIds IS NULL OR i.branch.branchId IN :branchIds)
        ORDER BY i.issuedAt DESC
    """)
    List<Invoice> findUnpaidInvoicesByPatientAndBranches(
            @Param("profileId") Long profileId,
            @Param("branchIds") List<Long> branchIds
    );
    Page<Invoice> findByPatientProfile_ProfileIdAndStatusNot(
            Long profileId,
            InvoiceStatus status,
            Pageable pageable
    );

    Page<Invoice> findByPatientProfile_ProfileIdAndStatusNotAndBranch_BranchIdIn(
            Long profileId,
            InvoiceStatus status,
            List<Long> branchIds,
            Pageable pageable
    );

    @Query(value = """
        SELECT i FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != :status
          AND (:startDate IS NULL OR i.issuedAt >= :startDate)
          AND (:endDate IS NULL OR i.issuedAt < :endDate)
    """, countQuery = """
        SELECT COUNT(i) FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != :status
          AND (:startDate IS NULL OR i.issuedAt >= :startDate)
          AND (:endDate IS NULL OR i.issuedAt < :endDate)
    """)
    Page<Invoice> findInvoicesByPatientAndDates(
            @Param("profileId") Long profileId,
            @Param("status") InvoiceStatus status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );

    @Query(value = """
        SELECT i FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != :status
          AND i.branch.branchId IN :branchIds
          AND (:startDate IS NULL OR i.issuedAt >= :startDate)
          AND (:endDate IS NULL OR i.issuedAt < :endDate)
    """, countQuery = """
        SELECT COUNT(i) FROM Invoice i
        WHERE i.patientProfile.profileId = :profileId
          AND i.status != :status
          AND i.branch.branchId IN :branchIds
          AND (:startDate IS NULL OR i.issuedAt >= :startDate)
          AND (:endDate IS NULL OR i.issuedAt < :endDate)
    """)
    Page<Invoice> findInvoicesByPatientAndBranchesAndDates(
            @Param("profileId") Long profileId,
            @Param("status") InvoiceStatus status,
            @Param("branchIds") List<Long> branchIds,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );
}

